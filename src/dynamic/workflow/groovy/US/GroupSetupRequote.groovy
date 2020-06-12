package groovy.US

import java.time.Instant

import org.slf4j.MDC;
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.configuration.GSSPConfiguration
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

import groovy.json.JsonOutput
import groovy.time.TimeCategory
import groovy.time.TimeDuration
/**
 *
 * @author MuskaanBatra/Durgesh Kumar Gupta
 *
 * This class is to submit requote scenarios to Majesco from Group Set up
 *
 */
class GroupSetupRequote implements Task{
	Logger logger = LoggerFactory.getLogger(GroupSetupRequote)
	def static final SERVICE_ID = "GSRQ008"
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def tenantId = requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		def requestBody = workFlow.getRequestBody()
		GroupSetupUtil util = new GroupSetupUtil()
		def groupSetupId = workFlow.getRequestPathParams().get("groupSetUpId")
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetupId.split('_')[0])
		logger.info("GroupSetupRequote : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GroupSetupRequote : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def spiHeadersMap = util.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)

		def getspiHeadersMap = util.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		def config = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_CONFIGURATION, GSSPConfiguration)
		//if(GroupSetupConstants.PROVISIONCHANGE.equalsIgnoreCase(requestBody['requoteType'])){
		//def response = submitRequoteScenario(spiPrefix, registeredServiceInvoker, requestBody,  spiHeadersMap, profile)
		//}
		def requestParams = workFlow.getRequestParams()
		def requestParamsMap = GroupSetupUtil.parseRequestParamsMap(requestParams)
		def agentId
		if(requestParamsMap) {
			agentId= (requestParamsMap?.agentId) ? requestParamsMap?.agentId: "All"
		}
		logger.info("GroupSetupRequote : requestBody : ${requestBody} agentId:::: ${agentId}")
		def requoteServiceVip = workFlow.getEnvPropertyFromContext(GroupSetupConstants.REQOUTEVIP)
		def requoteType=requestBody['requoteType']
		updateRequoteFlag(entityService,requoteType, groupSetupId)
		def responses =updateUnderWritingRequote(gsspRepository,spiPrefix,config, getspiHeadersMap,registeredServiceInvoker,entityService,requestBody,groupSetupId,workFlow,tenantId,requoteServiceVip,agentId,workFlow)
		workFlow.addResponseBody(new EntityResult(responses, true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${groupSetupId} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	def updateRequoteFlag(entityService,requoteType, groupSetupId){
		updateCollection(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,entityService,requoteType, groupSetupId)
		updateCollection(GroupSetupConstants.PER_COLLECTION_NAME,entityService,requoteType, groupSetupId)
	}

	def updateCollection(collection,entityService,requoteType, groupSetupId){
		def result = null
		try{
			EntityResult entResult = entityService?.get(collection, groupSetupId,[])
			result = entResult.getData()
			logger.info("result: ${result}")
			if(result!=null){
				def extension= result?.extension
				extension.putAt("requote", requoteType)
				entityService?.updateById(collection, groupSetupId, result)
			}
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(Exception e){
			logger.error("Error while getting checkDataAvailability by Id ---> "+e.getMessage())
			throw new GSSPException("40001")
		}

	}

	/**
	 * @param spiPrefix
	 * @param registeredServiceInvoker
	 * @param requestPayload
	 * @param spiHeadersMap
	 * @param profile
	 * @return
	 */
	def submitRequoteScenario(spiPrefix, registeredServiceInvoker,requestPayload,spiHeadersMap, String[] profile) {
		def uri="${spiPrefix}/requote"
		def response = null
		def spiResponse = [:] as Map
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)) {
				spiResponse.putAt("result", "true")
				spiResponse.putAt("resultId", "approved")
			}
			else {
				logger.info("submitRequoteScenario() SPI URL to IIB :: spiHeadersMap:${spiHeadersMap} :: SPI URL :: $uri")
				def request = registeredServiceInvoker?.createRequest(requestPayload,spiHeadersMap)
				response = registeredServiceInvoker?.postViaSPI(uri, request,Map.class)
				spiResponse = response?.getBody()
			}
		} catch (e) {
			logger.error("Error while Submiting Requote ---> ${e}")
			throw new GSSPException('GS_NOT_SUBMITTED')
		}
		spiResponse
	}
	/**
	 *
	 * @param entityService
	 * @param requestBody
	 * @return
	 */
	def updateUnderWritingRequote(gsspRepository,spiPrefix,config,getspiHeadersMap,registeredServiceInvoker,entityService,requestBody,groupSetupId,workflow,tenantId,requoteServiceVip,agentId,workFlow){
		GroupSetupUtil util = new GroupSetupUtil()
		def uwPayload=[:] as Map
		def groupSetupData = getGSDraftById entityService,groupSetupId,GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA
		def SIC = groupSetupData?.extension?.sicCode
		def NOE = groupSetupData?.extension.eligibleLives
		def agentDetails = groupSetupData?.extension?.agentDetails
		def RC = groupSetupData?.clientInfo?.basicInfo?.primaryAddress?.state
		def rfpId = groupSetupData?.extension?.rfpId
		def rfpType = (GroupSetupConstants.ADD_PRODUCT.equalsIgnoreCase(groupSetupData?.extension?.rfpType)) ? GroupSetupConstants.ADD_PRODUCT + "_" + rfpId : GroupSetupConstants.NEW_BUSINESS
		def selectedProducts = selectedProducts(groupSetupData?.extension?.dhmoStates, groupSetupData?.extension?.products, config, entityService, rfpType)
		def requestHeader = workFlow.getRequestHeader()
		def metrefId = requestHeader["x-met-rfr-id"]
		logger.info("******Metref Id*****"+metrefId)
		def groupDetails = framGroupDetailsStructure(groupSetupData,agentId,metrefId)
		logger.info("Reqoute groupDetails To UW Data : "+groupDetails)
		if(GroupSetupConstants.PROVISIONCHANGE.equalsIgnoreCase(requestBody['requoteType'])){
			def retriveProductList = retrieveProductListFromIIB(spiPrefix,getspiHeadersMap,registeredServiceInvoker,SIC,NOE,RC)
			def products=IIBproductList(retriveProductList)
			uwPayload.put("selectedProducts", selectedProducts)
			uwPayload.put("products", products)
			uwPayload.put("isSitusStateChanged", false)
			groupDetails.put("isRePlanDesign", true)
		}else{
			uwPayload.put("isSitusStateChanged", true)
			def selectedProduct =[] as List
			uwPayload.put("selectedProducts", selectedProduct)
			def product =[:]
			uwPayload.put("products", product)
		}
		Map groupItems=new HashMap()
		uwPayload.put("group", groupDetails)
		uwPayload.put("brokerDetails",agentDetails)
		logger.info("Reqoute groupItems : "+groupDetails+" uwPayload: $uwPayload, --------> \n groupDetails:$groupDetails, agentDetails:$agentDetails")
//		uwPayload.put(groupItems)
		uwPayload.put("groupNumber", groupSetupData?.extension?.groupNumber)
		util.saveIIBRequestPayload(gsspRepository, ['response':uwPayload], "Requote", groupSetupId)
		def response =callRequoteService(groupSetupData,['response':uwPayload], tenantId, registeredServiceInvoker, requoteServiceVip, workflow,agentId,metrefId)
		return response
	}


	def callRequoteService(groupSetupData, requoteData, tenantId, registeredServiceInvoker, requoteServiceVip,WorkflowDomain workflow, agentId,metrefId) {

		def groupNumber=groupSetupData?.extension?.groupNumber
		def requoteUri = "/v1/tenants/$tenantId/groups/groupsetup/rfp/requote?q=rfpDraftId==${groupNumber}_${metrefId}"
		def Jsonutput=JsonOutput.toJson(requoteData);
		logger.info("Reqoute RequestBody To UW Data : "+Jsonutput +"requoteUri "+requoteUri)
		def response
		try {
			logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling Requote API: ${requoteUri}  ")
			Date start = new Date()
			response =registeredServiceInvoker.post(requoteServiceVip,"${requoteUri}",new HttpEntity(requoteData), Map.class)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> ${groupNumber} === Requote API: ${requoteUri}, Response time : "+ elapseTime)
		} catch (e){
			logger.error(" Exception while calling requote service, createGroups - ${requoteUri} - ${e.getMessage()}", e)
			//throw new GSSPException("400013")
		}
		return response?.getBody()
	}

	def selectedProducts(dhmoStates, products, config, entityService, rfpType){
		def selectedProducts = [] as List
		def productNameMap = getProductNameFromDB(entityService)
		for(def prod: products){
			def prodRFPType = prod?.rfpType
			if(!prodRFPType.equalsIgnoreCase(rfpType))
				continue
			def product =[:] as Map
			def productID = translateProductCode(prod?.productCode)
			//			product.put("name", getConfigValue(productID,config,"US", 'ref_products_nameCode','en_US'))
			product.put("name",productNameMap.getAt(productID))
			product.put("id", productID)
			if(productID.equals("201") || productID =="201") {
				String [] states=dhmoStates.split(',')
				logger.info(" Dhmo States "+states)
				List applicableCode=new ArrayList()
				for( String state : states) {
					applicableCode.add(state)
				}
				product.put("applicablePlanStateCodes", applicableCode)
			}
			selectedProducts.add(product)
		}
		logger.info("selectedProducts : "+selectedProducts)
		return selectedProducts
	}

	def translateProductCode(String productCode) {
		String productName
		switch(productCode){
			case 'DHMO' :
				productName = '201'
				break
			case 'DPPO' :
				productName = '202'
				break
			case 'VIS' :
				productName = '704'
				break
			case 'BSCL' :
				productName = '105'
				break
			case 'BSCLD' :
				productName = '106'
				break
			case 'OPTL' :
				productName = '107'
				break
			case 'OPTLD' :
				productName = '108'
				break
			case 'STD' :
				productName = '402'
				break
			case 'LTD' :
				productName = '403'
				break
			case 'ACC' :
				productName = '502'
				break
			case 'CIAA' :
				productName = '601'
				break
			case 'HI' :
				productName = '504'
				break
			case 'LGL' :
				productName = '1003'
				break
			default :
				productName = productCode
				logger.error "Invalid product Code....${productCode}"
				break
		}
		productName
	}

	def getProductNameFromDB(entityService)
	{
		def result = null
		try{
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GS_PRODUCTCODE_NAME, "product_code_name",[])
			result = entResult.getData()
		}catch(any){
			logger.error("Error while getting product names from mongoDB :  "+ any)
			throw new GSSPException("40001")
		}
		result
	}


	def getConfigValue(keycode, config, tenantId, configurationId, locale){
		def billMethod_map=[:]

		keycode = keycode.toString()
		if(billMethod_map[keycode]){
			billMethod_map[keycode]
		}else {
			def statusMapping = config.get(configurationId, tenantId, [locale : locale])
			billMethod_map = statusMapping?.data
			billMethod_map[keycode]
		}
	}

	def IIBproductList(retriveProductList){
		def Jsonutput=JsonOutput.toJson(retriveProductList);
		logger.info("IIB Retrieve List : "+Jsonutput)

		def eligibleProduct =[:] as Map
		def eligible = [] as List
		for(def retriveProduct : retriveProductList?.items){
			def item = retriveProduct?.item
			eligible.add(item)
		}
		logger.info("Eligible Product : "+eligible)
		eligibleProduct.put("eligible", eligible)
		return eligibleProduct
	}
	def getGSDraftById(entityService, groupId,collectionName) {
		def result=null
		try{
			logger.info  "GetGroupSetupDetails : collection Name :: ${collectionName}, entityService:${entityService}, groupId:${groupId}"
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupId,[])
			result=entResult.getData()
		}catch(any){
			logger.error("Error getting draft Group Set UP Data  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		result
	}


	def retrieveProductListFromIIB(spiPrefix,getspiHeadersMap,registeredServiceInvoker,SIC,NOE,RC){
		def uri = "${spiPrefix}/products?q=SIC==${SIC};eligibleLivesCount==${NOE};regionCode==${RC}"
		logger.info("getGSClientsList() :: SPI URL -> ${uri}")
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		def serviceUri = uriBuilder.build(false).toString()

		def response
		try {
			logger.info("${SERVICE_ID} ----> Calling ${uri} API ")
			Date start = new Date()
			response = registeredServiceInvoker?.getViaSPI(serviceUri, Map.class, [:], getspiHeadersMap)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> ${uri} API Response time : "+ elapseTime)
			//			GroupSetupUtil util= new GroupSetupUtil()
			//			response= util.getMockData("products.json");
			logger.info("getGSClientsList() spiResponseContent: ${response}")
			if(response){
				response = response?.getBody()
			}

			else{
				throw new GSSPException('400013')
			}
		}
		catch(e) {
			logger.error("Exception occured while executing SPI Request ---> :  "+e)
			throw new GSSPException("400013")
		}
		response
	}
	def formSelectedProductList(productList){
		def selectedProducts=[] as List
		def productSelected=[:] as Map
		productSelected.put("name", "")
		productSelected.put("id", "")

		selectedProducts.add(productSelected)
		selectedProducts
	}

	def framGroupDetailsStructure(groupSetupData,agentId,metrefId){
		def groupDetails=[:] as Map
		groupDetails.put("number", groupSetupData?.extension?.groupNumber)
		groupDetails.put("rfpId", groupSetupData?.extension?.rfpId)
		groupDetails.put("name", groupSetupData?.extension?.companyName)
		def rfpType=groupSetupData?.extension?.rfpType
		if(rfpType.equalsIgnoreCase("NEWBUSINESS"))
		  groupDetails.put("isMaintenance",false )
		else
		  groupDetails.put("isMaintenance",true )
		
		/*if(agentId!=null && !agentId.equals("All")){
		 //groupDetails.put("agentId", agentId)
		 }else{
		 //groupDetails.put("agentId", "")
		 }*/
		// As part of Underwriting request to pass metref id
		groupDetails.put("agentId", metrefId)
		groupDetails.put("roleType", "")
		groupDetails.put("isUSCitizen", groupSetupData?.extension?.isUSCitizen)
		groupDetails.put("isTPAResponsible", "")
		groupDetails.put("entryDate", groupSetupData?.extension?.entryDate)
		groupDetails.put("secondarySIC",groupSetupData?.extension?.secondarySIC)
		groupDetails.put("createdDate", groupSetupData?.extension?.createdDate)
		groupDetails.put("yearStarted", groupSetupData?.extension?.yearStarted)
		def branches = [] as List
		def address = [:] as Map
		address =  groupSetupData?.clientInfo?.basicInfo?.primaryAddress
		address.putAt("number", "")
		address.putAt("typeCode", "")
		address.putAt("postalCode", groupSetupData?.clientInfo?.basicInfo?.primaryAddress?.zipCode)
		address.putAt("stateCode", groupSetupData?.clientInfo?.basicInfo?.primaryAddress?.state)
		address.putAt("countryCode", "")
		address.putAt("countryCode", "")
		def adds =['address':address]
		branches.add(adds)
		groupDetails.put("branches", branches)
		groupDetails.put("self", "")
		groupDetails.put("createdDate", "")
		groupDetails.put("industryCode", "")
		def phoneNumbers = [] as List
		def phoneNumber = [:] as Map
		phoneNumber.put("countryCode", "")
		phoneNumber.put("number", "")
		phoneNumber.put("typeCode", "")
		phoneNumber.put("useCode", "")
		phoneNumber.put("isPrimary", true)
		groupDetails.put("phoneNumbers", phoneNumbers)
		groupDetails.put("status", "")
		def extension =[:] as Map
		extension.put("referenceNumber", '')
		extension.put("eligibleLivesCount", groupSetupData?.extension?.eligibleLives)
		extension.put("primarySIC", groupSetupData?.extension?.sicCode)
		extension.put("secondarySIC", groupSetupData?.extension?.secondarySIC)
		extension.put("regionCode", '')
		extension.put("rfpEffectiveDate", groupSetupData?.clientInfo?.basicInfo?.effectiveDate)
		def governmentIssuedIds = [] as List
		def governmentIssuedId = [:] as Map
		governmentIssuedId.put("typeCode", '')
		governmentIssuedId.put("value", groupSetupData?.clientInfo?.basicInfo?.federalTaxId)
		governmentIssuedIds.add(governmentIssuedId)
		extension.put("governmentIssuedId", governmentIssuedIds)
		extension.put("entryDate", groupSetupData?.extension?.entryDate)
		extension.put("effectiveDate", groupSetupData?.clientInfo?.basicInfo?.effectiveDate)
		extension.put("rfpDeadlineDate", '')
		extension.put("dunsNumber", '')
		extension.put("yearStarted", groupSetupData?.extension?.yearStarted)
		extension.put("companyName", groupSetupData?.extension?.companyName)
		extension.put("dnbRating", '')
		extension.put("recentlyFilingDate", '')
		extension.put("naic", '')
		extension.put("bankruptcyNoOfFilings", '')
		extension.put("locationType", '')
		extension.put("isUSCitizen", '')
		groupDetails.put("extension",extension)
		groupDetails
	}
}
