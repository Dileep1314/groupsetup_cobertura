package groovy.US

import java.time.Instant

import org.slf4j.MDC
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
import net.minidev.json.JSONObject
import net.minidev.json.parser.JSONParser


/**
 * This Class for 'StructureHistory' Details when user clicks on structure history hyperlink
 * Structure history page.
 * @author Vijayaprakash Prathipati/Vishal
 * CR-305 Development Changes
 */

class StructureHistory implements Task{
	Logger logger = LoggerFactory.getLogger(StructureHistory.class)
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	GroupSetupUtil utilObject = new GroupSetupUtil()
	GetGroupSetupData getGSData = new GetGroupSetupData()
	GroupSetupDBOperations gspDBOpp = new GroupSetupDBOperations()
	
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def tenantId = requestPathParamsMap['tenantId']
		def groupNumber = requestPathParamsMap['groupSetUpId']
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupNumber)
		logger.info("StructureHistory : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("StructureHistory : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def spiPrefix= workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def structureHistoryDetails =  getStructureHistoryDetailsFromIIB(registeredServiceInvoker,spiPrefix,spiHeadersMap,groupNumber,profile)
		logger.info("StructureHistoryAmendment From IIB....${structureHistoryDetails}"+structureHistoryDetails)
		
		workFlow.addResponseBody(new EntityResult(['structureHistoryDetails': structureHistoryDetails], true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param rfpId
	 * @param proposalId
	 * @return
	 */
	def getStructureHistoryDetailsFromIIB(registeredServiceInvoker,spiPrefix,spiHeadersMap,groupNumber,String[] profile){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def requestData =[:] as Map
		 requestData.putAt("groupNumber",groupNumber)
		 JSONObject requestPayload = new JSONObject(requestData)
		 logger.info("StrucureLetter IIB Respose with body requestPayload--> "+requestPayload)
		def uri = "${spiPrefix}/structurehistory"
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		logger.info('Final Request url for Get structureHistory  :----->'+uri)
		def response
		def structureHistory =[] as List
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("gsStructureHistory.json")?.items
				for(def strHistory: response) {
					strHistory = strHistory?.item
					structureHistory.add(strHistory)
				}
			}
			else {
				//logger.info("${SERVICE_ID} ----> ${rfpId} ----> ${proposalId} ==== Calling structureHistory API ")
				Date start = new Date()
				def request = registeredServiceInvoker?.createRequest(requestPayload,spiHeadersMap)
				response = registeredServiceInvoker?.postViaSPI(uri, request,Map.class)
				logger.info("StrucureHistory IIB Respose with body response--> "+response)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				//logger.info("${SERVICE_ID} ----> ${rfpId} ----> ${proposalId} ==== structureHistory API Response time : "+ elapseTime)
				logger.info("StrucureHistory IIB Respose with body --> "+response)
				response = response?.getBody()?.items
				for(def strHistory: response) {
					strHistory = strHistory?.item
					structureHistory.add(strHistory)
				}
				if(structureHistory.isEmpty())
				{
					logger.error("structureHistory from SPI ${structureHistory}")
					//throw new GSSPException("400013")
				}
			}
		} catch (e) {
			logger.error("Error retrieving structureHistory from SPI ${e.message}")
			//throw new GSSPException("400013")
		}
		logger.info("IIB Respose with structureHistory Details ---> "+structureHistory)
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		structureHistory
	}
}
