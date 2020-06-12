package groovy.US

import static java.util.UUID.randomUUID

import java.time.Instant

import org.slf4j.MDC;
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration

/**
 * @author NarsiChereddy
 *
 */
class RetrieveUploadedDocuments implements Task {
	Logger logger = LoggerFactory.getLogger(RetrieveUploadedDocuments)
	def static final SERVICE_ID = "RUPDS013"

	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def requestHeaders = [:]
		def response = [:]
		def docDetails = [] as Set
		def groupNumber, rfPId, personaCode, getUPLDocResponse, getCAAUPLDocResponse
		def gsspRepository = workFlow.getBeanFromContext("GSSPRepository", GSSPRepository)
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestParams = workFlow.getRequestParams()
		def tenantId = requestPathParamsMap['tenantId']
		def groupSetUpId = requestPathParamsMap['groupSetUpId']
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetUpId.split('_')[0])
		logger.info("RetrieveUploadedDocuments : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("RetrieveUploadedDocuments : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def requestParamsMap = GroupSetupUtil.parseRequestParamsMap(requestParams)
		if(requestParamsMap)
			personaCode = (requestParamsMap?.personaId) ? requestParamsMap?.personaId : ""
		groupNumber = groupSetUpId.split('_')[0]
		rfPId = groupSetUpId.split('_')[1]
		def profile = workFlow.applicationContext.environment.activeProfiles
		def spiPrefix = workFlow.getEnvPropertyFromContext("spi.prefix")
		def headersList =  workFlow.getEnvPropertyFromContext('gssp.headers')
		requestHeaders << ['x-gssp-tenantid': workFlow.getEnvPropertyFromContext('smdgssp.tenantid')]
		logger.info('HeadersList :: '+headersList +"spiPrefix "+spiPrefix +"groupSetUpId :"+groupSetUpId +"requestHeaders "+requestHeaders)
		def spiHeadersMap = getRequiredHeaders(headersList.tokenize(',') , requestHeaders)
		spiHeadersMap << ['x-spi-service-id' : [workFlow.getEnvPropertyFromContext('smdgssp.serviceid')]]
		if(GroupSetupConstants.BROKER_PERSONA_TYPE_CODE.equalsIgnoreCase(personaCode) || GroupSetupConstants.TPA_PERSONA_TYPE_CODE.equalsIgnoreCase(personaCode) || GroupSetupConstants.GA_PERSONA_TYPE_CODE.equalsIgnoreCase(personaCode))
			getCAAUPLDocResponse = getDocsFromSPI(registeredServiceInvoker, rfPId, spiPrefix, spiHeadersMap, profile)
		getUPLDocResponse = getDocsFromSPI(registeredServiceInvoker, groupNumber, spiPrefix, spiHeadersMap, profile)
		//Defect fix for 65825
		//getMGIDocResponse = getDocsFromSPI(registeredServiceInvoker, groupNumber, spiPrefix, spiHeadersMap, profile, 'MGI')
		logger.info("RetrieveUploadedDocuments getUPLDocResponse" + getUPLDocResponse)
		if(getUPLDocResponse)
		{
			getUPLDocResponse.each{docItem ->
				if(docItem?.item?.status == 'success') 
					docDetails.add(docItem?.item)
			}
			if(getCAAUPLDocResponse){
			getCAAUPLDocResponse.each{docItem ->
			 if(docItem?.item?.status == 'success' && (docItem?.item?.docType == 'Commission Agreement Acknowledgement'))
				 docDetails.add(docItem?.item)
			}}
			//Defect fix for 65825
			/*getMGIDocResponse.each{docItem ->
				if(docItem?.item?.status == 'success')
					docDetails.add(docItem?.item)
			}*/
			logger.info("Getting list of uploaded documents details groupNumber : ${groupNumber}, docDetailsRes : ${docDetails}")
		}
		response << ["uploadedDocuments":docDetails]
		workFlow.addResponseBody(new EntityResult(response, true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${groupSetUpId} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	protected getDocsFromSPI(registeredServiceInvoker, groupNumber, spiPrefix, spiHeadersMap, profile)
	
	{
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def response
		def errorMessage
		def getDocsSPIURI = "${spiPrefix}users/${groupNumber}/retrieveUploadedDocuments"
		MDC.put(GroupSetupConstants.SUB_API_NAME, getDocsSPIURI);
		logger.info("getDocsFromSPI Calling the SPI to get Docs for URL: $getDocsSPIURI")
		def uriBuilder = UriComponentsBuilder.fromPath(getDocsSPIURI)
		uriBuilder.queryParam("personaType", GroupSetupConstants.EMPLOYER_PERSONA_TYPE_CODE)
		uriBuilder.queryParam("marketIndicator", 'SM')
		uriBuilder.queryParam("documentSourceIndicator", 'UPL')
		def serviceUri = uriBuilder.build(false).toString()
		try {
			logger.info('SPI header Map  :: '+spiHeadersMap+"serviceUri"+serviceUri)
			logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling RetrieveUploadedDocuments API: ${serviceUri} ")
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				return ""
			}
			Date start = new Date()
			response = registeredServiceInvoker.getViaSPI(serviceUri,Map.class,[:],spiHeadersMap)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Soldproposalslist API: ${serviceUri}, Response time : "+ elapseTime)
			logger.info("Retrieving Uploaded documents :  spiResponseContent: ${response}")
			if(response) {
				response = response.getBody()?.items
			}else {
				response = ""
			}
		} catch (Exception e) {
			logger.error "Got exception while calling SPI to getting uploaded Docs data : "+e.printStackTrace()
//			throw new GSSPException("3006")
		}
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		response
	}

	static def getRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[X_GSSP_TRANSACTION_ID:randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): [headerMap[header]]]
			}
		}
		spiHeaders
	}

	def sortByDate(getDocResponse){
		getDocResponse.items.sort{x,y->
			def objectOne=new Date().parse("yy-MM-dd hh:mm:ss",x?.item?.docCreationDate)
			def objecttwo=new Date().parse("yy-MM-dd hh:mm:ss",y?.item?.docCreationDate)
			objectOne<=>objecttwo
		}.reverse()
		return getDocResponse
	}
}
