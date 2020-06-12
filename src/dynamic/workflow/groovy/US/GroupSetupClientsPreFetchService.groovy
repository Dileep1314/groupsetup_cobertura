package groovy.US

import java.util.concurrent.CompletableFuture

import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration
import net.minidev.json.parser.JSONParser

/**
 * This Class is used for prefetch soldproposal and store into db
 * @author mchalla0, vishal, Narsi
 *
 */

class GroupSetupClientsPreFetchService  implements Task{
	Logger logger = LoggerFactory.getLogger(GroupSetupClientsPreFetchService.class)
	GroupSetupUtil utilObject = new GroupSetupUtil()
	GetSoldProposals gsProposal=new GetSoldProposals()
	def static final SERVICE_ID = "GSCPFS001"


	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		logger.info("GroupSetupClientsPreFetchService : execute(): Start")
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def tenantId = requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		def metrefID = requestPathParamsMap[GroupSetupConstants.METREF_ID]
		if(workFlow.getFact("metRefId", String.class) != null){
			metrefID = workFlow.getFact("metRefId", String.class)
		}
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(metrefID)
		logger.info("GroupSetupClientsPreFetchService : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GroupSetupClientsPreFetchService : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def isPreFtechFailed = parallelSoldProposalCall(entityService, gsspRepository, metrefID, tenantId, registeredServiceInvoker, spiHeadersMap, spiPrefix, workFlow, profile)
		workFlow.addFacts("isPreFtechFailed",isPreFtechFailed)
		logger.info("GroupSetupClientsPreFetchService : execute(): End")
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${metrefID} === MS api elapseTime : " + elapseTime1)
		workFlow.continueToNextStep()
	}

	def parallelSoldProposalCall(entityService, gsspRepository, metrefID, tenantId, registeredServiceInvoker, spiHeadersMap, spiPrefix, workFlow, profile) {
		logger.info("GroupSetupClientsPreFetchService : parallelSoldProposalCall(): Start")
		def clients = [:]
		def masterClientList = []
		def isPreFtechFailed = false
		List brokerIdList = getBrokers(registeredServiceInvoker, metrefID, tenantId, workFlow)
		brokerIdList.add(metrefID)
		logger.info("Brokers List ......... "+brokerIdList)
		Collection<CompletableFuture<Map<String, Object>>> proposalFutures = new ArrayList<>(brokerIdList.size())
		brokerIdList.each(){ bkrId->
			def clientDetailsList = []
			proposalFutures.add(CompletableFuture.supplyAsync({
				->
				def clientDetails = getGSClientsList(spiPrefix, registeredServiceInvoker, spiHeadersMap, bkrId, metrefID, profile)
//				def clientDetails= utilObject.getTestData("getSoldProposals.json")
				logger.info("getGSClientsList from IIB for Broker"+bkrId +" is "+clientDetails)
				if(clientDetails?.items) {
					clientDetails?.items.each { input->
						input?.item?.proposal<<["brokerId":bkrId]
					}
					clientDetailsList.addAll(updatedProposalList(entityService, clientDetails?.items))
					clientDetailsList = formatSPIResponse(clientDetailsList, brokerIdList)
					masterClientList.addAll(clientDetailsList)
				}
				clients<<["proposals":masterClientList]
				clients
			}))
			CompletableFuture.allOf(proposalFutures.toArray(new CompletableFuture<?>[proposalFutures.size()])).join()
		}
		deleteClientList(entityService, metrefID)
		isPreFtechFailed = saveProposal(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, clients, gsspRepository, metrefID, tenantId, isPreFtechFailed)
		logger.info("GroupSetupClientsPreFetchService : parallelSoldProposalCall(): End")
		isPreFtechFailed
	}
	
	
	def deleteClientList(entityService, metrefID){
		logger.info("GroupSetupClientsPreFetchService : deleteClientList(): Start")
		try{
			entityService?.deleteById(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, metrefID)
			logger.info("GroupSetupClientsPreFetchService : deleteClientList(): End")
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Clients by BrokereId ---> "+e.getMessage())
//			throw new GSSPException("40001")
		}
	}

	/**
	 *
	 * @param clientDetails
	 * @return
	 */
	def formatSPIResponse(clientDetails,brokerId) {
		logger.info("GroupSetupClientsPreFetchService : formatSPIResponse(): Start")
		def clientsList = []
		clientDetails.each{proposal ->
			def statusCode = proposal?.groupSetupStatusCode
			switch(statusCode){
				case GroupSetupConstants.STATUS_ACTIVE_CODE :
					proposal << [(GroupSetupConstants.GROUPSETUP_STATUS):GroupSetupConstants.STATUS_ACTIVE]
					break
				case GroupSetupConstants.STATUS_IN_ACTIVE_CODE :
					proposal << [(GroupSetupConstants.GROUPSETUP_STATUS):GroupSetupConstants.STATUS_IN_ACTIVE]
					break
				case GroupSetupConstants.STATUS_APPLICATION_NOT_STARTED_CODE :
					proposal << [(GroupSetupConstants.GROUPSETUP_STATUS):GroupSetupConstants.STATUS_APPLICATION_NOT_STARTED]
					break
				case GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS_CODE :
					proposal << [(GroupSetupConstants.GROUPSETUP_STATUS):GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS]
					break
				default :
					logger.info "Invalid Status Code....${statusCode}"
					break
			}
			clientsList.add(proposal)
		}
		logger.info("GroupSetupClientsPreFetchService : formatSPIResponse(): End")
		clientsList
	}
	/**
	 * This method is use for storing data in soldproposal from IIB
	 * @param collectionName
	 * @param data
	 * @param gsspRepository
	 * @param groupSetUpId
	 * @param tenantId
	 * @return
	 */
	def saveProposal(collectionName, data, gsspRepository, metrefID, tenantId, isPreFtechFailed) {
		logger.info("GroupSetupClientsPreFetchService : saveProposal(): Start")
		try{
			logger.info("*****IIB response****"+data)
			if(!data?.proposals)
				isPreFtechFailed = true
			data << ['_id':metrefID]
			data << ['isPreFtechFailed':isPreFtechFailed]
			gsspRepository.create(tenantId, collectionName, data)
		}catch(any){
			logger.error("Error Occured in saveAsDraft--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		logger.info("GroupSetupClientsPreFetchService : saveProposal(): End")
		isPreFtechFailed
	}
	/**
	 * This method use for sold proposal data from IIB
	 * @param spiPrefix
	 * @param registeredServiceInvoker
	 * @param spiHeadersMap
	 * @param persona
	 * @param personaId
	 * @return
	 */
	def getGSClientsList(spiPrefix, registeredServiceInvoker, spiHeadersMap, personaId, metrefID, String[] profile) {
		logger.info("GroupSetupClientsPreFetchService : getGSClientsList(): Start")
		def roleType ='Agent'
		def uri = "${spiPrefix}/role/${roleType}/persona/${personaId}/soldproposalslist"
		logger.info("getGSClientsList() :: SPI URL -> ${uri}")
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		def serviceUri = uriBuilder.build(false).toString()
		def response
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("getSoldProposals.json")
			} else {
				logger.info("${SERVICE_ID} ----> ${metrefID} ----> ${personaId} ==== Calling Soldproposalslist API ")
				Date start = new Date()
				response = registeredServiceInvoker?.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${metrefID} ----> ${personaId} ==== Soldproposalslist API Response time : "+ elapseTime)
			}
			if(response){
				response = response?.getBody()
				/*JSONParser parser = new JSONParser()
				response = parser.parse(response.toString())*/
			}
			else{
				logger.error("IIB Returned Empty response ---> :  ")
			}
		}
		catch(e) {
			logger.error("Exception occured while executing SPI Request ---> :  "+e)
			throw new GSSPException("400013")
		}
		logger.info("Response From SPI :: ----> $response")
		logger.info("GroupSetupClientsPreFetchService : getGSClientsList(): End")
		response
	}
	/**
	 *
	 * @param entityService
	 * @param proposalList
	 * @return
	 */
	def updatedProposalList(entityService, proposalList){
		logger.info("GroupSetupClientsPreFetchService : updatedProposalList(): Start")
		def updatedList = [] as List
		for(def client: proposalList){
			try{
				def proposalInfo = client?.item?.proposal
				def rfpId = proposalInfo?.rfpId
				def groupNumber = proposalInfo?.groupNumber
				def uniqueId = proposalInfo?.uniqueId
				def actualStatus = proposalInfo?.actualStatus
				def rfpType = proposalInfo?.rfpType
				proposalInfo.putAt("module", "")
				if(rfpType.equalsIgnoreCase(GroupSetupConstants.ADD_PRODUCT) && ((actualStatus && "PENDINGISSUE".equalsIgnoreCase(actualStatus.trim())) || (actualStatus == null || actualStatus.isEmpty()))){
					actualStatus = "Pending For Implementation"
					proposalInfo.putAt("actualStatus", actualStatus)
				}
				def existingSoldCase = checkForInProgressCases(entityService,groupNumber,rfpId,uniqueId)
				if("PendingImpl".equalsIgnoreCase(actualStatus.trim()) || "Pending For Implementation".equalsIgnoreCase(actualStatus.trim())){
					if(existingSoldCase){
						proposalInfo.putAt("groupSetupStatusCode", GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS_CODE)
					}
					else {
						proposalInfo.putAt("groupSetupStatusCode", GroupSetupConstants.STATUS_APPLICATION_NOT_STARTED_CODE)
					}
				}else if("PendingReview".equalsIgnoreCase(actualStatus) || "Pending Review".equalsIgnoreCase(actualStatus) || "Terminated".equalsIgnoreCase(actualStatus)){
					def module = existingSoldCase?.extension?.module
					proposalInfo.putAt("module", module)
				}
				updatedList.add(proposalInfo)
			}
			catch(e){
				logger.error("Error while updating ProposalList,  Proposal:${client} ---> "+e.getMessage())
			}
		}
		logger.info("GroupSetupClientsPreFetchService : updatedProposalList(): End")
		updatedList
	}
	/**
	 *
	 * @param entityService
	 * @param groupId
	 * @param rfpId
	 * @param soldCaseNumber
	 * @return
	 */
	def checkForInProgressCases(entityService, groupId, rfpId, soldCaseNumber) {
		logger.info("GroupSetupClientsPreFetchService : checkForInProgressCases(): Start")
		def result
		def groupSetUpId=groupId+"_"+rfpId+"_"+soldCaseNumber
		try{
			EntityResult results = entityService.findById("US", GroupSetupConstants.PER_COLLECTION_NAME, groupSetUpId,[])
			result=results.getData()
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Clients by groupSetUpId :${groupSetUpId} ---> "+e.getMessage())
			//throw new GSSPException("40001")
		}
		logger.info("GroupSetupClientsPreFetchService : checkForInProgressCases(): End")
		return result
	}
	/**
	 * 
	 * @param registeredServiceInvoker
	 * @param metrefId
	 * @param tenantId
	 * @param workflow
	 * @return
	 */

	def getBrokers(registeredServiceInvoker, metrefId, tenantId, workflow) {
		logger.info("GroupSetupClientsPreFetchService : getBrokers(): Start")
		def sharedServiceVip = "metlife-smd-gssp-contactmetlife-service"//workflow.getEnvPropertyFromContext()
		def entitlementUri = "/v1/tenants/${tenantId}/party/${metrefId}/getEntitlement"
		def brokerList = []
		try {
			logger.info("${SERVICE_ID} ----> ${metrefId} ==== Calling Entitlement API ")
			Date start = new Date()
			def response = registeredServiceInvoker.get(sharedServiceVip, entitlementUri, new HashMap(), Map.class)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> Get entitlement api for ${metrefId}, elapseTime: " + elapseTime)
			response = response.getBody()
			logger.info("Response From Entitlement::: "+response)
			def brokerIdList = response?.brokerRelations
			brokerIdList.each() { broker->
				brokerList.add(broker?.brokerID)
			}
			logger.info("brokerList.size()::: " + brokerList.size())
		} catch(e) {
			logger.error("getEntitlement is Failed"+e.getMessage())
			//throw new GSSPException('10057')
		}
		logger.info("GroupSetupClientsPreFetchService : getBrokers(): End")
		brokerList
	}
}
