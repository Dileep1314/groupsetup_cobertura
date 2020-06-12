package groovy.US

import static java.util.UUID.randomUUID
import java.time.Instant
import org.slf4j.MDC;
import org.springframework.http.HttpEntity
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
 * GroupSetupSendMail is used to  Send EmailNotification
 * @author JHANSI
 *
 */
class GroupSetupSendMail implements Task {
	Logger logger = LoggerFactory.getLogger(GroupSetupSendMail.class)
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	def static final SERVICE_ID = "GSSM010"
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def requestHeaders = workFlow.getRequestHeader()
		def headersList = workFlow.getEnvPropertyFromContext('gssp.headers')
		requestHeaders << [
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('smdgssp.tenantid'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('smdgssp.serviceid')]
		def spiHeadersMap = getRequiredHeaders(headersList?.tokenize(',') , requestHeaders)
		def requestParamsMap = workFlow.getRequestParams()
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def profile = workFlow.applicationContext.environment.activeProfiles
		def tenantId = requestPathParamsMap['tenantId']
		logger.info "tenantId......${tenantId}"
		def requestBody = workFlow.getRequestBody()
		//Sec-code changes -- Begin
		def groupSetup_Id = requestPathParamsMap[GroupSetupConstants.GROUPSETUP_ID]
		def secValidationList = [] as List
		secValidationList.add(groupSetup_Id.split('_')[0])
		logger.info("GroupSetupSendMail : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GroupSetupSendMail : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def isEmpRegistration
		if(workFlow.getFact("emailRequestBody", Map.class)!=null){
			requestBody=workFlow.getFact("emailRequestBody", Map.class)
		}
		isEmpRegistration = (requestBody["emails"]) ? GroupSetupConstants.TRUE : GroupSetupConstants.FALSE
		requestBody = updateEmailRequestBody(requestBody, groupSetup_Id, entityService, isEmpRegistration)
		def spiPrefix='/spi/v2'
		def responseSPI = [:]
		responseSPI= sendMail(registeredServiceInvoker, spiPrefix,requestBody, spiHeadersMap, groupSetup_Id, isEmpRegistration, profile)
		formateResponse(requestBody, responseSPI, isEmpRegistration)
		workFlow.addResponseBody(new EntityResult(emailResponse : responseSPI, true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${groupSetup_Id} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	private formateResponse(requestBody, responseSPI, isEmpRegistration) {
		if(isEmpRegistration!= null && isEmpRegistration.equals(GroupSetupConstants.TRUE))
		{
			try{
				def emailList = [] as List
				def emails = requestBody["emails"]
				if(emails){
					for(def email:emails){
						emailList.add(email["toAddress"])
					}
					def resBody = responseSPI.getAt("body")
					resBody.putAt("emails", emailList)
					responseSPI.putAt("body", resBody)
					responseSPI.putAt("statusCode", "OK")
					responseSPI.putAt("statusCodeValue", 200)
					logger.info("GroupSetupSendMail:formateResponse() Email response body : " + responseSPI)
				}
			}catch(any){
				logger.error("Exception occured while formating email response. " + any)
			}
		}
	}
	private sendMail(registeredServiceInvoker,spiPrefix, requestBody,spiHeadersMap, groupSetup_Id, isEmpRegistration, String[] profile) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def uri
		uri = "${spiPrefix}/messages/send"
		logger.info("GroupSetupSendMail:sendMail() :: SPI URL ->${uri}")
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		if(isEmpRegistration != null && isEmpRegistration.equals(GroupSetupConstants.TRUE)){
			uriBuilder.queryParam("isEmailScheduled", isEmpRegistration)
		}
		def serviceUri = uriBuilder.build(false).toString()
		def response = [:] as Map
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				GroupSetupUtil utilObject = new GroupSetupUtil()
				response = utilObject.getTestData("sendMailResponse.json")
			}else{
				logger.info("${SERVICE_ID} ----> ${groupSetup_Id} ==== Calling GS SendMaill API : ${serviceUri} ")
				Date start = new Date()
				HttpEntity<String> request = registeredServiceInvoker.createRequest(requestBody, spiHeadersMap)
				logger.info("request.."+ request)
				def spiResponse = registeredServiceInvoker?.postViaSPI(serviceUri, request, Map.class)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${groupSetup_Id} ==== GS SendMaill API Response time : "+ elapseTime)
				logger.info("GroupSetupSendMail:sendMail() spiResponseContent: ${spiResponse}")
				response.putAt("body", spiResponse?.getBody())
				logger.info("GroupSetupSendMail:sendMail() response body: ${response}")
			}
		}
		catch(e) {
			logger.error("GroupSetupSendMail:Error retrieving from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		response
	}

	def getRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[(X_GSSP_TRACE_ID):randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {

				spiHeaders << [(header): headerMap[header]]
			}
		}
		spiHeaders
	}

	def updateEmailRequestBody(requestBody, groupSetup_Id, entityService, isEmpRegistration) {
		def templateID = requestBody['templateId']
		if(templateID)
		{
			if(templateID.equals("14808663")) {	//licence & comp code not found
				def ccEmailId=getWritingProducerId(requestBody,groupSetup_Id,entityService)
				requestBody << ['toAddress':"CLR_institutional@metlife.com"]
				requestBody << ['ccAddress':ccEmailId]
			}
			else if(templateID.equals("14772140") || templateID.equals("17294635")) //offline begin
				requestBody << ['toAddress':"MLGroupSetup@metlifeservice.com"]
			else if(templateID.equals("15149934") || templateID.equals("17294741") ) //offline submit
				requestBody << ['toAddress':"MLGSU@metlifeservice.com"]
		}
		// Adding user maintenance id to employer registration email request
		if(isEmpRegistration)
			updateEmpRegistraionReq(requestBody, groupSetup_Id, entityService)
		requestBody
	}
	
	def updateEmpRegistraionReq(requestBody, groupSetup_Id, entityService)
	{
		try{
			def gsRecord = getGSDraftById(entityService, groupSetup_Id, GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA)
			def writingProducers = gsRecord?.licensingCompensableCode?.writingProducers
			def emails = requestBody["emails"]
			if(emails){
				def updatedEmails = [] as List
				for(def email:emails){
					def toAddress = email["toAddress"]
					def umId = getUMID(writingProducers, toAddress)
					email?.messageBody?.extension.putAt("umId", umId)
					email?.messageBody?.extension.putAt("employerId", umId)
					updatedEmails.add(email)
				}
				requestBody << ['emails' : updatedEmails]
			}
			logger.info("Updated email request body with UMID. requestBody : ${requestBody}")
		}catch(any){
			logger.error("Error while update UMID in EmpRegistraion email request : ${any.getMessage()}")
		}
	}
	
	private String getUMID(writingProducers, String toAddress) {
		def umId
		for(def wp : writingProducers){
			if(wp?.email == toAddress){
				umId = wp?.umId
				break
			}
		}
		return umId
	}
	
	/**
	 * Getting email id for cc
	 * @param requestBody
	 * @param groupSetup_Id
	 * @param entityService
	 * @return
	 */
	def getWritingProducerId(requestBody,groupSetup_Id,entityService) {
		GroupSetupUtil util=new GroupSetupUtil();
		def writingProducerId=requestBody?.writingProducerId
		def gsRecord=getGSDraftById(entityService, groupSetup_Id,GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA)
		def writingProducer=gsRecord?.licensingCompensableCode?.writingProducers
		def ccEmailId
		for(def producer : writingProducer) {
			def existingPId=producer?.writingProducerId
			def ccEmailId1=producer?.email
			if(existingPId == writingProducerId) {
				ccEmailId=producer?.email
				break
			}
		}
		logger.info("CC Email Id --->"+ccEmailId)
		ccEmailId
	}

	def getGSDraftById(entityService, groupId,collectionName) {
		def result=null
		try{
			EntityResult entResult = entityService?.get(collectionName, groupId,[])
			result=entResult.getData()
		}catch(AppDataException e){
			logger.error("Data not found ---> ${e.getMessage()}")
		}catch(any){
			logger.error("Error getting draft Group Set UP Data  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		result
	}
}
