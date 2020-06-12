package groovy.US

import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

/**
 * This class  updating the declinedProducts into Permanent collection[GSAssignAccess] in Mongo DB.  
 * @author Jhansi
 *
 */
class DeclinedProducts implements Task{
	
	Logger logger = LoggerFactory.getLogger(DeclinedProducts.class)
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		Map<String, Object> requestPathParamsMap = workFlow.getRequestPathParams()
		Map<String, Object> requestBody = workFlow.getRequestBody()
		logger.info("DeclinedProducts requestBody::: "+requestBody)
		def declinedProducts=requestBody['declinedProducts']
		def rfpId= requestBody['rfpId']
		def proposalId=requestBody['uniqueId']
		def groupNumber=requestBody['groupNumber']
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupNumber)
		logger.info("DeclinedProducts : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("DeclinedProducts : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def cacheId="${groupNumber}_${rfpId}_${proposalId}"
		def st=saveAsDraft(entityService,cacheId,declinedProducts)
		workFlow.addResponseBody(new EntityResult(['Status': 'Success'], true))
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	def saveAsDraft(entityService, String groupSetUpId, declinedProducts) {
		try{
			def collectionName=GroupSetupConstants.PER_COLLECTION_NAME
			def groupDataRes = getById GroupSetupConstants.PER_COLLECTION_NAME, entityService, groupSetUpId
			groupDataRes << [("declinedProducts"):declinedProducts]
			entityService.updateById(collectionName, groupSetUpId, groupDataRes)
		}catch(any){
			logger.error("Error Occured in DeclinedProducts :: saveAsDraft--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		
		GroupSetupConstants.OK
	}
	def  getById(collectionName, entityService, Id) {
		EntityResult entResult
		try{
			entResult = entityService?.get(collectionName, Id,[])
		}catch(e){
			logger.error("Error Occured while getting data from mongo :: ${e.getMessage()}")
			throw new GSSPException("40001")
		}
		entResult?.getData()
	}

}
