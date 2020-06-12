
package groovy.US

import static java.util.UUID.randomUUID

import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant

import org.slf4j.MDC
import org.springframework.data.mongodb.core.query.Criteria

import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory

import cz.jirutka.rsql.parser.RSQLParser
import cz.jirutka.rsql.parser.ast.Node
import net.minidev.json.parser.JSONParser
import org.springframework.web.util.UriComponentsBuilder
import com.metlife.gssp.common.exception.AppDataException

/**
 * @author NarsiChereddy, Shikhar Arora
 *
 */

class GroupSetupUtil{
	Logger logger = LoggerFactory.getLogger(GroupSetupUtil)
	def  final X_GSSP_TRACE_ID = 'x-gssp-trace-id'

	GroupSetupUtil(){
	}

	/**
	 * This method is for reading mock data from local system.
	 * @param fileName
	 * @return
	 */
	public  static Map<String, Object> getTestData(String fileName) {
		JSONParser parser = new JSONParser()
		Map<String, Object> jsonObj = null
		try {
			String workingDir = System.getProperty(GroupSetupConstants.USER_DIRECTORY)
			Object obj = parser.parse(new FileReader(workingDir + GroupSetupConstants.TEST_DATA_PATH + fileName))
			jsonObj = (HashMap<String, Object>) obj
			jsonObj
		} catch (Exception e) {
			e.printStackTrace()
			throw new RuntimeException(e)
		}
	}

	/**
	 * This method for decoding string values.
	 * @param value
	 * @return
	 */
	public static String decodeString(String value) {
		if(value){
			value = URLDecoder.decode(value, GroupSetupConstants.UTF_8);
		}
		value
	}

	/**
	 * 
	 * @param headersList
	 * @param headerMap
	 * @return
	 */
	def getRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[(X_GSSP_TRACE_ID):randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): [headerMap[header]]]
			}
		}
		spiHeaders
	}
	/**
	 * Post Call for Metlife system
	 * @param headersList
	 * @param headerMap
	 * @return
	 */
	def getRequiredHeader(List headersList, Map headerMap) {
		headerMap<<[(X_GSSP_TRACE_ID):randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): headerMap[header]]
			}
		}
		spiHeaders
	}
	/**
	 *
	 * @param workFlow
	 * @param userId
	 * @param token
	 * @param method
	 * @return
	 */
	def buildSPICallHeaders(WorkflowDomain workFlow, String methodType){
		def spiHeadersMap
		def requestHeaders =  workFlow.getRequestHeader()
		requestHeaders << [
			'x-gssp-tenantid': workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_TENANTID),
			'x-spi-service-id': workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_SERVICEID)]
		def headersList=workFlow.getEnvPropertyFromContext(GroupSetupConstants.GSSP_HEADERS)
		if(methodType == GroupSetupConstants.GET_METHOD)
			spiHeadersMap = getRequiredHeaders(headersList.tokenize(",") , requestHeaders)
		else if(methodType == GroupSetupConstants.POST_METHOD)
			spiHeadersMap = getRequiredHeader(headersList.tokenize(",") , requestHeaders)
		spiHeadersMap
	}
	
	/**
	 *
	 * @param workFlow
	 * @param userId
	 * @param token
	 * @param method
	 * @return
	 */
	def buildSPICallHeader(headersList,requestHeaders,smdGsspId,smdGsspServiceId,String methodType){
		def spiHeadersMap
		//def requestHeaders =  workFlow.getRequestHeader()
		requestHeaders << [
			'x-gssp-tenantid': smdGsspId,
			'x-spi-service-id': smdGsspServiceId]
		//def headersList=workFlow.getEnvPropertyFromContext(GroupSetupConstants.GSSP_HEADERS)
		if(methodType == GroupSetupConstants.GET_METHOD)
			spiHeadersMap = getRequiredHeaders(headersList.tokenize(",") , requestHeaders)
		else if(methodType == GroupSetupConstants.POST_METHOD)
			spiHeadersMap = getRequiredHeader(headersList.tokenize(",") , requestHeaders)
		spiHeadersMap
	}

	/**
	 *
	 * @param workFlow
	 * @param userId
	 * @param token
	 * @param method
	 * @return
	 */
	def buildMetlifeSPICallHeaders(WorkflowDomain workFlow){
		def requestHeaders =  workFlow.getRequestHeader()
		requestHeaders << [
			'x-gssp-tenantid': workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_TENANTID),
			'x-spi-service-id': workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_SERVICEID),
			'X-IBM-Client-Id' : workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_CLIENT_ID)]
		def headersList=workFlow.getEnvPropertyFromContext(GroupSetupConstants.GSSP_HEADERS)
		def spiHeadersMap = getRequiredHeader(headersList.tokenize(",") , requestHeaders)
		spiHeadersMap
	}

	def buildSPICallHeader(WorkflowDomain workFlow){
		def requestHeaders =  workFlow.getRequestHeader()
		requestHeaders << [
			'x-gssp-tenantid': workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_TENANTID),
			'x-spi-service-id': workFlow?.getEnvPropertyFromContext(GroupSetupConstants.SMDGSSP_SERVICEID)]
		def headersList=workFlow.getEnvPropertyFromContext(GroupSetupConstants.GSSP_HEADERS)
		def spiHeadersMap = getRequiredHeaders(headersList.tokenize(",") , requestHeaders)
		spiHeadersMap
	}

	static def parseRequestParamsMap(requestParamsMap) {
		def rsqlParam = requestParamsMap?.get("q")
		if(rsqlParam && rsqlParam.trim().length() > 0){
			Node rootNode = new RSQLParser().parse(rsqlParam)
			def gsspRSQLVisitor = new GsspRSQLVisitor()
			def nodes = rootNode.accept(gsspRSQLVisitor)
			def rsqlMap = gsspRSQLVisitor.getMap()
			rsqlMap
		}
	}
	public Map<String, Object> getMockData(String fileName) {
		JSONParser parser = new JSONParser();
		Map<String, Object> jsonObj = null;
		try {
			String workingDir = System.getProperty("user.dir");
			Object obj = parser.parse(new FileReader(workingDir +
					"/src/test/data/"+fileName));
			jsonObj = (HashMap<String, Object>) obj;
			return jsonObj;
		} catch (Exception e) {
			//			logger.error("Error retrieving notifications from SPI ${e.message}")
		}
	}

	def getEmployerSSNFromDB(entityService,metrefid,userRoleTypeCode){
		def orgUserId,SSN
		def userKeys = getDataFromDB(entityService,GroupSetupConstants.USER_KEY,GroupSetupConstants.PARTY_KEY,metrefid)
		for(def userkey :userKeys){
			orgUserId= userkey?.organizationUserID
			break;
		}
		logger.info("IN UTIL CLASS orgUserId::: ${orgUserId}")
		if(orgUserId!=null){
			def organizationUsers=getDataFromDB(entityService,GroupSetupConstants.ORGNIZATION_USERS,GroupSetupConstants.ID,orgUserId)
			for(def organizationUser :organizationUsers){
				SSN= organizationUser?.organizationUserIdentifier
				break;
			}
		}
//		logger.info("UTIL CLASS PRINTING SSN::: ${SSN}")
		return SSN
	}
	/**
	 * 
	 * @param entityService
	 * @param collectionName
	 * @param key
	 * @param value
	 * @return
	 */
	def getDataFromDB(entityService,collectionName,key,value){
		MDC.put("UTIL_GET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def dbRecords = [] as List
		try{
			logger.info("COLLECTION NAME "+collectionName +"value"+value+"key"+key)
			Criteria criteria = Criteria.where(key).is(value)
			dbRecords =entityService.listByCriteria(collectionName, criteria)
		}catch(e){
			logger.error("Error Occured while getting data from mongo ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		MDC.put("UTIL_GET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		dbRecords
	}
	/**
	 * This Method to login request Payload
	 * @param entityService
	 * @param result
	 * @param module
	 * @return
	 */
	def saveIIBRequestPayload(entityService,payload,module,groupId){
      boolean logRequest=false
		try{
          if(logRequest){
			payload << ['_id':groupId]
			def payloads=[] as List
			payloads.add(payload)
			if((module.toString()).equalsIgnoreCase("Requote")) {
				entityService.upsert("US",module.toString().toUpperCase()+"-PAYLOAD", payloads)
			}
			else if((module.toString()).equalsIgnoreCase("MasterApp-Unsigned")){
				entityService.upsert("US",module.toString().toUpperCase()+"-PAYLOAD", payloads)
			}else if((module.toString()).equalsIgnoreCase("MasterApp-Signed")){
				entityService.upsert("US",module.toString().toUpperCase()+"-PAYLOAD", payloads)
			}else if((module.toString()).equalsIgnoreCase("MasterAppResponse")){
				entityService.upsert("US",module.toString().toUpperCase()+"-RESPONSE", payloads)
			}else if((module.toString()).equalsIgnoreCase("Preference")){
				entityService.upsert("US",module.toString().toUpperCase()+"-PAYLOAD", payloads)
			}
			else {
				entityService.upsert("US",module.toString().toUpperCase()+"-IIB-REQUEST-PAYLOAD", payloads)
			}
          }else{
            logger.info("STOPPED STORING IIB REQUEST PAYLODS IN DB, SINCE CODE IS GOING TO PROD....")
          }
		}catch(any){
			logger.error("Error Occured while creating IIB REQUEST PAYLOAD IN LOCAL DB>>>>>>>${any.getMessage()}")
		}
	}

	/**
	 * to collect API level metrics to scale-up performance
	 * @return
	 */
	public static String getDateAndTimeStamp() {
		Date now = new Date()
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
		simpleDateFormat.format(now).toString()
	}
	
	/**
	 * Converting Local Time Stamp to EST Time Stamp.
	 * @return
	 */
	public static String getESTTimeStamp() {
		Date now = new Date()
		TimeZone etTimeZone = TimeZone.getTimeZone("America/New_York")
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
		simpleDateFormat.setTimeZone(etTimeZone)
		simpleDateFormat.format(now).toString()
	}

	/**
	 * This Method is used to log the API elapsed time in mongo db
	 * @param entityService
	 * @param collectionName
	 * @param data
	 * @param id
	 * @return
	 */
	public static def savePerfMetrics(gsspRepository,collectionName,MDC,id) {
		ArrayList mdcDataList=new ArrayList()
		LinkedHashMap mdcMap=new LinkedHashMap()
		String time=System.currentTimeMillis().toString()
		String enTime=MDC.get("UI_MS_END_TIME")
		String stTime=MDC.get("UI_MS_START_TIME")
		String msURL=MDC.get("UI_MS_API_NAME")
		mdcMap.putAt("UI_MS_API_NAME", msURL)
		String timeElapsed = Duration.between(Instant.parse(stTime),Instant.parse(enTime))
		MDC.remove("UI_MS_API_NAME")
		def mdcValue=MDC.getCopyOfContextMap()
		mdcMap.putAt("_id",time)
		mdcMap.putAt("timeElapsed",timeElapsed)
		mdcMap.putAt("apiMetrics", mdcValue)
		if(id) {
			mdcDataList.add(mdcMap)
			gsspRepository.upsert("US",collectionName, mdcDataList)
		}else {
			mdcDataList.add(mdcMap)
			gsspRepository.upsert("US", collectionName, mdcDataList)
		}
	}
	/**
	 * 
	 * @param metrefID
	 * @param entityService
	 * @param requestPathParamsMap
	 * @param validationFlag
	 * @param requestHeaders
	 * @return
	 */
	def authenticateUser(metrefID,entityService,requestPathParamsMap,validationFlag,requestHeaders) {
		def secValidationList = [] as List
		secValidationList.add(metrefID)
		logger.info("GroupSetupClientsPreFetchService : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUsers(entityService,requestPathParamsMap,validationFlag,secValidationList,requestHeaders)
		logger.info("GroupSetupClientsPreFetchService : secValidationResponse: {" + secValidationResponse + "}")
	}
	
	/**
	 * 
	 * @param userType
	 * @param spiPrefix
	 * @param id
	 * @param registeredServiceInvoker
	 * @param spiHeadersMap
	 * @return
	 */
	def getClients(userType,spiPrefix,id,registeredServiceInvoker,spiHeadersMap) {
		logger.info("**********GET CLIENTS************")
		def roleType = (userType == 'employer') ? 'Group': 'Agent'
		def uri = "${spiPrefix}/role/${roleType}/persona/${id}/soldproposalslist"
		def response
		try {
			response = registeredServiceInvoker?.getViaSPI(buildURI(uri), Map.class, [:], spiHeadersMap)
			if(response){
				response = response?.getBody()
				logger.error("Response soldproposalslist API---> :  "+response)
			}
			else{
				throw new GSSPException('400013')
			}
		}
		catch(e) {
			logger.error("Exception occured while executing soldproposalslist API---> :  "+e)
			throw new GSSPException("400013")
		}
		return response
	}
	/**
	 * 
	 * @param uri
	 * @return
	 */
	def buildURI(uri) {
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		return uriBuilder.build(false).toString()
	}
	/**
	 * 
	 * @param entityService
	 * @param metRefId
	 * @return
	 */
	def deleteClientsById(entityService,metRefId){
		try{
			entityService?.deleteById(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, metRefId)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting Clients by BrokereId ---> "+e.getMessage())
		}
	}
	/**
	 * 
	 * @param entityService
	 * @param clientDetails
	 * @return
	 */
	def saveClientDetails(entityService,clients,metRefId,isPreFtechFailed) {
		logger.info("clients details before inserting ---> "+clients)
		GroupSetupDBOperations dbOps = new GroupSetupDBOperations()
		try{
			if(!clients?.proposals)	{
				isPreFtechFailed = true
			}
			clients << ['_id':metRefId]
			clients << ['isPreFtechFailed':isPreFtechFailed]
			dbOps.create(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, clients, entityService)
			logger.info "Successfully saved SPI Response into Mongo DB "
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while inserting Clients by BrokereId ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
     isPreFtechFailed
	}
	
	/**
	 * Deleting contacts while sending to UI or IIB
	 * @param subGroups
	 * @return
	 */
	def removeDeletedContantsAndLocationsFromSubGroup(subGroups)
	{
		def subGroupList = [] as List
		try{
			subGroups.each { subGroup ->
				def isDeleted = subGroup?.isDeleted
				if (isDeleted != "true")
				{
					def contactsList = [] as List
					List contacts = subGroup?.buildCaseStructure?.contacts
					contacts.each { contact ->
						def isDeletedContact = contact?.isDeleted
						if(isDeletedContact != "true")
							contactsList.add(contact)
					}
					subGroup?.buildCaseStructure.putAt("contacts", contactsList)
					subGroupList.add(subGroup)
				}
			}
		}catch(any){
			logger.error("Error while deleting duplicate contacts subgroups data. ---> "+any)
		}
		subGroupList
	}
}
