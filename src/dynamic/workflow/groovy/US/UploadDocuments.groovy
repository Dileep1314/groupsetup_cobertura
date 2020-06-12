package groovy.US

import com.metlife.gssp.repo.GSSPRepository
import org.slf4j.MDC;
import static java.util.UUID.randomUUID

import java.time.Instant
import java.util.concurrent.CompletableFuture

import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

import com.google.gson.JsonObject
import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.TokenManagementService
import com.metlife.service.entity.EntityService

import groovy.json.JsonOutput

/**
 * @author NarsiChereddy
 *
 */
class UploadDocuments implements Task{

	
	Logger logger = LoggerFactory.getLogger(UploadDocuments.class)
	GroupSetupDBOperations groupSetupDBOperations = new GroupSetupDBOperations()
	
	@Override
	public Object execute(WorkflowDomain workFlow) {
		
		def uploadedDocuments = []
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker.class)		
		def serviceUri = prepareServiceURI(workFlow.getEnvPropertyFromContext(GroupSetupConstants.DMF_UPLOAD_SPI_URL))
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def profile = workFlow.applicationContext.environment.activeProfiles
		logger.info("Upload Document serviceUri::: "+serviceUri)
		def requestBody = workFlow.getRequestBody()
		logger.info("UploadDocuments requestBody::: "+requestBody)
		Collection<CompletableFuture<Map<String, Object>>> infoFutures = new ArrayList<>(requestBody.size())
		def module = requestBody?.extension?.module
		def groupSetupId = requestBody?.extension?.groupSetupId
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetupId)
		logger.info("UploadDocuments : secValidationList: {"+ secValidationList +"}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("UploadDocuments : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def creatorNumber = requestBody?.extension?.creatorNumber
		def sourceSystemName = requestBody?.extension?.sourceSystemName
		def searchTypeCode = requestBody?.extension?.searchTypeCode		
		HttpHeaders multiPartPostHeaders = buildDMFCallSPIHeaders(workFlow)
		logger.info("multiPartPostHeaders::: "+multiPartPostHeaders)
		requestBody?.extension?.documents?.each { doc -> 
			//Request headers
			def metadata=[:]
			def extension=[:]
			def tempmetadata = []
			def tempextension = [:]
			def documentBlob = doc.getAt("content")
			//decoding bytestream
			def fileData = Base64.decoder.decode(documentBlob)
			def fileName = doc.getAt("fileName")
			def contentType = doc.getAt("formatCode")
			def fileType = doc.getAt("typeCode")
			def createDate = doc.getAt("createDate")
			def businessCapabilityName = doc.getAt("businessCapabilityName")
			def documentSourceCode = doc.getAt("documentSourceCode")
			def productFamilyName = doc.getAt("productFamilyName")
			
			//creating  request metadata
			tempmetadata << ["metadataKeyParameterName":"customerNumber","metadataKeyParameterValue":creatorNumber]
			tempmetadata << ["metadataKeyParameterName":"createDate","metadataKeyParameterValue":createDate]
			tempmetadata << ["metadataKeyParameterName":"documentDetailTypeCode","metadataKeyParameterValue":(fileType=='MasterApplication') ? 'MasterApp':fileType]
			tempmetadata << ["metadataKeyParameterName":"businessCapabilityName","metadataKeyParameterValue":businessCapabilityName]
			tempmetadata << ["metadataKeyParameterName":"documentSourceCode","metadataKeyParameterValue":documentSourceCode]
			tempmetadata << ["metadataKeyParameterName":"productFamilyName","metadataKeyParameterValue":productFamilyName]
			tempmetadata << ["metadataKeyParameterName":"billDueDate","metadataKeyParameterValue":"2020-01-01"]
			tempmetadata << ["metadataKeyParameterName":"documentName","metadataKeyParameterValue":fileName]
			//static values for temporarily
			tempmetadata << ["metadataKeyParameterName":"partyID","metadataKeyParameterValue":"12"]
			tempmetadata << ["metadataKeyParameterName":"brokerID","metadataKeyParameterValue":"44"]
			tempmetadata << ["metadataKeyParameterName":"brokerageID","metadataKeyParameterValue":"33"]
			tempmetadata << ["metadataKeyParameterName":"marketTypeCode","metadataKeyParameterValue":"12"]
			tempmetadata << ["metadataKeyParameterName":"documentID","metadataKeyParameterValue":'1234444qwes']
			tempmetadata << ["metadataKeyParameterName":"employeeID","metadataKeyParameterValue":"01"]
			tempmetadata << ["metadataKeyParameterName":"billingBranchNumber","metadataKeyParameterValue":"012"]
			tempmetadata << ["metadataKeyParameterName":"experienceNumber","metadataKeyParameterValue":'02']
			tempmetadata << ["metadataKeyParameterName":"documentName","metadataKeyParameterValue":'test']
			extension << ["sourceSystemName":sourceSystemName,"searchTypeCode":searchTypeCode]
			metadata.put("metadata", tempmetadata)
			tempextension.put("extension", extension)
			logger.info("tempmetadata::: "+tempmetadata)
			//creating request for document
			HttpHeaders documentHeader = new HttpHeaders();
			documentHeader.add("Content-Type", contentType)
			documentHeader.setContentDispositionFormData("documentBLOB", fileName)
			HttpEntity<ByteArrayResource> part = new HttpEntity<>(fileData, documentHeader);

			//creating metadata json request
			HttpHeaders jsonHeader = new HttpHeaders();
			jsonHeader.setContentType(MediaType.APPLICATION_JSON);
			jsonHeader.setContentDispositionFormData("documentMetadata", null)
			HttpEntity<JsonObject> jsonHttpEntity = new HttpEntity<>(metadata, jsonHeader);

			//creating extension json request
			HttpHeaders jsonHeaderExtension = new HttpHeaders();
			jsonHeaderExtension.setContentType(MediaType.APPLICATION_JSON);
			jsonHeaderExtension.setContentDispositionFormData("extension", null)
			HttpEntity<JsonObject> extensionHttpEntity = new HttpEntity<>(tempextension, jsonHeaderExtension);

			// putting the three parts in one request
			LinkedMultiValueMap<String, Object> multipartRequest = new LinkedMultiValueMap<>();
			multipartRequest.add("documentBLOB", part);
			multipartRequest.add("documentMetadata", jsonHttpEntity);
			multipartRequest.add("extension", extensionHttpEntity);
			MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
			MDC.put(GroupSetupConstants.SUB_API_NAME, serviceUri);
			//initiating asynchronous call
			infoFutures.add(CompletableFuture.supplyAsync({->getDocsFromSPI(serviceUri, registeredServiceInvoker,multipartRequest, multiPartPostHeaders )}))
			CompletableFuture.allOf(infoFutures.toArray(new CompletableFuture<?>[infoFutures.size()])).join()
			logger.info("infoFutures::: "+infoFutures)
			def updatedDetails = [:]
			infoFutures.forEach({k ->
				if(k?.get()?.getBody() != null){
					Map<String, Object> returnMap =  k?.get()?.getBody()
					updatedDetails.putAll(returnMap)
					def documentId = returnMap.get('documentID')
					if(documentId)
					{
						if(module == 'GSP')
							saveDocumentDetailsInMongoDB(doc, groupSetupId, documentId, entityService)
						updatedDetails << ['status':'success']
					}
					else
					{
						updatedDetails << ['status':'failed']
					}
				} else {
					updatedDetails << ['status':'failed']
				}
				updatedDetails << ['fileName':fileName, 'fileType':fileType, 'fileDisplayName': doc?.fileDisplayName, 'fileDesc': doc?.fileDesc,'createdDate':createDate]
			})
			if(updatedDetails){
				uploadedDocuments << updatedDetails
			}
		}
		workFlow.addResponseBody(new EntityResult('uploadedDocuments':uploadedDocuments, true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		workFlow.addResponseStatus(HttpStatus.CREATED)
	}
	
	def prepareServiceURI(DMFUploadUrl)
	{ 
//		def docsSPIURI = "/metlife/integration/dms/v2/repositories/documents"
		def uriBuilder = UriComponentsBuilder.fromPath(DMFUploadUrl)
		def serviceUri = uriBuilder.build(false).toString()
		serviceUri
	}

	def buildDMFCallSPIHeaders(WorkflowDomain workFlow)
	{
		def tokenService = workFlow.getBeanFromContext("tokenManagementService", TokenManagementService.class)
		def token = tokenService.getToken()
		def headersList = workFlow.getEnvPropertyFromContext('gssp.headers')
		def userId = workFlow.getRequestHeader()?.getAt("userid")
		def pathParams = workFlow.getRequestPathParams()
		def userAgent = workFlow.getRequestHeader()?.getAt("user-agent")
		def xclient= workFlow.getEnvPropertyFromContext('apmc.clientId')
//		def token ='Bearer eyJlbmMiOiJBMTI4Q0JDLUhTMjU2IiwiYWxnIjoiQTI1NktXIn0.1HzH8GFEJkImFSwsTZVSZknkNTNilYY0ah2JJAbnihMnwmCeFe5fuA.2WX_YGu7bD4_uVP-bZullQ.nYgOIjd_OdAimFDV70E8MBzJcu2CilEODnpmgG7PZ_dmRmOvmWpih14QnSqcWTDgke9EVDJxua7yZ9rK-FpVGyhgNE5Bn2ih8sO-nhKX2aaa2TJUxdow9c2tjTGa3oA6n2B4r_INs438JZ7jAo7eoVo7dteQWMmOa7VUx_d37w61LUnJrJYkQyCCsAlarYHelX7kNAY7-GmhRCTiMHXK0xO9FVkpRDE7aKFuaoE64f0BINBd-zqn8nQYAerYoI3tewpjhlJKha7vGZI_pyPubaBBtpszKdJY1GeuuyXOpl1MsqNbMcgPUm1jiH24cPouzWFZtG3VYJ2KvKyfoWCVFTxVBp81Q8yrKo0rWL4tLOaqX0mOf3i-WLkSX4OMgM0RAn-upr2xyP8aZxN3E5xs8z23qZT8a8OUT_Vtvgge7kmHqJisay-ZvDGgkRakB-AcUC4JFVQf0vJ5HMEplCRAbELE7v82xbV9F4-MfgoWdDA3I1SXAQAXb9oNrKbCozPnEzfG19azBuq-L_S6cTsJaBRay4iTWdvfF9_wAQfyLK61FusMb9_r1sEv8N2D7qXoHKQulpMzKF-s0qQ-j9k5x03OEaGWgf4c6ZJr3_SziU6Ert0WszoRnuA0PpxIe4Ot557SOhp-olx6dNHsST0nffwbdrAxK4JcRPj1bZD3WT9PQ4XxsWVkwTZg51NXuEG_34d5pxBuj0lmcAiuAFyWxN-ZlWY_Yga4NoUlsivDy1EltEP6LRt_A6BNO-4f2C5oiHUEVnSYGHr6aT3u580P9Cez0EjW_xm4ySwI2OHHPDBD8E7wSNvyrk9Q8Uz4dtJsTyRGQHgv7cJ-ZpXLZkO73r9Aohjttcxkgk_Xng41ti63JUtT-ZaJ0gtx3tLT3H8RIgaVMNnmSMumNfIFz5s39Nnf01qorGeohCWo_5eClcyRBPeHpejM1T6P0KwvP0_JTLFG2gzr5oEt7f7lmB6V51iWgnVBVuNSKx80pUpIZ62O1g9OAxYq1pD5kQXnnEDKuXzfmyvzUMvg9kkzJTR1Vt9Jl-wU4bNWE0SX0v2IYSlLj1dhEWBah-glOe24Qo6KyU6BgWas_LnlfDCHaLy9XAG5kRvMDkT2YfHS5fxtaLVnb0i2sWWx4D9y6DP4c1nrF8AHF7HD-ETAfuHrruBGTq9ndkDat0icUUydvxM6XrQGpCU1HWpbqpI4pkMICryyznj0k-FPf8_3l0p0LS6vjgrqiR6sbnH71W-m7AB1yxyFUA8Po2NRAmrmHAN24VSZiaHX3gr0LIQXR2AdfvPhOJ-mpq-DTAwLn9HqVm6OW4OQ7_h07kvGiiKzN2ZbF7Y8RPWBjTlMWVfO1qUueKWNlrRfYivawgoUHVix1z3f-3SDcNyI2_iegpP1SyHt1WSlrk2Ivdvx6NqcjzDNVHGfj0qR1bKYBk01XgIPF3bp19ZZg53nuiFZCzSFnwV7XNRB6VIBf7zKS7gVkUTQMpdBc39qgYKnyk9pB3cbimbydUePt29eldm8MtWmO81BPbSHjzNHqcf0P0Ui9iG2wyTLN82EbX52ePflJPITAHi8fRgPXq2NuYLVIOsk73SaOMQmHrjvrYmz44VnEL3a0olc2ILVaDy-dUyvZtNazGf2tD82WjH1dFISRlKvBjx3lOd40qeo0TZmgpvsHUqTow1T10Ue3AcollEnquLcpjiR38zpXNNnAcoAExdMyn-Luj09N7y4rcd-qcXB8jB1j3ETmXnzwePu73RxkTakOxY9GH-CGhCDAiV9pTiq-JBl2PWh.tWxXdZnmnWGs9GYjAroa-w'
		def xtenant = workFlow.getEnvPropertyFromContext('apmc.tenantId')
		def xspiservice = workFlow.getEnvPropertyFromContext('apmc.serviceId')
		def tenantId = pathParams['tenantId']
		HttpHeaders multiPartPostHeaders = new HttpHeaders();
		multiPartPostHeaders.set("Content-Type", "multipart/form-data");
		multiPartPostHeaders.set("UserId", 'asdasd');
		multiPartPostHeaders.set("User-Agent", userAgent);
		multiPartPostHeaders.set("x-ibm-client-id", xclient);
		multiPartPostHeaders.set("RequestTxnId", randomUUID().toString());
		multiPartPostHeaders.set("x-spi-service-id", xspiservice);
		multiPartPostHeaders.set("x-gssp-tenantid", xtenant);
		multiPartPostHeaders.set("Authorization",token);
		multiPartPostHeaders
		
	}
	/**
	 * This method added the header details from list to map object.
	 *
	 * @param headersList
	 * @param headerMap
	 */
	static def getRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[X_GSSP_TRANSACTION_ID:randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): headerMap[header]]
			}
		}
		spiHeaders
	}
   def getDocsFromSPI(serviceUri, registeredServiceInvoker, multipartRequest, multiPartPostHeaders){
		def docsResponse
		def response
		HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(multipartRequest, multiPartPostHeaders)
		logger.info("Doc requestEntity::: "+requestEntity)
		try 
		{
			docsResponse = registeredServiceInvoker.postViaSPI(serviceUri, requestEntity, Map.class)
		} catch (e) {
			logger.error("Error while uploading document::: "+e.message)
		}
		logger.info("docsResponse size::: "+docsResponse.size())
		docsResponse
	}
	
	def saveDocumentDetailsInMongoDB(doc, groupSetupId, documentId, entityService)
	{
		MDC.put("SAVE_DOC_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def uploadedFilesDetails
		def data = [:] as Map
		data << ['fileName': doc?.fileName]
		data << ['fileDisplayName': doc?.fileDisplayName]
		data << ['fileDesc': doc?.fileDesc]
		data << ['fileType': doc?.typeCode]
		data << ['createdDate': doc?.createDate]
		data << ['documentId': documentId]
		uploadedFilesDetails = checkDataAvailability(entityService, groupSetupId)
		if(uploadedFilesDetails)
		{
			logger.info("updating IIB DB2 GSUploadDocuments collection with uploaded details")
			List filesList = uploadedFilesDetails?.files
			filesList.add(data)
			uploadedFilesDetails << ['files':filesList]
			entityService.updateById(GroupSetupConstants.COLLECTION_GS_UPLOAD_DOCUMENMTS, groupSetupId, uploadedFilesDetails)
			logger.info("uploadedFilesDetails::: "+uploadedFilesDetails)
		}else{
			logger.info("uploadedFilesDetails are empty")
			def response = [:]
			response << ["_id":groupSetupId]
			def filesList = []
			filesList.add(data)
			response << ['files':filesList]
			groupSetupDBOperations.create(GroupSetupConstants.COLLECTION_GS_UPLOAD_DOCUMENMTS, response, entityService)
		}
		MDC.put("SAVE_DOC_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}
	
	/**
	 * Getting uploaded documents details from mongodb.
	 * @param entityService
	 * @param brokerId
	 * @return
	 */
	def checkDataAvailability(entityService, String id) {
		def result = null
		try{
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_UPLOAD_DOCUMENMTS, id,[])
			result = entResult.getData()
		}catch(AppDataException e){
			logger.error("Record not found::: "+e.getMessage())
		}catch(Exception e){
			logger.error("Error while getting checkDataAvailability by Id::: "+e.getMessage())
			throw new GSSPException("40001")
		}
		result
	}
	
	/**
	 *
	 * temp method for spi externalizaton
	 *
	 */
	def getDocsFromRestSPI(docsSPIURI, registeredServiceInvoker, multipartRequest, multiPartPostHeaders)  {
		RestTemplate restTemplate = new RestTemplate()
		HttpHeaders headers = new HttpHeaders()
		HttpEntity entity = new HttpEntity(multiPartPostHeaders)
		def host = "http://lxrsvcppt002:35635"
		def uri = host + docsSPIURI
		logger.info('host from configuration >>>>>>>>>>>>>'+ uri)
		def completeSpiResponse
		def requestPayload = new HttpEntity<>(multipartRequest,multiPartPostHeaders)
		logger.info("requestPayload "+ JsonOutput.toJson(requestPayload))
		completeSpiResponse  = restTemplate.exchange(uri, HttpMethod.POST, requestPayload, Map.class)
		completeSpiResponse
	}
}
	