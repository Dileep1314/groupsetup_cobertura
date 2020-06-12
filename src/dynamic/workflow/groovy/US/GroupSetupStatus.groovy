package groovy.US

import com.metlife.gssp.repo.GSSPRepository
import org.springframework.data.mongodb.core.query.Criteria
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
import com.metlife.service.entity.EntityService
import java.time.Instant
import net.minidev.json.parser.JSONParser
import org.slf4j.MDC;

/**
 *  Call this method while click on landing page start button.
 * @author Durgesh Kumar Gupta
 *
 */
class GroupSetupStatus implements Task{

	Logger logger = LoggerFactory.getLogger(GroupSetupStatus.class)
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def profile = workFlow.applicationContext.environment.activeProfiles
		def tenantId = requestPathParamsMap['tenantId']
		def rfpId= requestPathParamsMap['rfpId']
		def groupNumber=requestPathParamsMap['groupNumber']
		def status
		//UAT defect - #59602 -- changes - Begin
		//Sec-code changes -- Begin
		//def secValidationList = [] as List
		//secValidationList.add(groupNumber)
		//logger.info("GroupSetupStatus : secValidationList: {" + secValidationList + "}")
		//ValidationUtil secValidationUtil = new ValidationUtil();
		//def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		//logger.info("GroupSetupStatus : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		//UAT defect - #59602 -- changes - End
		def mongoExistingPerData = checkDataAvailability(GroupSetupConstants.PER_COLLECTION_NAME, entityService, groupNumber,rfpId)
		if(( mongoExistingPerData)) {
			status= mongoExistingPerData?.extension?.groupSetUpStatus[0]
			if(!status){
				status = 'Application Not Started'
			}
		}else{
			status = 'Application Not Started'
		}

		workFlow.addResponseBody(new EntityResult(['status': status], true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	/**
	 * Getting sold proposals from mongodb.
	 * @param entityService
	 * @param brokerId
	 * @return
	 */
	def checkDataAvailability(collectionName, entityService, String id, String rfpId) {
		def result = null
		try{
			Criteria criteria = Criteria.where("groupNumber").is(id).and("rfpId").is(rfpId)
			 result=entityService.listByCriteria(collectionName, criteria)
			
			logger.info("checkDataAvailability() result: ${result}")
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(Exception e){
			logger.error("Error while getting checkDataAvailability by Id ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		result
	}
}

