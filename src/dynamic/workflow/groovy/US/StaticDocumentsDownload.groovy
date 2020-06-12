package groovy.US

import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

/**
 * @author NarsiChereddy
 *
 */
class StaticDocumentsDownload implements Task{
	Logger logger = LoggerFactory.getLogger(StaticDocumentsDownload.class)


	@Override
	public Object execute(WorkflowDomain workFlow) {
		def file, bytesBase64, fileData, documentName, dbResponse
		def responseArray = [] as Set
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestParamsMap = workFlow.getRequestParams()
//		requestParamsMap = GroupSetupUtil.parseRequestParamsMap(requestParamsMap)
//		if(requestParamsMap)
//			documentName = (requestParamsMap?.documentName) ? requestParamsMap?.documentName : ""
		if(requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q) != null){
			requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q)?.tokenize(GroupSetupConstants.SEMI_COLON).each{ queryParam->
				def (key, value) = queryParam.tokenize( GroupSetupConstants.DOUBLE_EQUAL )
				switch(key){
					case 'documentName' : documentName = value
						break
					default:
						logger.info("Invalid key::: "+key)
						break
				}
			}
		}
		
		logger.info("documentName::: "+documentName)
		if(documentName)
		{
			dbResponse = getDocumentsFromMongoDB(entityService, documentName)
			if(dbResponse)
			{
				bytesBase64 = dbResponse?.content
				fileData = ['content' : bytesBase64]
				fileData.encodingType = "BASE64"
				fileData.contentLength = bytesBase64?.length()
				fileData.formatCode = "PDF"
				fileData.name = dbResponse?.name
				responseArray.add(fileData)
			}
			logger.info("responseArray size::: "+responseArray.size())
		}
		workFlow.addResponseBody(new EntityResult([Details:responseArray], true))
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	
	/**
	 * Getting static documents from mongodb.
	 * @param entityService
	 * @param documentName
	 * @return
	 */
	def getDocumentsFromMongoDB(entityService, documentName) {
		def result=null
		try{
			logger.info("documentName::: "+documentName)
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_STATIC_DOCUMENMTS, documentName,[])
			result = entResult.getData()
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(Exception e){
			logger.error("Error while getting Clients by documentName ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		result
	}
}
