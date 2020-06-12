/**
 * 
 */
package groovy.US

import java.time.Instant

import org.slf4j.MDC
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus
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

/**
 * This class is used get list of Sold Proposal details from SPI/MAJESCO by persona & personaId and display in dashboard.
 * @author NarsiChereddy
 *
 */
class GetSoldProposals implements Task {

	Logger logger = LoggerFactory.getLogger(GetSoldProposals.class)
	GroupSetupUtil utilObject = new GroupSetupUtil()
	GroupSetupDBOperations groupSetupDBOperations = new GroupSetupDBOperations()
	def static final SERVICE_ID = "GSPS001"
	

	/**
	 * This method is the start & ending point for this service.
	 */
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		logger.info("GetSoldProposals : execute(): Start")
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestParamsMap = workFlow.getRequestParams()
		def tenantId = requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		def persona = requestPathParamsMap[GroupSetupConstants.PERSONA]
		def metRefId = requestPathParamsMap[GroupSetupConstants.PERSONA_ID]
		def requestHeader = workFlow.getRequestHeader()
		def metrefId = requestHeader["x-met-rfr-id"]
		def sessionId =requestHeader["x-session-id"]
		def id=metrefId+""+sessionId
		def spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(metRefId)
		logger.info("GetSoldProposals : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GetSoldProposals : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		GroupSetupClientsPreFetchService prefetchService=new GroupSetupClientsPreFetchService()
		def clientDetails = [:]
		def clientsList = []
		def clientsPerPage=[]
		def kickOutNotificationGroups
		def deletedBannerMessageRes
		def pageNumber
		def pageSize
		def order
		def orderBy
		def searchData
		def totalNoOfClients
		def status
		def searchOrCustomizeViewReq
		def filterByRFP
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
					case GroupSetupConstants.FILTER_BY_RFPID : filterByRFP = value
						break
					default:
						logger.info "Invalid key....$key"
						break
				}
			}
		}
		validateRequestParams(pageNumber?.toInteger(), pageSize?.toInteger())
		clientDetails = getClientsListBypersonaId(clientDetails, entityService, profile, workFlow, prefetchService,metRefId,persona,spiPrefix,registeredServiceInvoker,spiHeadersMap,gsspRepository,tenantId)
		clientsList = clientDetails?.proposals
		clientsList = searchClientsList(clientsList, searchData, filterByRFP)
		clientsList = filterClientsList(clientsList, status)
		totalNoOfClients = clientsList?.size()
		clientsPerPage = getPage(clientsList, pageNumber?.toInteger(), pageSize?.toInteger())
		clientsList = sortClientsList(clientsPerPage, order, orderBy)
		if(pageNumber?.toInteger() == 1) {
			kickOutNotificationGroups = getKickOutNotificationDetails(gsspRepository,metRefId,GroupSetupConstants.COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS,tenantId)
			deletedBannerMessageRes= getKickOutNotificationDetails(gsspRepository,id,GroupSetupConstants.COLLECTION_GS_BANNER_DETAILS,tenantId)
			deletedBannerMessageRes=deletedBannerMessageRes?.deletedBannerMessages
		}
		logger.info("NOW TOTAL NUMBER OF CLIENTS::: "+totalNoOfClients)
		clientsList = processResponse(clientsPerPage, persona, metRefId, totalNoOfClients, pageSize, pageNumber, kickOutNotificationGroups,deletedBannerMessageRes)
		 workFlow.addResponseBody(new EntityResult(clientsList,true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		logger.info("profile[0]>>>> "+profile[0])
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		logger.info("GetSoldProposals : execute(): End")
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${metRefId} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	/**
	 * This method return sold proposal from mongodb if available in mongodb else it will call SPI request and 
	 * returned SPI response will be saved in mongodb then same data will be return to UI.
	 * @param clientDetails
	 * @param entityService
	 * @param persona
	 * @param personaId
	 * @param spiPrefix
	 * @param registeredServiceInvoker
	 * @param spiHeadersMap
	 * @param profile
	 * @return
	 */
	def getClientsListBypersonaId(clientDetails, entityService, String[] profile, workFlow, prefetchService, metRefId,persona,spiPrefix,registeredServiceInvoker,spiHeadersMap,gsspRepository,tenantId) {
		logger.info("GetSoldProposals :: getClientsListBypersonaId() :: Start")
		def response =[:]
		if(profile[0]?.equals(GroupSetupConstants.LOCAL)) {
			clientDetails = utilObject.getTestData("getSoldProposals.json")?.proposals
			response << ["_id":metRefId]
			response << ["proposals":clientDetails]
		}else {
			if(persona.equals("employer")){
				clientDetails = getGSClientsList(spiPrefix, registeredServiceInvoker, spiHeadersMap, persona, metRefId)
				//clientDetails= utilObject.getTestData("getSoldProposals.json")
				def proposalList = clientDetails?.items
				proposalList = updatedProposalList(entityService, proposalList)
				def clientList = formatSPIResponse(proposalList)
				def groupNumberList= getRegisteredEmpGroupNumber(gsspRepository)
				clientList=getEmployerStatus(groupNumberList,clientList)
				response << ["_id":metRefId]
				response << ["proposals":clientList]
				clientDetails = response
				if(clientList) {
					deleteClientList(entityService,metRefId)
					saveSPIResponseInMongoDB(entityService, clientDetails)
				}
			}
			else {
				EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, metRefId,[])
				def prefetch = entResult.getData()?.isPreFtechFailed
				if(prefetch){
					workFlow.addFacts("metRefId", metRefId)
					prefetchService.execute(workFlow)
					entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, metRefId,[])
				}
				clientDetails = entResult.getData()
				def clientProposalList = clientDetails?.proposals
				def groupNumberList = getRegisteredEmpGroupNumber(gsspRepository)
				clientDetails = getEmployerStatus(groupNumberList,clientProposalList)
				clientDetails = eliminateDuplicateClientDetails(clientDetails)
				response << ["_id":metRefId]
				response << ["proposals":clientDetails]
			}
		}
		logger.info("GetSoldProposals :: getClientsListBypersonaId() :: End")
		response
	}
	
	def eliminateDuplicateClientDetails(clientDetails){
		logger.info("GetSoldProposals :: eliminateDuplicateClientDetails() :: Start")
		def uniqueBrokers = clientDetails?.unique{orglist, dplist ->
			orglist?.rfpId <=> dplist?.rfpId
			orglist?.groupNumber <=> dplist?.groupNumber
			orglist?.uniqueId <=> dplist?.uniqueId
		}
		logger.info("GetSoldProposals :: eliminateDuplicateClientDetails() :: End")
		uniqueBrokers
	}
	
	def deleteClientList(entityService,personaId){
		logger.info("GetSoldProposals :: deleteClientList() :: Start")
		MDC.put("DELET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try{
			entityService?.deleteById(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS,personaId)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Clients by BrokereId ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		logger.info("GetSoldProposals :: deleteClientList() :: End")
		MDC.put("DELET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}

	def updatedProposalList(entityService, proposalList){
		logger.info("GetSoldProposals :: updatedProposalList() :: Start")
		def updatedList = [] as List
		for(def client: proposalList){
			def proposal = [:] as Map
			def item = [:] as Map
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
			proposal.putAt("proposal", proposalInfo)
			item.putAt("item", proposal)
			updatedList.add(item)
		}
		logger.info("GetSoldProposals :: updatedProposalList() :: End")
		updatedList
	}
	/**
	 * Adding property Status names based on status code
	 */
	def formatSPIResponse(clientDetails) {
		logger.info("GetSoldProposals :: formatSPIResponse() :: Start")
		def clientsList = []
		clientDetails.each{client ->
			def proposal = client?.item?.proposal
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
		logger.info("GetSoldProposals :: formatSPIResponse() :: End")
		clientsList
	}


	def checkForInProgressCases(entityService, groupId, rfpId, soldCaseNumber) {
		logger.info("GetSoldProposals :: checkForInProgressCases() :: Start")
		def result
		def groupSetUpId=groupId+"_"+rfpId+"_"+soldCaseNumber
		try{
			EntityResult results = entityService?.get(GroupSetupConstants.PER_COLLECTION_NAME, groupSetUpId,[])
			result=results.getData()
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Clients by BrokereId ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		logger.info("GetSoldProposals :: checkForInProgressCases() :: End")
		return result
	}

	/**
	 * Calling SPI Get request by passing persona & personaId and will return list of sold proposals.
	 * @param spiPrefix
	 * @param registeredServiceInvoker
	 * @param spiHeadersMap
	 * @param persona
	 * @param personaId
	 * @return
	 */
	private getGSClientsList(spiPrefix, registeredServiceInvoker, spiHeadersMap, persona, personaId) {
		logger.info("GetSoldProposals :: getGSClientsList() :: Start")
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def roleType = (persona == 'employer') ? 'Group': 'Agent'
		def uri = "${spiPrefix}/role/${roleType}/persona/${personaId}/soldproposalslist"
		logger.info("getGSClientsList() :: SPI URL -> ${uri}")
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		def serviceUri = uriBuilder.build(false).toString()
		def response
		try {
			logger.info("${SERVICE_ID} ----> ${persona} ----> ${personaId} ==== Calling Soldproposalslist API ")
			Date start = new Date()
			response = registeredServiceInvoker?.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> ${persona} ----> ${personaId} ==== Soldproposalslist API Response time : "+ elapseTime)
			if(response){
				response = response?.getBody()
			}
			else{
				throw new GSSPException('400013')
			}
		}
		catch(e) {
			logger.error("Exception occured while executing SPI Request ---> :  "+e)
			throw new GSSPException("400013")
		}
		logger.info("Response From SPI :: ----> $response")
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		logger.info("GetSoldProposals :: getGSClientsList() :: End")
		response
	}


	/**
	 * Saving SPI response in mongodb for caching.
	 * @param entityService
	 * @param clientsList
	 * @return
	 */
	def saveSPIResponseInMongoDB(entityService, clientsList) {
		logger.info("GetSoldProposals :: saveSPIResponseInMongoDB() :: Start")
		MDC.put("CREATE_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		groupSetupDBOperations.create(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, clientsList, entityService)
		logger.info "Successfully saved SPI Response into Mongo DB "
		MDC.put("CREATE_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		logger.info("GetSoldProposals :: saveSPIResponseInMongoDB() :: End")
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
	def processResponse(clientsList, persona, personaId, totalNoOfClients, pageSize, pageNumber, kickOutNotificationGroups,deletedBannerMessages) {
		logger.info("GetSoldProposals :: processResponse() :: Start")
		
		def response = [:]
		response << ["persona":persona]
		response << ["personaId":personaId]
		response << ["count":totalNoOfClients]
		response << ["limit":pageSize]
		response << ["offset":pageNumber]
		response << ["clients":clientsList]
		response << ["kickOutResult":kickOutNotificationGroups]
		response << ["deletedBannerMessages":deletedBannerMessages]
		logger.info("GetSoldProposals :: processResponse() :: End")
		response
	}

	/**
	 * Returning user selected page data . 
	 * @param clientsList
	 * @param pageNumber
	 * @param pageSize
	 * @return
	 */
	def getPage(clientsList, pageNumber, pageSize) {
		logger.info("GetSoldProposals :: getPage() :: Start")
		def fromIndex = (pageNumber - 1) * pageSize;
		if(clientsList == null || clientsList.size() < fromIndex){
			return Collections.emptyList();
		}
		logger.info("GetSoldProposals :: getPage() :: End")
		clientsList?.subList(fromIndex, Math.min(fromIndex + pageSize, clientsList.size()))
	}

	/**
	 * Filtering Proposals by user selected status.
	 * @param clientsList
	 * @param status
	 * @return
	 */
	def filterClientsList(List clientsList, status) {
		logger.info("GetSoldProposals :: filterClientsList() :: Start")
		logger.info("Inside filterClientsListByStatus() method -->  Filtered Status =${status}")
		if(status) {
			status = GroupSetupUtil.decodeString(status)
			def statusList = status.split(GroupSetupConstants.COMMA)
			if(!statusList.contains(GroupSetupConstants.ALL)) {
				clientsList = clientsList.findAll{it -> statusList.contains(it.groupSetUpStatus)}
			}
		}
		logger.info("GetSoldProposals :: filterClientsList() :: End")
		clientsList
	}

	/**
	 * Searching for proposals by ClientId or ClientName.
	 * @param clientsList
	 * @param searchData
	 * @return
	 */
	private List searchClientsList(List clientsList, searchData, filterByRFP) {
		logger.info("GetSoldProposals :: searchClientsList() :: Start")
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
		
		if (filterByRFP) {
//			searchData = GroupSetupUtil.decodeString(searchData)
			def matchedClients = []
			clientsList.each({ client->
				if(client?.rfpId.equalsIgnoreCase(filterByRFP)){
					matchedClients.add(client)
				}
			})
			clientsList = matchedClients
		}
		logger.info("GetSoldProposals :: searchClientsList() :: End")
		clientsList
	}

	/**
	 * Sorting list of proposal by specific field and ascending or descending order.
	 * @param clientsList
	 * @param order
	 * @param orderBy
	 * @return
	 */
	private List sortClientsList(List clientsList, String order, String orderBy) {
		logger.info("GetSoldProposals :: sortClientsList() :: Start")
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
		logger.info("GetSoldProposals :: sortClientsList() :: End")
		clientsList
	}

	/**
	 * Method to add empRegistered to identify each clients as employer or not
	 * @param groupNumberList 
	 * @param clientsList
	 * @return
	 */
	def getEmployerStatus(groupNumberList,clientsList){
		logger.info("GetSoldProposals :: getEmployerStatus() :: Start")
		HashSet groupNumberSet=new HashSet()
		for(def groupNumberEmp in groupNumberList) {
			groupNumberSet.add(groupNumberEmp?.groupNumber)
		}
		for(def clientsValue in clientsList){
			def groupNumber=clientsValue?.groupNumber
			if(groupNumberSet.contains(groupNumber)) {
				clientsValue.putAt("empRegistered",true)
			}else {
				clientsValue.putAt("empRegistered",false)
			}
		}
		logger.info("GetSoldProposals :: getEmployerStatus() :: End")
		clientsList
	}

	/**
	 * Method to fetch all groupnumber from User_Key collection
	 * @param gsspRepository
	 * @return
	 */
	def getRegisteredEmpGroupNumber(gsspRepository) {
		logger.info("GetSoldProposals :: getRegisteredEmpGroupNumber() :: Start")
		MDC.put("FIND_BY_USER_KEY_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def  groupNumberList=[] as List
		try{
			Query query = new Query()
			query.fields().include("groupNumber")
			query.fields().exclude("_id")
			query.addCriteria(Criteria.where("groupNumber").ne(""))
			query.addCriteria(Criteria.where("userRoleTypeCode").is("30002"))
			groupNumberList=gsspRepository.findByQuery("US", GroupSetupConstants.USER_KEY,query)
		}
		catch(any){
			logger.error("Error getting getContentByModule  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		MDC.put("FIND_BY_USER_KEY_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		logger.info("GetSoldProposals :: getRegisteredEmpGroupNumber() :: End")
		groupNumberList
	}

	/**
	 * validation for pagesize and pagenumber for pagination.
	 * @param pageSize
	 * @param pageNumber
	 * @return
	 */
	private validateRequestParams(pageSize, pageNumber) {
		logger.info("GetSoldProposals :: validateRequestParams() :: Start")
		if(pageNumber <= 0 || pageSize <= 0 ) {
			logger.info "Invalid pagesize and pageNumber entered--->pageSize value = ${pageSize} and pageNumber value = ${pageNumber}"
			throw new IllegalArgumentException("invalid Page Size or Page Number!");
		}
		logger.info("GetSoldProposals :: validateRequestParams() :: End")
	}
	
	def getKickOutNotificationDetails(gsspRepository, metRefId,collection,tenantId)
	{
		logger.info("GetSoldProposals :: getKickOutNotificationDetails() :: Start")
		def result
		try{
			Query query = new Query()
			query.fields().exclude("_id").exclude("_class").exclude("updatedAt").exclude("createdAt")
			query.addCriteria(Criteria.where("_id").is(metRefId))
			result = gsspRepository.findByQuery(tenantId, collection, query)
			if(collection.equals(GroupSetupConstants.COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS))
			   result = result[0]?.kickOutResult
			 else if(collection.equals(GroupSetupConstants.COLLECTION_GS_BANNER_DETAILS)){
				 result = result[0]
			 }
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Kickout Notifications by metrefId ---> "+e.getMessage())
//			throw new GSSPException("40001")
		}
		logger.info("GetSoldProposals :: getKickOutNotificationDetails() :: End")
		return result
	}
	
}
