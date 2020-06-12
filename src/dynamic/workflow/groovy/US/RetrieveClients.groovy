
package groovy.US

import java.time.Instant

import org.slf4j.MDC
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus

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
 * This class is used to extract all sold RFP from core system
 * Store in MongoDB cache
 * Hydrate Data and cater to UI
 * @author Mohana
 *
 */
class RetrieveClients implements Task {

	Logger logger = LoggerFactory.getLogger(RetrieveClients.class)

	def entityService, gsspRepository, registeredServiceInvoker
	def requestPathParamsMap, requestParamsMap, spiHeadersMap,requestHeaders
	def spiPrefix, profile, tenantId, persona, metRefId
	def pageNumber, pageSize, order, orderBy, searchData, totalNoOfClients, status,searchOrCustomizeViewReq,validationFlag
	GroupSetupUtil utilObject = new GroupSetupUtil()

	@Override
	public Object execute(WorkflowDomain workFlow) {
		logTransactionTime("REQUEST",this.getClass())
		extractCommonDetails(workFlow)
		utilObject.authenticateUser(metRefId,entityService,requestPathParamsMap,validationFlag,requestHeaders)
		getPageFilterKeys()
		def response=processRequest(workFlow)
		logTransactionTime("RESPONSE",this.getClass())
		workFlow.addResponseBody(new EntityResult(response,true))
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	def extractCommonDetails(workFlow) {
		this.entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		this.gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		this.registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		this.spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		this.profile = workFlow.applicationContext.environment.activeProfiles
		this.requestPathParamsMap = workFlow.getRequestPathParams()
		this.requestParamsMap = workFlow.getRequestParams()
		this.requestHeaders =  workFlow.getRequestHeader()
		this.validationFlag = workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SECURITY_CHECK)
		this.tenantId = requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		this.persona = requestPathParamsMap[GroupSetupConstants.PERSONA]
		this.metRefId = requestPathParamsMap[GroupSetupConstants.PERSONA_ID]
		this.spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
	}
	
	/**
	 * This method returns filter criteria selected by user
	 * @return
	 */
	def getPageFilterKeys() {
		if(requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q) != null){
			requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q)?.tokenize(GroupSetupConstants.SEMI_COLON).each{ queryParam->
				def (key, value) = queryParam.tokenize( GroupSetupConstants.DOUBLE_EQUAL )
				switch(key){
					case GroupSetupConstants.LIMIT : pageSize = value
						break
					case GroupSetupConstants.OFFSET : pageNumber = value
						break
					case GroupSetupConstants.STATUS : status = value
						break
					case GroupSetupConstants.ORDER : order = value
						break
					case GroupSetupConstants.ORDER_BY : orderBy = value
						break
					case GroupSetupConstants.SEARCH_DATA : searchData = value
						break
					case GroupSetupConstants.REQUEST_FROM : searchOrCustomizeViewReq = value
						break
					default:
						logger.info "Invalid key....$key"
						break
				}
			}
		}
		validateRequestParams(pageNumber?.toInteger(), pageSize?.toInteger())
	}
	/**
	 * 
	 * @param pSize
	 * @param pNumber
	 * @return
	 */
	def validateRequestParams(pSize, pNumber) {
		if(pSize <= 0 || pNumber <= 0 ) {
			logger.info "Invalid pagesize and pageNumber entered--->pageSize value = ${pSize} and pageNumber value = ${pNumber}"
			throw new IllegalArgumentException("invalid Page Size or Page Number!");
		}
	}
	/**
	 * This Method is used for process the IIB response for broker and employer requests
	 * @param workFlow
	 * @return
	 */
	def processRequest(workFlow) {
		def response =[:]
		def clientDetails = [:]
		def clientsList = []
		def clientsPerPage=[]
		def predictiveData = []
		logger.info("persona--"+persona)
		if(isProfileLocal()) {
			clientDetails=buildStubResponse()
		}
		else {
			if(persona?.equals("employer")) {
				logger.info("Employer inside--"+persona)
				clientDetails=utilObject.getClients(persona,spiPrefix,metRefId,registeredServiceInvoker,spiHeadersMap)
				def proposalList = updateProposal(clientDetails?.items,entityService)
				response=buildResponse(proposalList)
				clientDetails=response
				if(proposalList) {
					utilObject.deleteClientsById(entityService,metRefId)
					utilObject.saveClientDetails(entityService,clientDetails,metRefId,false)
				}
				logger.info("Emplyer proposal list-->${response}")
			}
			else {	
				logger.info("Broker inside--")
				clientDetails=getData()
				if(clientDetails?.isPreFtechFailed){
					PreFetchClients prefetchService=new PreFetchClients()
					workFlow.addFacts("metRefId", metRefId)
					prefetchService.execute(workFlow)
					clientDetails=getData()
				}
				clientDetails = eliminateDuplicateClientDetails(clientDetails)
				response=buildResponse(clientDetails)
				logger.info("Broker proposal list-->${response}")
			}
			response=filterClients(response)
		}
		response
	}
	
	/**
	 * This method is perform the sorting and filter of client page on basis of selected criteria by user
	 * @param response
	 * @return
	 */
	def filterClients(response){
		def clientsList = []
		def totalNoOfClients
		def kickOutNotificationGroups
		def clientsPerPage=[]
		clientsList = response?.proposals
		if(searchData) {
			clientsList = searchClientByValue(clientsList)
		}
		if(status)
			clientsList = filterClientByStatus(clientsList)
		clientsPerPage = getPage(clientsList, pageNumber?.toInteger(), pageSize?.toInteger())
		if(order || orderBy)
			clientsList = sortClients(clientsPerPage, order, orderBy)
		if(pageNumber?.toInteger() == 1)
			kickOutNotificationGroups = getKickOutNotificationDetails()
		totalNoOfClients = clientsList?.size()
		logger.info("Total Number of Clients -->${totalNoOfClients}")
		clientsList = processResponse(clientsPerPage, totalNoOfClients,kickOutNotificationGroups)
		logger.info("Clients After Filter-->${clientsList}")
		clientsList
	}
	/**
	 * Sorting list of proposal by specific field and ascending or descending order.
	 * @param clientsList
	 * @param order
	 * @param orderBy
	 * @return
	 */
	private List sortClients(List clientsList, String order, String orderBy) {
		logger.info("Inside sortClientsList() method -->  orderBy =${orderBy}, order = ${order}")
		if(order && orderBy){
			switch(orderBy){
				case GroupSetupConstants.CLIENT_NAME :
					clientsList.sort{ a,b ->
						a.companyName <=> b.companyName
					}
					break
				case GroupSetupConstants.PRIMARY_ADDRESS :
					clientsList.sort{ a,b ->
						a.primaryAddress.city <=> b.primaryAddress.city
					}
					break
				case GroupSetupConstants.CAMEL_CASE_UNIQUE_ID :
					clientsList.sort{ a,b ->
						a.uniqueId <=> b.uniqueId
					}
					break
				case GroupSetupConstants.ELIGIBLELIVES :
					clientsList.sort{ a,b ->
						a.eligibleLives <=> b.eligibleLives
					}
					break
				case GroupSetupConstants.CLIENT_STATUS :
					clientsList.sort{ a,b ->
						a.groupSetUpStatus <=> b.groupSetUpStatus
					}
					break
				default:
					logger.info "Invalid orderBy value....${orderBy}"
					break
			}
			if(order.equalsIgnoreCase(GroupSetupConstants.ORDER_DESC)){
				Collections.reverse(clientsList)
			}
		}
		clientsList
	}
	/**
	 * Search Clients based on groupNumber and Comapnay Name
	 * @param clientsList
	 * @param searchData
	 * @return
	 */
	private List searchClientByValue(List clientsList) {
		logger.info("Inside searchClientsList() method -->  searchData =${searchData}")
		if (searchData) {
			searchData = GroupSetupUtil.decodeString(searchData)
			def matchedClients = []
			clientsList.each({ client->
				if(client?.groupNumber?.toLowerCase()?.contains(searchData?.toLowerCase()) || client?.companyName?.toLowerCase()?.contains(searchData?.toLowerCase())){
					matchedClients.add(client)
				}
			})
			clientsList = matchedClients
		}
		clientsList
	}
	/**
	 * Filter Client list based on status
	 * @param clientsList
	 * @param status
	 * @return
	 */
	def filterClientByStatus(List clientsList) {
		logger.info("Inside filterClientsListByStatus() method -->  Filtered Status =${status}")
		if(status) {
			status = GroupSetupUtil.decodeString(status)
			def statusList = status.split(GroupSetupConstants.COMMA)
			if(!statusList.contains(GroupSetupConstants.ALL)) {
				clientsList = clientsList.findAll{it -> statusList.contains(it.groupSetUpStatus)}
			}
		}
		clientsList
	}
	
	
	/**
	 * This Method is update the sold proposal given by IIB
	 * @param proposalList
	 * @return
	 */
	def updateProposal(proposalList,entityServiceObj){
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
			def existingSoldCase = checkForInProgressCases(entityServiceObj,groupNumber,rfpId,uniqueId)
			proposalInfo=setCodes(proposalInfo,statusCode,actualStatus,existingSoldCase)
			proposal.putAt("proposal", proposalInfo)
			item.putAt("item", proposal)
			updatedList.add(item)
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
	}
	/**
	 * Getting GroupSetup data from permanent Mongo db
	 * @param groupId
	 * @param rfpId
	 * @param soldCaseNumber
	 * @return
	 */
	def checkForInProgressCases(entityServiceObj,groupId, rfpId, soldCaseNumber) {
		def result
		def groupSetUpId=groupId+"_"+rfpId+"_"+soldCaseNumber
		try{
			EntityResult results = entityServiceObj?.get(GroupSetupConstants.PER_COLLECTION_NAME, groupSetUpId,[])
			result=results.getData()
			logger.error("checkForInProgressCases  ---> "+result)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while retrieve Clients by ID ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		result
	}
	boolean isProfileLocal() {
		return profile[0]?.equals(GroupSetupConstants.LOCAL)
	}

	def buildStubResponse() {
		def response =[:]
		def stub = utilObject.getTestData("getSoldProposals.json")?.proposals
		response << ["_id":metRefId]
		response << ["proposals":stub]
		return response
	}
	/**
	 * Building the response structure
	 * @param proposalList
	 * @param clientDetails
	 * @return
	 */
	def buildResponse(proposalList) {
		def response =[:]
		response << ["_id":metRefId]
		response << ["proposals":proposalList]
		response
	}
	
	/**
	 * This method is used to delete the duplicates clients
	 * @param clientDetails
	 * @return
	 */
	def eliminateDuplicateClientDetails(clientDetails){
		clientDetails=getData()
		clientDetails=clientDetails?.proposals
		logger.info("GetSoldProposals-getDuplicateClientDetails-orgList==>: " + clientDetails)
		def uniqueBrokers = clientDetails?.unique{orglist, dplist ->
			orglist?.rfpId <=> dplist?.rfpId
			orglist?.groupNumber <=> dplist?.groupNumber
			orglist?.uniqueId <=> dplist?.uniqueId
		}
		logger.info("==GetSoldProposals-getDuplicateClientDetails-uniqueBrokers==>: " + uniqueBrokers)
		uniqueBrokers
	}
	/**
	 * Returning user selected page data .
	 * @param clientsList
	 * @param pageNumber
	 * @param pageSize
	 * @return
	 */
	def getPage(clientsList, pageNumber, pageSize) {
		def fromIndex = (pageNumber - 1) * pageSize;
		if(clientsList == null || clientsList.size() < fromIndex){
			return Collections.emptyList();
		}
		clientsList?.subList(fromIndex, Math.min(fromIndex + pageSize, clientsList.size()))
	}
	/**
	 * This method gives the kick out details of all group existing in db
	 * @param gsspRepository
	 * @param metRefId
	 * @return
	 */
	def getKickOutNotificationDetails() {
		def result
		try{
			Query query = new Query()
			query.fields().exclude("_id").exclude("_class").exclude("updatedAt").exclude("createdAt")
			query.addCriteria(Criteria.where("_id").is(metRefId))
			result = gsspRepository.findByQuery("US", GroupSetupConstants.COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS, query)
			result = result[0]?.kickOutResult
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Kickout Notifications by metrefId ---> "+e.getMessage())
			//			throw new GSSPException("40001")
		}
		result
	}
	/**
	 * Preparing final response which will send to UI.
	 * @param clientsList
	 * @param persona
	 * @param personaId
	 * @param totalNoOfClients
	 * @param pageSize
	 * @param pageNumber
	 * @return
	 */
	def processResponse(clientsList, totalNoOfClients, kickOutNotificationGroups) {
		def response = [:]
		response << ["persona":persona]
		response << ["personaId":metRefId]
		response << ["count":totalNoOfClients]
		response << ["limit":pageSize]
		response << ["offset":pageNumber]
		response << ["clients":clientsList]
		response << ["kickOutResult":kickOutNotificationGroups]
		response
	}
	def isPreFtechFailed() {
		return getData()?.isPreFtechFailed
	}
	def getData() {
		EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, metRefId,[])
		return entResult.getData()
	}
	private logTransactionTime(type,className) {
		logger.info("TransactionTime of "+className+" "+type+"@"+System.currentTimeMillis())
	}
}
