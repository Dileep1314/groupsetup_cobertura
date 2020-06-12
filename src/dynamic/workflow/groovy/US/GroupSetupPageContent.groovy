package groovy.US

import com.metlife.gssp.repo.GSSPRepository

import java.time.Instant

import org.slf4j.MDC;
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory

import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

/**
 * This class is used for Get static page content from db based on module
 * @author vishal
 *
 */
class GroupSetupPageContent implements Task{
	Logger logger = LoggerFactory.getLogger(GroupSetupPageContent)

	@Override
	public Object execute(WorkflowDomain workFlow) {
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def module = workFlow.getRequestPathParams().get("module")
		def profile = workFlow.applicationContext.environment.activeProfiles
		def content= getContentByModule(entityService, module)
		workFlow.addResponseBody(new EntityResult(content,true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	def getContentByModule(entityService, module){
		def response=[:]
		def result=null
		try{
			logger.info  "getContentByModule() :: collection Name :: entityService:${entityService}, groupId:${module}"
			EntityResult entResultforMasterKey = entityService?.get(GroupSetupConstants.COLLECTION_PAGE_CONTENT_DATA, "pageContent",[])
			def pageContent= entResultforMasterKey.getData()
			response<<["common" : pageContent?.common]
			response<<["${module}" : pageContent?."${module}"]
		}
		catch(any){
			logger.error("Error getting getContentByModule  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		response
	}
}
