package groovy.US

import java.time.Instant

import org.slf4j.MDC
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
 * This Class is used to fetch kickout response from majesco
 * @author vishal
 *
 */
class GetGroupSetupKickout implements Task{
	Logger logger = LoggerFactory.getLogger(GetGroupSetupKickout.class)
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	def static final SERVICE_ID = "GGSKON012"
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def profile = workFlow.applicationContext.environment.activeProfiles
		GroupSetupUtil utilObject = new GroupSetupUtil()
		def spiPrefix= workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		logger.info("Path Param  value"+requestPathParamsMap)
		def tenantId = requestPathParamsMap['tenantId']
		def groupNumber = workFlow.getRequestPathParams().get("groupNumber")
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupNumber.split('_')[0])
		logger.info("GetGroupSetupKickout : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GetGroupSetupKickout : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		String [] gsId=groupNumber.toString().split("_",0)
		def responseSPI = [:]
		responseSPI= getKickoutResponse(registeredServiceInvoker, gsId[0],spiPrefix,spiHeadersMap)
		if(responseSPI) {
			workFlow.addResponseBody(new EntityResult(responseSPI, true))
		}
		else {
			workFlow.addResponseBody(new EntityResult("response":"", true))
		}
		
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${groupNumber} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
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

	def getKickoutResponse(registeredServiceInvoker,groupNumber,spiPrefix,spiHeadersMap) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def rasDecision=true
		def noClaimsAndBillClass=true
		def endpoint = "${spiPrefix}/groupsetup/${groupNumber}/getKickOutNotifications/${rasDecision}/${noClaimsAndBillClass}"
		MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('Final Request url for Get Group Setup kickout response -->'+serviceUri)
		def response
		try {
			logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling GetKickOutNotifications API: ${endpoint} ")
			Date start = new Date()
			response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> ${groupNumber} ==== GetKickOutNotifications API: ${endpoint}, Response time : "+ elapseTime)
			response = response?.getBody()
			logger.info "Sucessfully Retrieved Group Setup kickout response :"+response
		} catch (e) {
			logger.error("Error retrieving Group Set up data from SPI ${e.message}")
			//throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		response
	}

}
