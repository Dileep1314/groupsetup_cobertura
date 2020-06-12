package groovy.US

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit

import org.joda.time.format.ISODateTimeFormat
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task

/**
 * Storing banner notification and deleting 2 days old data
 * @author Vishal Patel
 *
 */
class BannerNotifcationDetails implements Task{

	Logger logger = LoggerFactory.getLogger(BannerNotifcationDetails.class)
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestHeader = workFlow.getRequestHeader()
		def metrefId = requestHeader["x-met-rfr-id"]
		def sessionId =requestHeader["x-session-id"]
		def saltId = requestHeader["x-salt-id"]
		def requestBody = workFlow.getRequestBody()
		def tenantId = requestPathParamsMap['tenantId']	
		def metrefID = requestPathParamsMap['metrefId']
		def id=metrefID +""+sessionId
		def bannerDetails=saveBannerData(tenantId,gsspRepository,id,requestBody)
		def status
		if(bannerDetails!=null) {
			status="ok"
		}else {
			status="failed"
		}
		logger.info("Response --->"+bannerDetails)
		workFlow.addResponseBody(new EntityResult(['status':status],true))
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	/**
	 * 
	 * @param gsspRepository
	 * @param groupSetUpId
	 * @return
	 */
	def saveBannerData(tenantId,gsspRepository,id,requestBody) {
		def response
		def data =[:] as Map
		def deleteBannerMsg=requestBody?.deletedBannerMessages
		logger.info("RequestBody deleteBannerMsg:: --->"+deleteBannerMsg)
		try{
			data << ['_id':id]
			data<<["deletedBannerMessages":deleteBannerMsg]
			def payloads=[] as List
			payloads.add(data)
			response=gsspRepository.upsert(tenantId, GroupSetupConstants.COLLECTION_GS_BANNER_DETAILS, payloads)
			logger.info("Sucessfully Added:: Data--->${response}")
			Criteria criteria = new Criteria();
			criteria.orOperator(Criteria.where("createdAt").lt(Instant.now().minus(2, ChronoUnit.DAYS)))
			gsspRepository.deleteByCriteria(tenantId, GroupSetupConstants.COLLECTION_GS_BANNER_DETAILS, criteria)
			logger.info("Sucessfully deleted:: --->")
		}catch(any){
			logger.error("Error Occured getData:: getData--->${any.getMessage()}")
			//throw new GSSPException("40001")
		}
		response
	}

}
