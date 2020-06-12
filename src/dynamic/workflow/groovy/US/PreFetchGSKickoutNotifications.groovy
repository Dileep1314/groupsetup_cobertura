package groovy.US

import com.metlife.gssp.repo.GSSPRepository
import com.metlife.service.entity.EntityService

import java.time.Instant
import java.util.concurrent.CompletableFuture

import org.apache.commons.jexl3.JexlException.Continue
import java.time.Instant
import java.util.concurrent.CompletableFuture

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
import com.metlife.gssp.taskflow.Task
import net.minidev.json.parser.JSONParser
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration
/**
 * This Class is used to fetch kickout response from majesco
 * @author NarsiChereddy
 *
 */
class PreFetchGSKickoutNotifications implements Task{
	Logger logger = LoggerFactory.getLogger(PreFetchGSKickoutNotifications.class)
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	GroupSetupUtil utilObject = new GroupSetupUtil()
	def static final SERVICE_ID = "PFGSKN003"
	
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		logTransactionTime("REQUEST",this.getClass())
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def spiPrefix= workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		logger.info("Path Param  value"+requestPathParamsMap)
		def tenantId = requestPathParamsMap['tenantId']
		def metrefId = requestPathParamsMap['metrefid']
		def isPreFtechFailed  = workFlow.getFact('isPreFtechFailed', Boolean.class)
		def response = [:]
		//Sec-code changes -- Begin
		if(isPreFtechFailed == null)
		{
			def secValidationList = [] as List
			secValidationList.add(metrefId)
			logger.info("PreFetchGSKickoutNotifications : secValidationList: {" + secValidationList + "}")
			ValidationUtil secValidationUtil = new ValidationUtil();
			def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
			logger.info("PreFetchGSKickoutNotifications : secValidationResponse: {" + secValidationResponse + "}")
		}
		//Sec-code changes -- End
		if(isPreFtechFailed == null || !isPreFtechFailed)
		{
			def kickOutgroupObjList = findKickOutGroups(gsspRepository, entityService, metrefId)
			def spiResponseList= getKickoutResponseFromSPI(registeredServiceInvoker, kickOutgroupObjList, spiPrefix,spiHeadersMap, profile, metrefId)
			response = prepareFinalResponse(spiResponseList)
			deleteClientList(entityService, metrefId)
			saveKickOutNoficationGroups(response, gsspRepository, tenantId, metrefId)
		}
		if(isPreFtechFailed == null)
			workFlow.addResponseBody(new EntityResult(response,true))
		else
			workFlow.addResponseBody(new EntityResult(["Response":"Success"],true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		logTransactionTime("RESPONSE",this.getClass())
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${metrefId} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	
	def findKickOutGroups(gsspRepository, entityService, metrefId)
	{
		def kickOutGroups = [] as List
		List groupNumberList = getClientsByMetrefId(entityService, metrefId)
		if(groupNumberList)
		{
			def kickOutgroupsList = getKickOutGroupsDetails(gsspRepository)
			kickOutgroupsList.each { groupObject -> 
				if(groupNumberList.contains(groupObject?.groupNumber))
					kickOutGroups.add(groupObject)
			}
		}
		logger.info("kickout groups for this metrefId : ${metrefId}, kickOutGroups : ${kickOutGroups}")
		kickOutGroups
	}
	
	def getClientsByMetrefId(entityService, metrefId)
	{
		def groupNumberList = [] as List
		try{
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, metrefId,[])
			def clientDetails = entResult.getData()
			def clientProposalList = clientDetails?.proposals
			clientProposalList.each { proposal ->
				groupNumberList.add(proposal?.groupNumber)
			}
		}catch(any){
			logger.error("Error while getting Clients by metRefiD :  ${any.getMessage()}")
//			throw new GSSPException("40001")
		}
		groupNumberList
	}
	
	def getKickOutGroupsDetails(gsspRepository)
	{
		def kickOutgroupNumberList = [:] as Map
		try{
			Query query = new Query()
			query.fields().include("groupNumber").include("extension.companyName")
			query.fields().exclude("_id")
			query.addCriteria(Criteria.where("extension.isKickOut").is(GroupSetupConstants.TRUE))
			kickOutgroupNumberList = gsspRepository.findByQuery("US", GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, query)
		}
		catch(any){
			logger.error("Error while getting kickout Groups :  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		kickOutgroupNumberList
	}
	
	def prepareFinalResponse(spiResponseList)
	{
		def finalResponse = [:] as Map
		def kickOutResult = [:] as Map
		def preFetchFailedGroups = [] as List
		def ras
		def billClass
		def noclaims
		try{
			spiResponseList.each { response ->
				def groupName = response?.groupName
				def isPreFtechFailed = response?.isPreFtechFailed
				if(isPreFtechFailed){
					preFetchFailedGroups.add(groupName)
				}else{
					def kickOutResultObj = response?.kickOutResult
					if(kickOutResultObj?.ras)
						ras = (ras) ? "${ras},${groupName}" : groupName
					if (kickOutResultObj?.billClass)
						billClass = (billClass) ? "${billClass},${groupName}" : groupName
					if (kickOutResultObj?.noclaims?.filteredProduct)
						noclaims = (noclaims) ? "${noclaims},${groupName}" : groupName
				}
			}
			if(ras || billClass ||  noclaims || preFetchFailedGroups)
			{
				kickOutResult << ["ras" : ras.toString()]
				kickOutResult << ["billClass" : billClass.toString()]
				kickOutResult << ["noclaims" : noclaims.toString()]
				finalResponse << ["kickOutResult" : kickOutResult]
				finalResponse << ["preFetchFailedGroups" : preFetchFailedGroups]
			}
		}catch(any)
		{
			logger.error("Error while preparing  Kickout Notifications final response ---> "+any)
		}
		finalResponse
	}
	
	def deleteClientList(entityService, metrefID){
		try{
			entityService?.deleteById(GroupSetupConstants.COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS, metrefID)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while deleting Kickout Notifications by metrefId ---> "+e.getMessage())
//			throw new GSSPException("40001")
		}
	}
	
	/**
	 * This method is used for storing Kickout Notifications
	 * @param data
	 * @param gsspRepository
	 * @param tenantId
	 * @return
	 */
	def saveKickOutNoficationGroups(data, gsspRepository, tenantId, metrefId) {
		try{
			logger.info("*****Final response before saving in db : "+data)
			if(data)
			{
				data << ['_id':metrefId]
				gsspRepository.create(tenantId, GroupSetupConstants.COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS, data)
				logger.info("Record created sucessfully in ${GroupSetupConstants.COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS} collection")
			}
		}catch(any){
			logger.error("Error Occured while creating kickout notification group record --->${any.getMessage()}")
//			throw new GSSPException("40001")
		}
	}
	
	/**
	 * This method is used to get specific declined group info from iib
	 * @param registeredServiceInvoker
	 * @param groupNumber
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param profile
	 * @return
	 */

	def getKickoutResponseFromSPI(registeredServiceInvoker, List kickOutgroupObjList, spiPrefix, spiHeadersMap, String[] profile, metrefId) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp())
		
		def rasDecision=true
		def noClaimsAndBillClass=true
		def finalResponse = [] as List
		Collection<CompletableFuture<Map<String, Object>>> proposalFutures = new ArrayList<>(kickOutgroupObjList.size())
		kickOutgroupObjList.each(){ groupObj ->
			def isPreFtechFailed = false
			def groupNumber = groupObj?.groupNumber
			String groupName = groupObj?.extension?.companyName
			proposalFutures.add(CompletableFuture.supplyAsync({
				->
				def endpoint = "${spiPrefix}/groupsetup/${groupNumber}/getKickOutNotifications/${rasDecision}/${noClaimsAndBillClass}"
				MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
				def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
				def serviceUri = uriBuilder.build(false).toString()
				def response
				try {
					
					if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
						response = utilObject.getTestData("KickOutNotifications.json")
					}else {
						logger.info("${SERVICE_ID} ----> ${metrefId} ----> ${groupNumber} ==== Calling KickOutNotifications API ")
						Date start = new Date()
						response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
						Date stop = new Date()
						TimeDuration elapseTime = TimeCategory.minus(stop, start)
						logger.info("${SERVICE_ID} ----> ${metrefId} ----> ${groupNumber} ==== KickOutNotifications API Response time : "+ elapseTime)
						response = response?.getBody()
						logger.info("Response From SPI, groupNumber : ${groupNumber}, groupName : ${groupName} :: ----> ${response}")
					}
				} catch (e) {
					logger.error("Error retrieving Kickout Notifications from SPI, groupNumber : ${groupNumber}, groupName : ${groupName} :: ----> ${e.message}")
					isPreFtechFailed = true
				}
				if(response && response?.kickOutResult?.result)
				{
					response << ["groupName":groupName]
					response << ["isPreFtechFailed":isPreFtechFailed]
					finalResponse.add(response)
				}else if(isPreFtechFailed)
				{
					response << ["groupName":groupName]
					response << ["isPreFtechFailed":isPreFtechFailed]
					finalResponse.add(response)
				}
			}))
			CompletableFuture.allOf(proposalFutures.toArray(new CompletableFuture<?>[proposalFutures.size()]))
					.join()
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp())
		logger.info("Final Response From SPI, finalResponse :: ----> ${finalResponse}")
		finalResponse
	}

	private logTransactionTime(type,className) {
		logger.info("PreFtech CONTINUES TransactionTime of "+className+" "+type+"@"+System.currentTimeMillis())
	}
}
