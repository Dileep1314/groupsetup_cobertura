package groovy.US;


import static java.util.UUID.randomUUID

import org.slf4j.MDC;
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain;
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task;
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration


public class EmailScheduler implements Task {
	Logger logger = LoggerFactory.getLogger(EmailScheduler.class)
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	def static final SERVICE_ID = "GSES010"
	GroupSetupUtil utilObject = new GroupSetupUtil()
	
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def headersList = workFlow.getEnvPropertyFromContext('gssp.headers')
		def requestHeaders = workFlow.getRequestHeader()
		requestHeaders << [
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('smdgssp.tenantid'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('smdgssp.serviceid')]
		def spiHeadersMap = getRequiredHeaders(headersList?.tokenize(',') , requestHeaders)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def spiPrefix = '/spi/v2'
		def responseSPI = [:]
		def currentDate = getFormattedCurrentDate()
		def scheduledGSPDataList = fetchScheduledData(gsspRepository, currentDate);
		def scheduledWPList = extractScheduledWPData(scheduledGSPDataList, currentDate)
		def emailReqBody = constructScheduledEmailReqBody(scheduledWPList)
		if(emailReqBody){
			responseSPI = sendMail(registeredServiceInvoker, spiPrefix, emailReqBody, spiHeadersMap, profile)
			updateDbRecord(responseSPI, gsspRepository, entityService, emailReqBody, scheduledGSPDataList)
		}
		workFlow.addResponseBody(new EntityResult(['Status': 'Success'], true))
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${currentDate} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	private String getFormattedCurrentDate() {
		def date = new Date()
		def formattedDate = date.format("MM/dd/yyyy").toString()
		return formattedDate
	}
	
	def fetchScheduledData(def gsspRepositoty, def date)
	{
		def result
		try{
			Query query = new Query()
			query.addCriteria(Criteria.where("licensingCompensableCode.writingProducers.roleType").is("Employer").
			and("licensingCompensableCode.writingProducers.emailPreference").is("scheduled").
			and("licensingCompensableCode.writingProducers.sendDate").is(date))
			result = gsspRepositoty.findByQuery("US", GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, query)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(e){
			logger.error("Error while getting records from DB ---> "+e.getMessage())
		}
		return result
	}
	
	def extractScheduledWPData(def groupsetupDataList, def formattedDate)
	{
		def myList =[] as List
		for(def storedData:groupsetupDataList){
			def groupNumber = storedData?.extension?.groupNumber
			def rfpType = storedData?.extension?.rfpType
			def writingProducers=storedData?.licensingCompensableCode?.writingProducers
			for(def wp: writingProducers){
				if(GroupSetupConstants.ROLE_EMPLOYER.equalsIgnoreCase(wp?.roleType) && GroupSetupConstants.SCHEDULED.equalsIgnoreCase(wp?.emailPreference) 
					&& formattedDate.equals(wp?.sendDate.toString())){
					wp.putAt("groupNumber", groupNumber)
					wp.putAt("rfpTypeValue", rfpType)
					myList.add(wp)
				}
			}
		}
		return myList;
	}
		
	def constructScheduledEmailReqBody(writingProducersList)
	{
		def emailRequestPayload= [:] as Map;
		def scheduledemailsList = [] as List
		for(def wp : writingProducersList)
		{
			def emailPayload = [:] as Map
			def messageBody = [:] as Map
			def extension =[:] as Map
			def name= [:] as Map
			name.put("firstName", wp?.firstName)
			name.put("lastName", wp?.lastName)
			extension.put("name", name)
			extension.put("userPersonaTypeCode", GroupSetupConstants.EMPLOYER_PERSONA_TYPE_CODE)
			extension.put("groupNumber", wp?.groupNumber)
			extension.put("brokerName", wp?.assignedBy)
          	extension.put("SMDKey", "")
			messageBody.put("extension", extension)
			emailPayload.put("messageBody",messageBody)
			if(wp?.rfpTypeValue.equals("NEWBUSINESS"))
			emailPayload.put("templateId", GroupSetupConstants.EMPLOYER_EMAIL_TEMPLATE_ID)
			else 
			 emailPayload.put("templateId", GroupSetupConstants.EMPLOYER_EMAIL_TEMPLATE_ID_ADDPRODUCT)
			emailPayload.put("toAddress", wp?.email)
			scheduledemailsList.add(emailPayload)
		}
		if(scheduledemailsList)
			emailRequestPayload.put("emails", scheduledemailsList)
		logger.info("Final scheduled email request payload, emailRequestPayload: ${emailRequestPayload}")
		return emailRequestPayload
	}	

	private sendMail(registeredServiceInvoker, spiPrefix, requestBody, spiHeadersMap, String[] profile) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def uri
		uri = "${spiPrefix}/messages/send"
		logger.info("EmailScheduler:sendMail() :: SPI URL ->${uri}")
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		uriBuilder.queryParam("isEmailScheduled", GroupSetupConstants.TRUE)
		def serviceUri = uriBuilder.build(false).toString()
		def response = null
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("productlist.json")
			}else{
				logger.info("${SERVICE_ID} ----> EmailScheduler ==== Calling GS SendMaill API : ${serviceUri} ")
				Date start = new Date()
				HttpEntity<String> request = registeredServiceInvoker.createRequest(requestBody, spiHeadersMap)
				logger.info("request.."+ request)
				response = registeredServiceInvoker?.postViaSPI(serviceUri, request, Map.class)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> EmailScheduler ==== GS SendMaill API Response time : "+ elapseTime)
				logger.info("EmailScheduler:sendMail() spiResponseContent: ${response}")
				response = response?.getBody()
				logger.info("EmailScheduler:sendMail() Response body: ${response}")
			}
		}
		catch(e) {
			logger.error("EmailScheduler:Error retrieving from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		response
	}
	
	
	def updateDbRecord(def responseSPI, def gsspRepository, def entityService, def emailReqBody, groupsetupDataList)
	{
		try{
			def updatedGSDataList
			def failedDetails = responseSPI?.failedDetails
			if(!failedDetails.isEmpty()) {
				def successEmailsList = [] as List
				def emailObjList = emailReqBody?.emails
				for(def emailRequestObj: emailObjList)
				{
					def isSuccess = true
					for(def failedDetail:failedDetails)
					{
						if(failedDetail?.emailId.equalsIgnoreCase(emailRequestObj?.toAddress) && failedDetail?.groupNumber.equalsIgnoreCase(emailRequestObj?.messageBody?.extension?.groupNumber))
						{
							isSuccess = false
							break
						}
					}
					if(isSuccess)
						successEmailsList.add(emailRequestObj)
				}
				updatedGSDataList = updateWritingProducers(groupsetupDataList, successEmailsList)
			}else{
				updatedGSDataList = updateWritingProducers(groupsetupDataList, emailReqBody?.emails)
			}
			
			if(updatedGSDataList){
				gsspRepository.upsert("US",GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, updatedGSDataList )
				gsspRepository.upsert("US",GroupSetupConstants.PER_COLLECTION_NAME, updatedGSDataList )
			}
		}catch(any){
			logger.error("Error while updating isEmailSent flag in Employer Writing producer details ---> "+any)
			throw new GSSPException("400013")
		}
		
	}

	private updateWritingProducers(groupsetupDataList, List successEmailsList) {
		List updatedGSDataList = [] as List
		for(def storedData:groupsetupDataList) {
			def groupNumber = storedData?.extension?.groupNumber
			for(def emailObj : successEmailsList){
				def emailGroupNumber = emailObj?.messageBody?.extension?.groupNumber
				if(emailGroupNumber.equalsIgnoreCase(groupNumber)){
					def writingProducers = storedData?.licensingCompensableCode?.writingProducers
					for(def wp: writingProducers){
						if(emailObj?.toAddress.equalsIgnoreCase(wp?.email)) {
							wp.putAt("isEmailSent", "true")
							storedData?.licensingCompensableCode?.putAt("writingProducers", writingProducers)
							updatedGSDataList.add(storedData)
						}
					}
				}
			}
		}
		updatedGSDataList
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
}
