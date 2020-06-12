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

/**
 * This Class is used to pre-fetch clients from Majesco and store in local DB.
 * @author Vishal
 *
 */
class PreFetchClients  implements Task{
	
	def entityService,registeredServiceInvoker,gsspRepository,sharedServiceVip
	def requestPathParamsMap,headersList,requestHeaders,spiPrefix,spiHeadersMap
	def validationFlag,metRefId,tenantIdsmdGsspId,smdGsspServiceId,tenantId,smdGsspId
	
	Logger logger = LoggerFactory.getLogger(PreFetchClients.class)
	GroupSetupUtil utilObject = new GroupSetupUtil()
	
	@Override
	public Object execute(WorkflowDomain workFlow) {
		logTransactionTime("REQUEST",this.getClass())
		extractCommonDetails(workFlow)
		utilObject.authenticateUser(metRefId,entityService,requestPathParamsMap,validationFlag,requestHeaders)
		def clients = getClientsAsync()
		utilObject.deleteClientsById(entityService,metRefId)
		def isPreFtechFailed=utilObject.saveClientDetails(entityService,clients,metRefId,false)
		logTransactionTime("RESPONSE",this.getClass())
		workFlow.addFacts("isPreFtechFailed",isPreFtechFailed)
		workFlow.continueToNextStep()
	}
	
	def extractCommonDetails(workFlow) {
		this.entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		this.requestPathParamsMap = workFlow.getRequestPathParams()
		this.validationFlag = workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SECURITY_CHECK)
		this.metRefId = requestPathParamsMap[GroupSetupConstants.METREF_ID]
		this.sharedServiceVip = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SHAREDSERVICEVIP)
		if(workFlow.getFact("metRefId", String.class) != null){
			metRefId = workFlow.getFact("metRefId", String.class)
		}
		this.registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		this.spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		this.gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		this.tenantId = requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		this.headersList=workFlow.getEnvPropertyFromContext(GroupSetupConstants.GSSP_HEADERS)
		this.requestHeaders =  workFlow.getRequestHeader()
		this.smdGsspId=workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_TENANTID)
		this.smdGsspServiceId=workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_SERVICEID)
		this.spiHeadersMap = utilObject.buildSPICallHeader(headersList,requestHeaders,smdGsspId,smdGsspServiceId, GroupSetupConstants.GET_METHOD)
	}
	
	/**
	 * method executes in parallel to extract list of clients for agent
	 * @return clients
	 */
	
	def getClientsAsync() {
		def clients = [:]
		def masterClientList = []
		def isPreFtechFailed = false
		List brokerIdList = getBrokers()
		logger.info("Brokers List ......... "+brokerIdList)
		brokerIdList.add(metRefId)
		Collection<CompletableFuture<Map<String, Object>>> proposalFutures = new ArrayList<>(brokerIdList.size())
		//RetrieveClients retrieveClientsObj=new RetrieveClients()
		brokerIdList.each(){ bkrId->
			def clientDetailsList = []
			proposalFutures.add(CompletableFuture.supplyAsync({
				->
				def clientDetails =utilObject.getClients("Agent",spiPrefix,bkrId,registeredServiceInvoker,spiHeadersMap)
				// Mock Data for local
				//def clientDetails= utilObject.getTestData("getSoldProposals.json")
				logger.info("getGSClientsList from IIB for Broker"+bkrId +" is "+clientDetails)
				if(clientDetails?.items) {
					clientDetails?.items.each { input->
						input?.item?.proposal<<["brokerId":bkrId]
					}
					//clientDetailsList.addAll(updatedProposalList(entityService, clientDetails?.items))
					clientDetailsList.addAll(updateProposal(clientDetails?.items))
					masterClientList.addAll(clientDetailsList)
				}
				clients<<["proposals":masterClientList]
				clients
			}))
			CompletableFuture.allOf(proposalFutures.toArray(new CompletableFuture<?>[proposalFutures.size()])).join()
		}
	    clients
	}
	/**
	 * Getting GroupSetup data from permanent Mongo db
	 * @param groupId
	 * @param rfpId
	 * @param soldCaseNumber
	 * @return
	 */
	def checkForInProgressCases(groupId, rfpId, soldCaseNumber) {
		def result
		def groupSetUpId=groupId+"_"+rfpId+"_"+soldCaseNumber
		try{
			EntityResult results = entityService.findById("US", GroupSetupConstants.PER_COLLECTION_NAME, groupSetUpId,[])
			result=results.getData()
			logger.info("checkForInProgressCases result---> "+result)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Clients by groupSetUpId :${groupSetUpId} ---> "+e.getMessage())
			//throw new GSSPException("40001")
		}
		return result
	}
	
	/**
	 * This Method is update the sold proposal given by IIB
	 * @param proposalList
	 * @return
	 */
	def updateProposal(proposalList){
		List updatedList = new ArrayList()
		for(def client: proposalList){
			def proposal = [:] as Map
			def item = [:] as Map
			def proposalInfo = client?.item?.proposal
			def rfpId = proposalInfo?.rfpId
			def groupNumber = proposalInfo?.groupNumber
			def uniqueId = proposalInfo?.uniqueId
			def actualStatus = proposalInfo?.actualStatus
			def statusCode = proposalInfo?.groupSetupStatusCode
			proposalInfo.putAt("module", "")
			def existingSoldCase = checkForInProgressCases(groupNumber,rfpId,uniqueId)
			proposalInfo=setCodes(proposalInfo,statusCode,actualStatus,existingSoldCase)
			updatedList.add(proposalInfo)
		}
		updatedList
	}
	/**
	 * This Method is uses for setting the status and status code of group
	 * @param proposalInfo
	 * @param statusCode
	 * @param actualStatus
	 * @param existingSoldCase
	 * @return
	 */
	def setCodes(proposalInfo,statusCode,actualStatus,existingSoldCase) {

		if(statusCode?.equals(GroupSetupConstants.STATUS_ACTIVE_CODE)) {
			proposalInfo.putAt(GroupSetupConstants.GROUPSETUP_STATUS, GroupSetupConstants.STATUS_ACTIVE)
		}else if(statusCode?.equals(GroupSetupConstants.STATUS_IN_ACTIVE_CODE)) {
			proposalInfo.putAt(GroupSetupConstants.GROUPSETUP_STATUS, GroupSetupConstants.STATUS_IN_ACTIVE)
		}

		if("PendingImpl".equalsIgnoreCase(actualStatus.trim())
		|| "Pending For Implementation".equalsIgnoreCase(actualStatus.trim())){
			if(existingSoldCase){
				proposalInfo.putAt("groupSetupStatusCode", GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS_CODE)
				proposalInfo.putAt(GroupSetupConstants.GROUPSETUP_STATUS, GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS)
			}
			else {
				proposalInfo.putAt("groupSetupStatusCode", GroupSetupConstants.STATUS_APPLICATION_NOT_STARTED_CODE)
				proposalInfo.putAt(GroupSetupConstants.GROUPSETUP_STATUS, GroupSetupConstants.STATUS_APPLICATION_NOT_STARTED)
			}
		}else if("PendingReview".equalsIgnoreCase(actualStatus)
		|| "Pending Review".equalsIgnoreCase(actualStatus)
		|| "Terminated".equalsIgnoreCase(actualStatus)){
			def module = existingSoldCase?.extension?.module
			proposalInfo.putAt("module", module)
		}
      proposalInfo
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
		try{
			logger.info("*****IIB response****"+data)
			if(!data?.proposals)
				isPreFtechFailed = true
			data << ['_id':metrefID]
			data << ['isPreFtechFailed':isPreFtechFailed]
			gsspRepository.create(tenantId, collectionName, data)
			logger.info("RECORD UPDATED SUCESSFULLY IN LOCAL DB WITH DRAFT DATA")
		}catch(any){
			logger.error("Error Occured in saveAsDraft--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		isPreFtechFailed
	}
	/**
	 * get agent profile details
	 * @return
	 */
	def getBrokers() {
		def entitlementUri = "/v1/tenants/${tenantId}/party/${metRefId}/getEntitlement"
		ArrayList brokerList =new ArrayList()
		try {
			def response = registeredServiceInvoker.get(sharedServiceVip, entitlementUri, new HashMap(), Map.class)
			response = response.getBody()
			logger.info("Response From Entitlement::: "+response)
			def brokerIdList = response?.brokerRelations
			brokerIdList.each() { broker->
				brokerList.add(broker?.brokerID)
			}
			logger.info("brokerList.size()::: " + brokerList.size())
		} catch(e) {
			logger.error("getEntitlement is Failed"+e.getMessage())
		}
		brokerList
	}
	/**
	 * 
	 * @param type
	 * @param className
	 * @return
	 */
	private logTransactionTime(type,className) {
		logger.info("TransactionTime of "+className+" "+type+"@"+System.currentTimeMillis())
	}
}
