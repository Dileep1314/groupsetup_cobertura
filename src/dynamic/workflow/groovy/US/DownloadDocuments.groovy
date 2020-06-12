package groovy.US

import static java.util.UUID.randomUUID

import java.time.Instant

import org.slf4j.MDC;
import org.springframework.core.io.Resource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.TokenManagementService
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration

/**
 * Search and Download Document from DMF Metlife Portal.
 * @author Durgesh Kumar Gupta, Shikhar Arora, NarsiChereddy
 *
 */
class DownloadDocuments implements Task{
	Logger logger = LoggerFactory.getLogger(DownloadDocuments.class)
	def static final SERVICE_ID = "DDS014"
	def BS_DOC_TYPE_NAME_ENUM = [COREPOSTSLIFEADDBS : "Basic Life/AD&D Term Life Benefit Summary", COREPOSTSBUYUPLIFEBS : "Core Buy-up Life Benefit Summary", COREPOSTSDENBS : "Dental Benefit Summary", COREPOSTSLTDBS : "Long Term Disability Benefit Summary", COREPOSTSSTDBS : "Short Term Disability Benefit Summary", COREPOSTSSUPPBS : "Supplemental Term Life Benefit Summary", COREPOSTSVDBS : "Voluntary Dental Benefit Summary", COREPOSTSVBS : "Vision Benefit Summary"]
	def CB_DOC_TYPE_NAME_ENUM = [CIPRESSALECB: "Critical Illness Cost and Benefit Summary", HIPRESSALECB: "Hospital Indemnity Cost and Benefit Summary", AXPRESSALECB:"Accident Cost and Benefit Summary", MLCBPRESALE:"MetLaw Cost and Benefit Summary"]
		@Override
		public Object execute(WorkflowDomain workFlow) {
			Date start1 = new Date()
			logger.info("DownloadDocuments groovy process execution starts")
			def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
			def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
			def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
			def requestPathParamsMap = workFlow.getRequestPathParams()
			def profile = workFlow.applicationContext.environment.activeProfiles
			def securityScan = workFlow.getEnvPropertyFromContext("securityScan")
			def tenantId = requestPathParamsMap[GroupSetupConstants.TENANT_ID]
			def spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPIDOCUMENTSEARCHURL)
			def groupSetup_Id = requestPathParamsMap[GroupSetupConstants.GROUPSETUP_ID]
			def proposalId = groupSetup_Id.split('_')[2]
			def userId, module, documentType, documentId, groupSetupId, token, documentName
			def requestParams = workFlow.getRequestParams()
			logger.info("requestParams: "+requestParams)
			def requestParamsMap = GroupSetupUtil.parseRequestParamsMap(requestParams)
			if(requestParamsMap) {
				userId = (requestParamsMap?.userId) ? requestParamsMap?.userId : ""
				module = (requestParamsMap?.module) ? requestParamsMap?.module : ""
				documentType = (requestParamsMap?.documentType) ? requestParamsMap?.documentType : ""
			}
			//Enrollment module check for security - Begin
			if(!module.toString().equalsIgnoreCase("enrollment")) {
			//Sec-code changes -- Begin
				def secValidationList = [] as List
				secValidationList.add(groupSetup_Id.split('_')[0])
				logger.info("DownloadDocuments : secValidationList: {" + secValidationList + "}")
				ValidationUtil secValidationUtil = new ValidationUtil();
				def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
				logger.info("DownloadDocuments : secValidationResponse: {" + secValidationResponse + "}")
			//Sec-code changes -- End
			}
			//Enrollment module check for security - End
			def requestBody = workFlow.getRequestBody();
			documentId = requestBody['documentId']
			documentName = requestBody['documentName']
			groupSetupId = requestBody['groupSetupId']
			def dmfRequest, responseMap, responseArray
			def documentLists = [] as Set
			def tokenService = workFlow.getBeanFromContext("tokenManagementService",TokenManagementService.class)
			token =tokenService.getToken()
			if(documentId) {
				responseArray = getDocumentByDocumentId(securityScan,workFlow, registeredServiceInvoker, token, userId, documentId, documentName, requestBody, profile)
				responseMap = ['files' : responseArray]
				workFlow.addResponseBody(new EntityResult(['Details': responseMap], true))
			}else {
				def spiHeadersMap= buildDMFCallSPIHeaders(workFlow, userId, token, GroupSetupConstants.POST_METHOD)
				dmfRequest = createRequestFormate(requestBody, module, documentType, proposalId)
				documentLists = searchDMFItems(spiPrefix, registeredServiceInvoker, spiHeadersMap, dmfRequest, profile, groupSetup_Id)
				logger.info("documentLists::: "+documentLists + "module : "+module +"documentType : "+documentType)
				if(module=="enrollment") {
					workFlow.addResponseBody(new EntityResult(['Details': documentLists], true))
				}else {
					if(documentLists && !documentType) {
						def getSpiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPIDOCUMENTGETURL)
						def getSpiHeadersMap = buildDMFCallSPIHeaders(workFlow,userId, token,GroupSetupConstants.GET_METHOD)
						responseArray = getGroupSetupDocuments(securityScan,getSpiPrefix, registeredServiceInvoker, getSpiHeadersMap, documentLists, dmfRequest, entityService, documentType, profile, workFlow)
					}
					else if (documentType && documentType == "policyCertificates" ){
						responseArray = preparePolicyCertificateDocs(documentLists)
					}
					else if (documentType && documentType == "CostBenefit" ){
						responseArray = prepareCostAndBenefitDocs(documentLists)
					}
					else{
						responseArray = preparingOOCOrBSDocsList(documentLists, documentType)
						logger.info("responseArray :"+responseArray)
					}
					responseMap = ['files' : responseArray]
					workFlow.addResponseBody(new EntityResult(['Details': responseMap], true))
				}
			}
			MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp());
			Instant endTime = Instant.now()
			MDC.put("UI_MS_END_TIME", endTime.toString())
			if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
				GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
			}
			Date stop1 = new Date()
			TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
			logger.info("${SERVICE_ID} ----> ${groupSetup_Id} === MS api elapseTime : " + elapseTime1)
			workFlow.addResponseStatus(HttpStatus.OK)
		}
	
		def getGroupSetupDocuments(securityScan,getSpiPrefix, registeredServiceInvoker, getSpiHeadersMap, documentLists, dmfRequest, entityService, documentType, profile, workFlow) {
			def docNames = [CostBenefit: "Cost and Benefit Summary", COREPSCB:"Cost and Benefit Summary", FinancialSummary: "Financial Summary", COMMISSIONAGREEACKN: "Commission Agreement Acknowledgement", GROSSUPLTR:"Gross-Up Letter", PORTTRUST:"Portability Trust letter", SMDRiskAssesmentSummary:"Risk Assessment Form (RAS)", HIPAAREQUESTFORM:"HIPAA Letter", MASTERAPP:"Master Application"]
			def responseArray = [] as Set
			def documentIds = [] as Set
			int count = 1
			documentLists.each{ document ->
				ResponseEntity<Resource> response = getDocumentFromDMF(getSpiPrefix, registeredServiceInvoker, getSpiHeadersMap, document?.documentID, dmfRequest, profile)
				if(response.getBody()){
					def content = response.getBody().getInputStream().getBytes().encodeBase64().toString()
					if(securityScan?.equalsIgnoreCase("true")){
						getScanResponse(content, workFlow)
					}
					def documentDetailTypeCode = document?.categorization?.documentDetailTypeCode
					def contentType = response.getHeaders().getContentType()
					def docName = docNames.get(documentDetailTypeCode)
					def fileExtension = getFileExtension(contentType)
					def fileName = (docName) ? docName + "-${count}" + fileExtension : "document-${count}"+fileExtension
					def fileData = [:] as Map
					fileData << ['content' : content]
					fileData << ['formatCode' :contentType]
					fileData << ['name' :fileName]
					responseArray.add(fileData)
					count = count+1
				}
			}
			responseArray
		}
	
		def preparingOOCOrBSDocsList(documentLists, documentType) {
			def responseArray = [] as Set
			documentLists.each{ document ->
				def documentDetailTypeCode = document?.categorization?.documentDetailTypeCode
				def bsDocName = BS_DOC_TYPE_NAME_ENUM.get(documentDetailTypeCode)
				if((documentDetailTypeCode.startsWith("OOC") && documentType == "OOC") || documentDetailTypeCode.startsWith("BS") || bsDocName)
				{
					def documentId = document?.documentID
					def docName
					if(!bsDocName)
						docName = getOOCBSDocumentName(documentDetailTypeCode)
					else
						docName = bsDocName
					logger.info("documentLists::: "+documentDetailTypeCode + "module : "+docName +"documentType : "+responseArray)
					def fileName = (docName) ? docName + ".pdf" : 'document.pdf'
					Map fileData = new HashMap()
					fileData.putAt('documentId', documentId)
					fileData.putAt('name', fileName)
					logger.info("fileData::: "+fileData)
					responseArray.add(fileData)
				}
			}
			responseArray
		}
		
		def preparePolicyCertificateDocs(documentLists) {
			def policyNames = [LPOL: "Life Policy", DPOL: "Dental Policy", STDPOL: "Short Term Disability Policy", LTDPOL:"Long Term Disability Policy", VPOL:"Vision Policy", VBAXPOL:"Group Accident and Hospital Indemnity Policy", VBHIPOL:"Hospital Indemnity Policy", VBCIPOL:"Critical Illness Policy", DHMOPOL:"DHMO policy"]
			def certificateNames = [LCERT:"Life Certificate", DCERT:"Dental Certificate", STDCERT:"Short Term Disability Certificate", LTDCERT:"Long Term Disability Certificate", VCERT:"Vision Certificate", VBCICERT:"Critical Illness Certificate", VBAXCERT:"Accident Injury Certificate", VBHICERT:"Hospital Indemnity Certificate", DHMOSOBCERT:"DHMO Schedule of Benefits Certificate", DHMOEOCCERT:"DHMO Evidence of Coverage Certificate", CPCERT:"Participant level Certificate"]
			def responseArray = [] as Set
			documentLists.each{ document ->
				def documentTypeCode = document?.categorization?.documentTypeCode
				if(documentTypeCode && (documentTypeCode == "Certificate" || documentTypeCode == "Policy"))
				{
					def docName
					def documentDetailTypeCode = document?.categorization?.documentDetailTypeCode
					def documentId = document?.documentID
					if(documentTypeCode == "Certificate")
						docName = certificateNames.get(documentDetailTypeCode)
					else
						docName = policyNames.get(documentDetailTypeCode)
					def fileName = (docName) ? docName + ".pdf" : 'document.pdf'
					Map fileData = new HashMap()
					fileData.putAt('documentId', documentId)
					fileData.putAt('name', fileName)
					logger.info("fileData::: "+fileData)
					responseArray.add(fileData)
				}
			}
			responseArray
		}
		
		def prepareCostAndBenefitDocs(documentLists) {
			def responseArray = [] as Set
			int count = 1
			documentLists.each{ document ->
				def documentDetailTypeCode = document?.categorization?.documentDetailTypeCode
				def documentId = document?.documentID
				def docName = CB_DOC_TYPE_NAME_ENUM.get(documentDetailTypeCode)
				if(docName)
				{
					Map fileData = new HashMap()
					fileData.putAt('documentId', documentId)
					fileData.putAt('name', docName)
					responseArray.add(fileData)
					
				}else if(documentDetailTypeCode == "COREPSCB")
				{
					docName = "Core Product Cost and Benefit Summary-" + count
					Map fileData = new HashMap()
					fileData.putAt('documentId', documentId)
					fileData.putAt('name', docName)
					responseArray.add(fileData)
					count = count+1
				}
			}
			logger.info("responseArray::: "+responseArray)
			responseArray
		}
	
		/**
		 * Getting Document by documentId from DMF.
		 * @param workFlow
		 * @param registeredServiceInvoker
		 * @param token
		 * @param userId
		 * @param documentId
		 * @param documentName
		 * @param requestBody
		 * @param profile
		 * @return
		 */
		def getDocumentByDocumentId(securityScan,workFlow, registeredServiceInvoker, token, userId, documentId, documentName, requestBody, profile) {
			MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
			def responseArray = [] as Set
			def getSpiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPIDOCUMENTGETURL)
			def getSpiHeadersMap = buildDMFCallSPIHeaders(workFlow,userId, token,GroupSetupConstants.GET_METHOD)
			ResponseEntity<Resource> response = getDocumentFromDMF(getSpiPrefix, registeredServiceInvoker, getSpiHeadersMap, documentId, requestBody, profile)
			if(response?.getBody()){
				logger.info("response.headers::: "+response.headers)
				def content = response.getBody().getInputStream().getBytes().encodeBase64().toString()
				if(securityScan?.equalsIgnoreCase("true")){
				   getScanResponse(content, workFlow)
				}
				def contentType = response.getHeaders().getContentType()
				def fileName = getDocumentName(contentType, response.getHeaders().CONTENT_DISPOSITION, documentName)
				def fileData = [:] as Map
				fileData << ['content' : content]
				fileData << ['formatCode' : contentType]
				fileData << ['name' : fileName]
				responseArray.add(fileData)
			}
			MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
			responseArray
		}
	
	
		private searchDMFItems(spiPrefix, registeredServiceInvoker, header, dmfRequest, profile, groupSetup_Id) {
			def uriBuilder = UriComponentsBuilder.fromPath("${spiPrefix}")
			def serviceUri = uriBuilder.build(false).toString()
			def response
			logger.info("header::: "+header +"dmfRequest::: "+dmfRequest+" serviceUri :: "+serviceUri )
			HttpEntity<List> request
			try {
	
				if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
					response= GroupSetupUtil.getTestData("searchDocument.json")
					return response.items
				}else{
					logger.info("${SERVICE_ID} ----> ${groupSetup_Id} ==== Calling DMF SearchDocument API: ${serviceUri} ")
					Date start = new Date()
					request = registeredServiceInvoker.createRequest(dmfRequest,header)
					response = registeredServiceInvoker.postViaSPI(serviceUri, request,Map.class)
					Date stop = new Date()
					TimeDuration elapseTime = TimeCategory.minus(stop, start)
					logger.info("${SERVICE_ID} ----> ${groupSetup_Id} ==== DMF SearchDocument API: ${serviceUri}, Response time : "+ elapseTime)
				}
				logger.info("DMF Response::: "+response)
			} catch (e) {
				logger.error("Error while retrieving documents from DMF::: "+e.message)
				//			throw new GSSPException("SEARCH_DOCUMENT_ERROR")
			}
			def resBody = response?.body
			if(resBody){
				return resBody.items
			}else{
				return ""
			}
		}
	
		private createRequestFormate(Map requestBody, module, documentType, proposalId){
	
			StringBuilder builder=new  StringBuilder("(");
			String[] keys=requestBody.keySet() as String[]
			for(String key: keys){
				if(!(key.equals("extension")) && !(key.equals("metadata"))){
					builder.append("(searchKeyParameterName==")
					builder.append("\"${key}\";")
					builder.append(" searchKeyParameterValue==")
					def value=requestBody[key]
					builder.append("\"${value}\");")
				}
			}
			if(documentType && (documentType == "OOC")) {
				builder.append("(searchKeyParameterName==")
				builder.append("\"documentDetailTypeCode\";")
				builder.append(" searchKeyParameterValue==")
				builder.append("\"OOC-*\");")
			}
			if(documentType && (documentType == "CostBenefit")) {
				def docType = "CostBenefit"
				builder.append("(searchKeyParameterName==")
				builder.append("\"documentTypeCode\";")
				builder.append(" searchKeyParameterValue==")
				builder.append("\"${docType}\");")
				
				builder.append("(searchKeyParameterName==")
				builder.append("\"quoteID\";")
				builder.append(" searchKeyParameterValue==")
				builder.append("\"${proposalId}\");")
			}
			String queryString= builder.toString().substring(0,builder.toString().length()-1)+")";
			Map jsonObj = [:];
			jsonObj = (HashMap<String, Object>) requestBody;
			Map queryObject=[:] as Map
			queryObject.put("q", queryString)
			queryObject.put("extension", jsonObj.extension)
			queryObject.put("metadata", jsonObj.metadata)
			logger.info("queryObject::: "+queryObject)
			return queryObject
		}
	
		private getDocumentFromDMF(getSpiPrefix, registeredServiceInvoker, spiHeadersMap, documentId, dmfRequest, profile) {
			def endpoint = "${getSpiPrefix}/${documentId}"
			MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
			def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
			uriBuilder.queryParam("SourceSystemName", (dmfRequest?.extension?.sourceSystemName) ? dmfRequest?.extension?.sourceSystemName : 'SMD')
			uriBuilder.queryParam("SearchTypeCode", (dmfRequest?.extension?.searchTypeCode) ? dmfRequest?.extension?.searchTypeCode : 'MGI')
			def serviceUri = uriBuilder.build(false).toString()
			logger.info("DMF Request serviceUri:::>> "+serviceUri +"documentId:: "+documentId)
			ResponseEntity<Resource> response
			try {
				if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
					response = GroupSetupUtil.getTestData("document.json").masterAppPdf
				}else{
					logger.info("${SERVICE_ID} ----> ${documentId} ==== Calling DMF GetDocument API : ${endpoint}")
					Date start = new Date()
					response = registeredServiceInvoker.getViaSPI(serviceUri, Resource.class, [:], spiHeadersMap)
					Date stop = new Date()
					TimeDuration elapseTime = TimeCategory.minus(stop, start)
					logger.info("${SERVICE_ID} ----> ${documentId} ==== DMF GetDocument API: ${endpoint}, Response time : "+ elapseTime)
					//				data = response.getBody().getInputStream().getBytes().encodeBase64().toString()
					logger.info("documents from  DMF>>> "+response)
				}
			} catch (e) {
				logger.error("ERROR while retrieving documents from DMF"+e.message)
				//			throw new GSSPException("GET_DOCUMENT_ERROR")
			}
			return response
		}
	
		/**
		 *
		 * @param workFlow
		 * @param userId
		 * @param token
		 * @param method
		 * @return
		 */
		def buildDMFCallSPIHeaders(WorkflowDomain workFlow, userId, token, method){
			def requestHeaders =  workFlow.getRequestHeader()
			def headersList=workFlow.getEnvPropertyFromContext(GroupSetupConstants.GSSP_HEADERS)
			def spiHeadersMap
			if(method.equals(GroupSetupConstants.POST_METHOD)){
				spiHeadersMap = getRequiredHeaders(headersList.tokenize(",") , requestHeaders)
				spiHeadersMap << ['X-IBM-Client-Id' : workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_CLIENT_ID),
					'Authorization' : token,
					'x-gssp-tenantid': workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_TENTENT_ID),
					'x-spi-service-id': workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_SERVICE_ID),
					'RequestTxnId':randomUUID().toString(),
					'ServiceName':GroupSetupConstants.SERVICE_NAME,
					'UserId':userId]
			}else{
				spiHeadersMap = getRequiredHeadersForGetCall(headersList.tokenize(",") , requestHeaders)
				spiHeadersMap << ['X-IBM-Client-Id' : [workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_CLIENT_ID)],
					'Authorization' : [token],
					'x-gssp-tenantid': [workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_TENTENT_ID)],
					'x-spi-service-id': [workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_SERVICE_ID)],
					'RequestTxnId':[randomUUID().toString()],
					'ServiceName':[GroupSetupConstants.SERVICE_NAME],
					'UserId':[userId]]
			}
			return spiHeadersMap
		}
		def getRequiredHeaders(List headersList, Map headerMap) {
			headerMap<<[('x-gssp-trace-id'):randomUUID().toString()]
			def spiHeaders = [:]
			for (header in headersList) {
				if (headerMap[header]) {
					spiHeaders << [(header): headerMap[header]]
				}
			}
			spiHeaders
		}
		def getRequiredHeadersForGetCall(List headersList, Map headerMap) {
			headerMap<<[('x-gssp-trace-id'):randomUUID().toString()]
			def spiHeaders = [:]
			for (header in headersList) {
				if (headerMap[header]) {
					spiHeaders << [(header): [headerMap[header]]]
				}
			}
			spiHeaders
		}
	
		def getOOCBSDocumentName(documentDetailTypeCode) {
			def documentName
			def plan, formId, ch, situs, provision, plVar, spVar
			def chA = [L: "Low", M: "Medium", H: "High"]
			def documentDetailTypeCodes = documentDetailTypeCode.split("-")
			def arrayLength = documentDetailTypeCodes.length
			if (documentDetailTypeCode.toString().contains("OOC")) {
				formId = "OOC"
				if(documentDetailTypeCode.toString().contains("ACC")) {
					plan = "Accident"
					if(documentDetailTypeCodes[1] == "NW") {
						situs = "Nationwide"
					} else {
						situs = documentDetailTypeCodes[1]
					}
	
					if(arrayLength > 4)
					{
						if(documentDetailTypeCodes[4] == "OJ") {
							provision = "Off-Job"
						} else if(documentDetailTypeCodes[4] == "24") {
							provision = "24hr"
						}
					}
	
					def choiceCharArray = [] as List
					choiceCharArray = documentDetailTypeCodes[3].toString().toCharArray()
					if(choiceCharArray.size() == 1) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 2) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 3) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + "/" + chA.get(choiceCharArray.getAt(2).toString()) + " Plan)"
					}
	
					documentName = plan + " " + formId + " " + ch + " - " + situs + " - " + provision
				} else if(documentDetailTypeCode.toString().contains("CI")) {
					plan = "Critical Illness"
					if(documentDetailTypeCodes[1] == "NW") {
						situs = "Nationwide"
					} else {
						situs = documentDetailTypeCodes[1]
					}
	
					documentName = plan + " " + formId + " - " + situs
				} else if(documentDetailTypeCode.toString().contains("HI")) {
					plan = "Hospital Indemnity"
	
					if(documentDetailTypeCodes[1] == "NW") {
						situs = "Nationwide"
					} else if(documentDetailTypeCodes[1] == "NYU") {
						situs = "NY Upstate"
					} else if(documentDetailTypeCodes[1] == "NYM") {
						situs = "NY Metro"
					} else {
						situs = documentDetailTypeCodes[1]
					}
	
					if(arrayLength > 4)
					{
						if(documentDetailTypeCodes[4] == "10") {
							provision = "10day"
						} else if(documentDetailTypeCodes[4] == "31") {
							provision = "31day"
						} else {
							provision = documentDetailTypeCodes[4] + "day"
						}
					}
	
					def choiceCharArray = [] as List
					choiceCharArray = documentDetailTypeCodes[3].toString().toCharArray()
					if(choiceCharArray.size() == 1) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 2) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 3) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + "/" + chA.get(choiceCharArray.getAt(2).toString()) + " Plan)"
					}
	
					documentName = plan + " " + ch + " - " + situs +  " - " + provision
				}
			} else if(documentDetailTypeCode.toString().contains("BS")) {
				formId = "Ben Sum"
	
				if(documentDetailTypeCode.toString().contains("ACC")) {
					plan = "Accident"
					if(documentDetailTypeCodes[1] == "NW") {
						situs = "Nationwide"
					} else {
						situs = documentDetailTypeCodes[1]
					}
	
					def choiceCharArray = [] as List
					choiceCharArray = documentDetailTypeCodes[3].toString().toCharArray()
					if(choiceCharArray.size() == 1) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 2) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 3) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + "/" + chA.get(choiceCharArray.getAt(2).toString()) + " Plan)"
					}
	
					documentName = plan + " " + formId + " " + ch + " - " + situs
				} else if(documentDetailTypeCode.toString().contains("CI")) {
					plan = "Critical Illness"
					if(documentDetailTypeCodes[1] == "NW") {
						situs = "Nationwide"
					} else {
						situs = documentDetailTypeCodes[1]
					}
	
					def planVar = documentDetailTypeCodes[3]
					if(planVar.toString().equalsIgnoreCase("EE10k")) {
						plVar = "10k/20k"
					} else if(planVar.toString().equalsIgnoreCase("EE15k")) {
						plVar = "15k/30k"
					} else if(planVar.toString().equalsIgnoreCase("EE5k")) {
						plVar = "5k"
					} else {
						plVar = planVar.toString().replace("EE", "")
					}
	
					if(arrayLength > 4 && documentDetailTypeCodes[4]) {
						def spouseVar = documentDetailTypeCodes[4]
						if(spouseVar.toString().equalsIgnoreCase("SP50")) {
							spVar = "Spouse " + "50%"
						} else if(spouseVar.toString().equalsIgnoreCase("SP100")) {
							spVar = "Spouse " + "100%"
						} else {
							spouseVar = spouseVar.toString().replace("SP", "")
							spVar = "Spouse " + spouseVar
						}
					} else {
						spVar = "EE Only"
					}
	
					documentName = plan + " " + formId + " (" + plVar + " - " + spVar + ") - " + situs
				} else if(documentDetailTypeCode.toString().contains("HI")) {
					plan = "Hospital Indemnity"
	
					if(documentDetailTypeCodes[2] == "NW") {
						situs = "Nationwide"
					} else {
						situs = documentDetailTypeCodes[2]
					}
	
					def choiceCharArray = [] as List
					choiceCharArray = documentDetailTypeCodes[3].toString().toCharArray()
					if(choiceCharArray.size() == 1) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 2) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + " Plan)"
					} else if(choiceCharArray.size() == 3) {
						ch = "(" + chA.get(choiceCharArray.getAt(0).toString()) + "/" + chA.get(choiceCharArray.getAt(1).toString()) + "/" + chA.get(choiceCharArray.getAt(2).toString()) + " Plan)"
					}
	
					if(arrayLength > 4)
					{
						if(documentDetailTypeCodes[4] == "10") {
							provision = "10 day"
						} else if(documentDetailTypeCodes[4] == "31") {
							provision = "31 day"
						} else {
							provision = documentDetailTypeCodes[4] + " day"
						}
					}
					documentName = plan + " " + formId + " " + ch + " - " + situs + " - " + provision
				}
			}
		}
	
		def getDocumentName(contentType, contentDisposition, documentName) {
			def docName
			def fileName
			def fileExtension
			if(documentName) {
				fileExtension = getFileExtension(contentType)
				fileName = getFileNameResponseHeader(contentDisposition)
			}
			docName = (documentName) ? documentName : fileName + fileExtension
		}
	
		def getFileExtension(mimetype) {
			String fileType
			switch (mimetype) {
				case "application/pdf":
					fileType = ".pdf"
					break
				case "image/bmp":
					fileType = ".bmp"
					break;
				case "image/gif":
					fileType = ".gif"
					break
				case "image/jpeg":
					fileType = ".jpeg"
					break
				case "image/jpg":
					fileType = ".jpg"
					break
				case "image/pjpeg":
					fileType = ".pjpeg"
					break
				case "image/png":
					fileType = ".png"
					break
				case "image/tiff":
					fileType = ".tiff"
					break
				case "application/vnd.ms-outlook":
					fileType = ".msg"
					break
				case "application/x-filenet-filetype-msg":
					fileType = ".msg"
					break
				case "message/rfc822":
					fileType = ".eml"
					break
				case "application/vnd.ms-excel":
					fileType = ".xls"
					break
				case "application/vnd.ms-powerpoint":
					fileType = ".ppt"
					break
				case "application/msword":
					fileType = ".doc"
					break
				case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
					fileType = ".pptx"
					break
				case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
					fileType = ".xlsx"
					break
				case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
					fileType = ".docx"
					break
				case "application/octet-stream":
					fileType = ""
					break
				default:
					fileType = ".pdf"
			}
			fileType
		}
	
		def getFileNameResponseHeader(contentDisposition) {
			String fileName
			if(contentDisposition) {
				String[] strArray = contentDisposition.split(';')
				if(strArray.size() == 2)
					fileName = strArray[1].split('=')[1].split('\\.')
			}
			fileName
		}
		
		def getScanResponse(byteContent, workFlow){
			RetrieveScanStatus retrieveScanStatus = new RetrieveScanStatus()
			workFlow.addFacts("content", byteContent)
			retrieveScanStatus.execute(workFlow)
			def finalResponse = workFlow.getFact("response",String.class)
			def clean = finalResponse?.get("clean")
			def message = finalResponse?.get("message")
			if(clean == false && !message.contains("0")){
					throw new GSSPException("INVALID_FILE")
			}
		}
}