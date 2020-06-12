package groovy.US

import java.time.Instant

import org.slf4j.MDC
import org.springframework.data.mongodb.core.query.Criteria
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
import com.metlife.service.TokenManagementService
import com.metlife.service.entity.EntityService

import groovy.json.JsonOutput
import groovy.time.TimeCategory
import groovy.time.TimeDuration

class GenerateMasterApp implements Task {
	Logger logger = LoggerFactory.getLogger(GenerateMasterApp)
	def static MasterAppDBKeyList
	String documentName
	def static final SERVICE_ID = "GMA009"
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		logger.error("GenerateMasterApp Service :: execute() :: Start ")
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def config = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_CONFIGURATION, GSSPConfiguration)
		def securityScan = workFlow.getEnvPropertyFromContext("securityScan")
		def profile = workFlow.applicationContext.environment.activeProfiles
		MasterAppDBKeyList = new HashMap()
		documentName = new String("")
		GroupSetupUtil util = new GroupSetupUtil()
		def aePrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.AE_Prefix)
		def groupSetupId = workFlow.getRequestPathParams().get("groupSetUpId")
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetupId.split('_')[0])
		logger.info("GenerateMasterApp : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GenerateMasterApp : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def request=workFlow.getRequestBody()
		def requestBody=request['masterAppSignature']
		def spiPrefix=workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		String module
		def requestParamsMap = GroupSetupUtil.parseRequestParamsMap(workFlow.getRequestParams())
		if(requestParamsMap) {
			module = (requestParamsMap?.moduleName) ? requestParamsMap?.moduleName : ""
		}
		def getDataFromDb= getGSDraftById entityService,groupSetupId,GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA
		def masterAppPayloads =[:] as Map
		def spiHeader = util.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)
		def response
		if(module.equalsIgnoreCase("unsigned")){
			def formNameKeys
			def acoountExecutiveId
			def noOfLives
			String state
			String productCode
			def productList
			if(getDataFromDb) {
				productList = getDiFFProductList(entityService, getDataFromDb?.extension?.products,groupSetupId)
				 productCode=getDataFromDb?.extension?.products?.productCode[0]
				state = getDataFromDb?.clientInfo?.basicInfo?.primaryAddress?.situsState
				noOfLives = ""+getDataFromDb?.extension.eligibleLives
				acoountExecutiveId = getDataFromDb?.extension?.accountExecutive?.id
				formNameKeys = getFormNameList(state,noOfLives, productList,getDataFromDb)
			}
			def masterAppEformKeys = getEformName(formNameKeys)
			logger.info("Form Name Keys :::" +formNameKeys + "masterAppEformKeys :::" +masterAppEformKeys)
			def licenseNumber
			if(acoountExecutiveId) {
				def tokenService = workFlow.getBeanFromContext(GroupSetupConstants.TOKENMANAGEMENTSERVICE,TokenManagementService.class)
				def token=tokenService.getToken()
				def header =[:]
				header << ['x-ibm-client-Id': workFlow.getEnvPropertyFromContext('apmc.clientId'),
					'Authorization':token,'x-spi-service-id': workFlow.getEnvPropertyFromContext('apmc.serviceId'),
					'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('apmc.tenantId')]

				licenseNumber=aeLicenseNumber(header,aePrefix,registeredServiceInvoker,acoountExecutiveId,state,productCode)
			}
			else {
				licenseNumber=""
			}
			if(getDataFromDb)
			masterAppPayloads= generatePayloadsForDocGen(state,config,masterAppEformKeys,getDataFromDb,requestBody,productList,licenseNumber)
			util.saveIIBRequestPayload(gsspRepository, masterAppPayloads, "MasterApp-Unsigned", groupSetupId)
			insertItIntoDatabase(entityService,groupSetupId,masterAppPayloads,requestBody,GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA)
			insertItIntoDatabase(entityService,groupSetupId,masterAppPayloads,requestBody,GroupSetupConstants.PER_COLLECTION_NAME)
		}else{
			if(getDataFromDb)
			masterAppPayloads = mapRequestBody(getDataFromDb?.masterApp, requestBody)
			util.saveIIBRequestPayload(gsspRepository, masterAppPayloads, "MasterApp-Signed", groupSetupId)
			insertItIntoDatabase(entityService,groupSetupId,masterAppPayloads,requestBody,GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA)
			insertItIntoDatabase(entityService,groupSetupId,masterAppPayloads,requestBody,GroupSetupConstants.PER_COLLECTION_NAME)
		}

		def form = masterAppPayloads?.item?.forms
		def fileData
		//added by Muskaan -->errorMessage when No form is existing for situs state , No. of eligible lives and Product combination.
		if(form){
			response=submitMasterAppRequest(registeredServiceInvoker,spiPrefix,spiHeader,masterAppPayloads,entityService,groupSetupId, module)
			def masterAppContent = response?.masterAppPdf
			if(securityScan?.equalsIgnoreCase("true") && masterAppContent){
				getScanResponse(masterAppContent, workFlow)
			}
			fileData = ['content' : masterAppContent]
			fileData.formatCode = 'PDF'
			fileData.name = documentName+"_.pdf"
			
		}else{
			response = "No Master App is generated for this combination."
			fileData = ['errorMessage' : response]
		}
		def responseArray = [] as Set
		def responseMap = ['files' : responseArray]
		responseArray.add(fileData)
		util.saveIIBRequestPayload(gsspRepository, responseMap, "MasterAppResponse", groupSetupId)
		if(responseMap) {
			workFlow.addResponseBody(new EntityResult(['Details': responseMap],true))
		}else{
			workFlow.addResponseBody(new EntityResult(['Details': ""],true))
		}
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

	def mapRequestBody(masterApp, requestBody){
		def item =masterApp?.item
		item<< ["city": requestBody['city']]
		item<< ["state":requestBody['state']]
		item<< ["date": requestBody['date']]
		item<< ["time": requestBody['time']]
		item<< ["createdDate": GroupSetupUtil.getESTTimeStamp()]
		item<< ["nameOfApplicant": requestBody['name']]
		item<< ["titleOfAuthorizedSignature": requestBody['acceptedBy']]
		masterApp <<["item": item]
		return masterApp
	}
	def submitMasterAppRequest(registeredServiceInvoker,spiPrefix,spiHeader,masterAppPayloads,entityService ,groupSetupId, module){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		spiPrefix = "${spiPrefix}/generatemasterapp"
		MDC.put(GroupSetupConstants.SUB_API_NAME, spiPrefix);
		def pdfresponse
		def response
		try {
			def json= JsonOutput.toJson(masterAppPayloads)
			logger.error("MasterApp Request Body --->${json}")
			logger.info("${SERVICE_ID} ----> ${groupSetupId} ==== Calling MasterApp API ")
			Date start = new Date()
			def request= registeredServiceInvoker.createRequest(masterAppPayloads,spiHeader)
			response = registeredServiceInvoker.postViaSPI(spiPrefix, request, Map.class)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> ${groupSetupId} == ${module} == MasterApp API Response time : "+ elapseTime)
			/*GroupSetupUtil util= new GroupSetupUtil()
			 response= util.getMockData("document.json");
			 def documentId ="1234"*/
			pdfresponse =response?.body
			//logger.error("Response Body --->${response}")
			def documentId =response?.body?.documentId
			documentName = response?.body?.documetName
			updateDocumentId(entityService,documentId ,groupSetupId)

		}catch(any) {
			logger.error("Error Occured while submit--->${any.getMessage()}")
			throw new GSSPException('400013')
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		return pdfresponse
	}
	def updateDocumentId(entityService,documentId ,groupSetupId){
		MDC.put("GET_AND_UPDATE_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetupId,[])
		def groupSetupData=entResult.getData()
		def masterApp = groupSetupData?.masterApp
		def item = masterApp?.item
		item << ['documentId':documentId]
		masterApp << ['item':item]
		groupSetupData << ['masterApp':masterApp]
		entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetupId, groupSetupData)
		MDC.put("GET_AND_UPDATE_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}

	def insertItIntoDatabase(entityService,groupSetUpId,masterAppPayloads,requestBody,collectionName){
		MDC.put("GET_AND_UPDATE_E_SIGN_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try{
			Criteria criteria = Criteria.where("_id").is(groupSetUpId)
			def datas= entityService.listByCriteria(collectionName, criteria)
			if(datas.size()!=0){
				def item = ['item':masterAppPayloads?.item]
				def modifiedData= ['masterApp':item]
				def masterAppSignature= ['masterAppSignature':requestBody]
				entityService.updateById(collectionName, groupSetUpId, modifiedData)
				entityService.updateById(collectionName, groupSetUpId, masterAppSignature)
			}
		}catch(AppDataException e){
            logger.error("Data not found ---> ${e.getMessage()}")
        }catch(any){
			logger.error("Error Occured in saveAsDraft--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		MDC.put("GET_AND_UPDATE_E_SIGN_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}
	def generatePayloadsForDocGen(state,config,masterAppEformList,getDataFromDb,requestBody, productList,licenseNumber){
		GroupSetupCommonUtil gsCommonUtil = new GroupSetupCommonUtil()// Util object for conversion of product and states
		def payloads=createGenralizePayload(getDataFromDb, requestBody,licenseNumber,gsCommonUtil)
		def formData=createFormPayload(state,config,masterAppEformList,getDataFromDb,productList,gsCommonUtil)
		payloads.putAt("forms", formData)
		def links= [:]
		links.put('link','')
		payloads.put("links", links)
		def metadata =[:]
		metadata.put("count",0)
		metadata.put("limit",0)
		metadata.put("offset",0)
		payloads.putAll('metadata':metadata)
		def item =['item':payloads]
		return item
	}

	def createGenralizePayload(getDataFromDb, requestBody,licenseNumber, GroupSetupCommonUtil gsCommonUtil){

		def item =[:] as Map
		item.put("groupNumber", getDataFromDb?.extension.groupNumber)
		item.put("documentId", "")
		item.put("eligibleLives", getDataFromDb?.extension.eligibleLives)
		if((getDataFromDb?.clientInfo.basicInfo.legalGroupName.isEmpty())){
			item.put("OrganizationLegalName", getDataFromDb?.extension.companyName)
			item.put("groupName", getDataFromDb?.extension.companyName)
		}else{
			item.put("OrganizationLegalName", getDataFromDb?.clientInfo.basicInfo.legalGroupName)
			item.put("groupName", getDataFromDb?.clientInfo.basicInfo.legalGroupName)
		}
		item.put("federalTaxId", getDataFromDb?.clientInfo.basicInfo.federalTaxId)
		item.put("contact", getDataOfAccountExecutive(getDataFromDb?.groupStructure.contact))

		//item.put("policySitus", getDataFromDb?.clientInfo.basicInfo.primaryAddress?.situsState)

		def address=getDataFromDb?.clientInfo.basicInfo.primaryAddress
		//address.putAt("state", gsCommonUtil.getStateName(address?.state))// Converting state
		address.putAt("state", address?.state) //Defect for State name to short name
		address.putAt("policySitus", gsCommonUtil.getStateName(address?.situsState))// Converting Situs State
		address.remove("situsState")

		item.put("addressGlobal", address)
		item.put("city", requestBody['city'])
		item.put("state", requestBody['state'])
		item.put("date", requestBody['date'])
		item.put("time", requestBody['time'])
		item.put("nameOfApplicant", requestBody['name'])
		item.put("titleOfAuthorizedSignature", requestBody['acceptedBy'])
		item.put("effectiveDateGlobal", getDataFromDb?.clientInfo.basicInfo.effectiveDate)
		item.put("monthlyGLOBAL", "1")
		item.put("quarterlyGLOBAL", "0")
		item.put("annuallyGLOBAL", "0")
		item.put("otherGLOBAL", "0")
		item.put("otherDetailsGLOBAL", "")
		def premiumPay=getDataFromDb?.billing?.premiumPayment
		if(premiumPay.equalsIgnoreCase("Yes")) { //#66306 as part of online fix
			def premiumAmt=getDataFromDb?.billing.premiumAmount
			item.put("advancedPaymentGLOBAL", premiumAmt)
		}
		else if(premiumPay.equalsIgnoreCase("No"))
			item.put("advancedPaymentGLOBAL", "0")
		else if(premiumPay.equalsIgnoreCase("") || premiumPay.isEmpty())
			item.put("advancedPaymentGLOBAL", "")//#defect 66073 as part of offline fix
			
		item.put("createdDate", GroupSetupUtil.getESTTimeStamp())

		def accountExecutive=[:] as Map
		accountExecutive=getDataFromDb?.extension.accountExecutive
		accountExecutive.putAt("printTypeCode","UPLDFINPKG")
		accountExecutive.putAt("licenseNumber",licenseNumber)
		item.put("accountExecutive", accountExecutive)
		logger.error("Create Genralize Payload::"+ item)
		return item
	}

	def getDataOfAccountExecutive(contacts){
		def contactInfo=[:] as Map
		for (def contact: contacts){
			for (def role : contact?.roleTypes){
				if("Executive Contact".equalsIgnoreCase(role?.roleType)){
					contactInfo.put("firstName", contact?.firstName)
					contactInfo.put("lastName", contact?.lastName)
					break
				}
			}
		}
		return contactInfo
	}
	def createFormPayload(state,config,masterAppEformList,getDataFromDb,productList,GroupSetupCommonUtil gsCommonUtil){
		logger.error("Create Form Payload:: ")
		logger.error("Create Form Payload State:: "+state)
		logger.error("Create Form Payload masterAppEformList:: "+masterAppEformList)
		logger.error("Create Form Payload productList:: "+productList)
		logger.error("Create Form Payload getDataFromDb:: "+getDataFromDb)
		def formData=[] as List
		boolean flag= false
		for(def prod: productList){
			if("ACC".equalsIgnoreCase(prod?.productCode)){
				flag= true
			}
		}
		masterAppEformList.each{ key, products->
			def form = [:] as Map

			form.put("formName",key)
			form.put("employeesMembersCoverage", gsCommonUtil.getConvertedProductNameList(products))// Converting product
			form.put("dependentMembersCoverage", dependentMemberCoverage(productList,products,gsCommonUtil))
			String formName=key;


			if("NY".equalsIgnoreCase(state) && flag){
				form.put("stateFooter", getConfigValue(state+"_"+key,config,"US", 'ref_group_setup_MasterApp','stateFooter'))
				//Defect 64253
				form.put("fraudWarning", getConfigValue(state+"_"+key,config,"US", 'ref_group_setup_MasterApp','fraudWarning'))
			}else{
				form.put("stateFooter", '')
				//				form.put("fraudWarning", '')
			}
			if(formName.endsWith("NW")){
				form.put("stateFooter", state) // AS part of defect fix 67517, Nationwide Form footer changed from 'NW' to Situs state
//				form.put("stateFooter", getConfigValue("NW_"+key,config,"US", 'ref_group_setup_MasterApp','stateFooter'))
//				form.put("fraudWarning", getConfigValue("NW_"+key,config,"US", 'ref_group_setup_MasterApp','fraudWarning'))
			}
			else{
				form.put("stateFooter", getConfigValue(state+"_"+key,config,"US", 'ref_group_setup_MasterApp','stateFooter'))
				//form.put("stateFooter", '')
			}
			if(formName.endsWith("UT")){
				form.put("stateFooter", getConfigValue(state+"_"+key,config,"US", 'ref_group_setup_MasterApp','stateFooter'))
				form.put("fraudWarning", getUTFraudWarning(products))
			}
			if("GAPP13-02-NY".equalsIgnoreCase(key) || "GAPP13-02-NY-2".equalsIgnoreCase(key)){
				if(products.contains("HI")){
					//Defect 64253 change 1 to Y
					form.put("hospitalIndemnityGroupComprehensive", "Y")
					form.put("hospitalIndemnitySupplementGroupComprehensive", "Y")

				}else{
					form.put("hospitalIndemnityGroupComprehensive", "")
					form.put("hospitalIndemnitySupplementGroupComprehensive", "")

				}
				if(products.contains("ACC")){
					//Defect 64253 change 1 to Y
					form.put("accidentGroupComprehensive", "Y")
					form.put("accidentSupplementGroupComprehensive", "Y")

				}else{
					form.put("accidentGroupComprehensive", "")
					form.put("accidentSupplementGroupComprehensive", "")

				}

			}else if("GAPP16-02-NH_2".equalsIgnoreCase(key)){
				form.put("partTimeEmployeesMembersCoverage", [])
				form.put("partTimeDependentMembersCoverage", [])
				form.put("specialMessageNHDV",getSpecialMessageForNH(products))
				form.put("specialMessage", getSpecialMessageAXHICI(products))
				form.put("notes",getSpecialMessageForNH(products))
			}else if("GAPP17-02-CO".equalsIgnoreCase(key)){ 
				//Defect 67518 changes from GAPP13-02-CO to GAPP17-02-CO and below field values 1 to Y
				form.put("stateFooter", "CO")
				if(getSpecialMessageAXHICI(products).equals("YES")){
					form.put("minEssentialCoverage", "Y")
				}else{
					form.put("minEssentialCoverage", "")
				}
				if(products.contains("DPPO")){
					form.put("ehbLanguageDental", "Y")
				}else{
					form.put("ehbLanguageDental", "")
				}
			}else if("DHMO-METCO-NJ_1".equalsIgnoreCase(key)){
				form.put("initialApplication", "0")
				form.put("rateGurantee", rateGaurantee(productList))
				form.put("customerType", "1")
				form.put("multiSiteLocationCovered", getMultiSiteLocation(getDataFromDb?.groupStructure?.locations))
				form.put("primaryBenefitAdministrator", getPrimaryBenefitAdmin(getDataFromDb))
			}
			form.put("stateFooter", state)
			formData.add(form)
		}
		logger.error("Create Form Payload formData:: "+formData)
		return formData
	}
	def getMultiSiteLocation(locations){
		def multiSiteLocations =[] as List
		locations.each(){location->
			def activeParticipant=location?.activeParticipants
			if("No".equalsIgnoreCase(activeParticipant)){
				def locationName=location?.locationName
				def multiLocation=[:] as Map
				multiLocation.put("locationName", locationName)
				multiSiteLocations.add(multiLocation)
			}
		}
		return multiSiteLocations
	}
	def getPrimaryBenefitAdmin(getDataFromDb){
		def primaryBenefitAdmin=[:] as Map
		def buildCaseStructures =getDataFromDb?.groupStructure?.buildCaseStructure
		for(def buildCaseStructure: buildCaseStructures){
			def locations = buildCaseStructure?.location
			if("Yes".equalsIgnoreCase(locations?.isPrimaryAddress)){
				primaryBenefitAdmin =primaryBenifitAdministrator(buildCaseStructure?.contacts)
				break
			}
		}
		primaryBenefitAdmin
	}
	def getSpecialMessageAXHICI(products){
		if(products.contains("HI") || products.contains("ACC") || products.contains("CIAA")){
			return "YES"
		}
		return "NO"
	}
	def getSpecialMessageForNH(products){
		if(products.contains("DPPO") && products.contains("VIS") && products.size() == 2){
			return "Dental_Vision_both"
		}else if(products.contains("DPPO") && !(products.contains("VIS")) && products.size() > 1){
			return "Dental_included"
		}else if(!products.contains("DPPO") && (products.contains("VIS"))&& products.size() > 1){
			return "Vision_included"
		}else {
			return "Dental_Vision_neither"
		}


	}
	def getUTFraudWarning(products){

		if(products.contains("DPPO") && products.contains("VIS") && products.size() > 2){
			return "Dental_Vision_both"
		}else if(products.contains("DPPO") && !(products.contains("VIS")) && products.size() == 1){
			return "Dental_only"
		}else if(products.contains("DPPO") && !(products.contains("VIS")) && products.size() > 1){
			return "Dental_included"
		}else if(products.contains("DPPO") && (products.contains("VIS")) && products.size() == 2){
			return "Dental_Vision_only"
		}else if(!products.contains("DPPO") && (products.contains("VIS"))&& products.size() == 1){
			return "Vision_only"
		}else if(!products.contains("DPPO") && (products.contains("VIS"))&& products.size() > 1){
			return "Vision_included"
		}else {
			return "Dental_Vision_neither"
		}
	}
	def rateGaurantee(productList){
		def rateGuarantee
		for(def product : productList){
			if("DHMO".equals(product?.productCode))
			{
				for (def provision: product?.provisions){
					if(("rateGurantee".equalsIgnoreCase(provision?.provisionName))){
						rateGuarantee=provision?.provisionValue
					}
				}
			}
		}
		return rateGuarantee
	}

	def primaryBenifitAdministrator(contacts){
		def primaryContact =[:] as Map
		contacts.each(){contact->
			def roleTypes=contact?.roleTypes
			roleTypes.each(){ roleType ->
				if("Benefit Administrator".equalsIgnoreCase(roleType?.roleType )){
					primaryContact.putAt("firstName", contact?.firstName)
					primaryContact.putAt("lastName", contact?.lastName)
					primaryContact.putAt("title","" )
					primaryContact.putAt("phoneNumber", contact?.workPhone)
					primaryContact.putAt("extension", "")
					primaryContact.putAt("emailAddress", contact?.email)
					primaryContact.putAt("fax", contact?.fax)
				}
			}
		}
		return primaryContact
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
	def dependentMemberCoverage(productList, products, GroupSetupCommonUtil gsCommonUtil){

		def dependentProductList =[] as Set
		for(def key: products){
			def flag = true
			for(def product : productList){
				if(key.equals(product?.productCode))
				{
					for (def provision: product?.provisions){
						if(("TIERTYPE".equalsIgnoreCase(provision?.provisionName) && Integer.parseInt((provision?.provisionValue).getAt(0))>=2 && flag)){
							dependentProductList.add(gsCommonUtil.translateProductCode(product?.productCode))// Converting product
							flag = false
						}
						if("BSCLD".equalsIgnoreCase(product?.productCode)||"OPTLD".equalsIgnoreCase(product?.productCode)){
							dependentProductList.add(gsCommonUtil.translateProductCode(product?.productCode))// Converting product
						}
					}
				}
			}
		}

		return dependentProductList
	}

	def getEformName(formNameKeys){
		def MapListWithProduct =[:] as Map
		formNameKeys.each{formK, formNam ->

			formNam.each{formName, product ->
				def key =[] as List
				key =MapListWithProduct.get(formName)
				if(key){
					key.add(product)
					MapListWithProduct.put(formName, key)
				}else{
					def list =[] as List
					list.add(product)
					MapListWithProduct.put(formName, list)
				}
			}

		}
		logger.info("Map List With Product "+ MapListWithProduct)
		return MapListWithProduct
	}

	def getDiFFProductList(entityService, productList,groupSetupId){
		ArrayList list= Arrays.asList(productList)
		try {
			logger.info("Products list : ${productList}")
			EntityResult entResult=entityService?.get(GroupSetupConstants.PER_COLLECTION_NAME, groupSetupId,[])
			def declinedProducts=entResult.getData()?.declinedProducts
			logger.info("declinedProducts list : ${declinedProducts}")
			for(int i=0; i<list.size();i++){
				def productCode = list[i]?.productCode
				// Commented below code to send dependentMembersCoverage details for BSCL & OPTLD 
				/*if(productCode == "BSCLD" || productCode == "OPTLD"){
					list.remove(i)
					i--
					continue
				}*/
				// Removing product if product is declined product.
				for(def prod:declinedProducts){
					if(productCode.equalsIgnoreCase(prod?.productName)){
						list.remove(i)
						break
					}
				}
			}
			logger.info("Diff Product List"+ list)
			}catch(AppDataException e){
				logger.error("Data not found ---> ${e.getMessage()}")
			}catch(any){
				logger.error("Error Occured in getDiFFProductList--->${any.getMessage()}")
				throw new GSSPException("40001")
			}
			return list
	}
	def getFormNameList(state, noOfLives, productList,getDataFromDb){
		def keySets= [:] as Map
		String dhmoStates=getDataFromDb?.extension?.dhmoStates
		if(generalStates(state)){
			keySets=generateMasterAppKeyListForGS(state, productList,noOfLives)
		}/*else if("WV".equalsIgnoreCase(state)){
		 keySets = generateMasterAppKeyListForWV(state, productList,noOfLives)
		 }*/else if("UT".equalsIgnoreCase(state)){
			keySets = generateMasterAppKeyListForGS(state, productList,noOfLives)
		}/*else if("ND".equalsIgnoreCase(state)){
		 keySets =generateMasterAppKeyListForND(state, productList,noOfLives,getDataFromDb?.groupStructure?.classDefinition)
		 }*/else if("FL".equalsIgnoreCase(state)){
			keySets = generateMasterAppKeyListForFL(state, productList,noOfLives)
		}else{
			keySets = generateMasterAppKeyListForGS("NW", productList,noOfLives)
		}

		if(dhmoStates){
			boolean moreThanOneDhmoState= dhmoStates.contains(',')
			String [] dhmoFormStates
			if(moreThanOneDhmoState){
				dhmoFormStates = dhmoStates.split(',')
			}else{
				dhmoFormStates= new String[1]
				dhmoFormStates[0]=dhmoStates
			}
			keySets = generateMasterAppKeyListForDHMO(dhmoFormStates,keySets, productList,noOfLives)
		}

		logger.error("getFormNameList Logger"+ keySets)
		keySets
	}

	def generalStates(state){
		def generalStateList=['AL', 'AR', 'DC', 'LA', 'NM', 'OH', 'RI', 'ME', 'TN', 'WA', 'CO', 'KS', 'KY', 'MA', 'MD', 'NJ', 'NY', 'OK', 'OR', 'PR', 'VA', 'VT', 'NH', 'WV'] as List
		if(generalStateList.contains(state))
			return true
		return false
	}

	def getGSDraftById(entityService, groupId,collectionName) {
		def result=null
		try{
			logger.error  "GetGroupSetupDetails :: getGSDraftById() :: collection Name :: ${collectionName}, entityService:${entityService}, groupId:${groupId}"
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupId,[])
			result=entResult.getData()
			EntityResult entResultforMasterKey = entityService?.get(GroupSetupConstants.COLLECTION_GS_MASTER_APP_EFORMS, "1234",[])
			MasterAppDBKeyList= entResultforMasterKey.getData()
		}catch(AppDataException e){
			logger.error("Data not found ---> ${e.getMessage()}")
		}catch(any){
			logger.error("Error getting draft Group Set UP Data  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		result
	}

	def generateMasterAppKeyListForGS(state, productList, noOfLives) {
		def keyList = [:] as Map
		for(def prod: productList){
			def productCode= prod?.productCode
			def key= state+"_"+noOfLives+"_"+productCode
			def formName=generateMasterAppKeyGS(state, productCode, noOfLives)
			if(formName)
				keyList.put(key, formName)
		}
		keyList
	}

	def generateMasterAppKeyListForWV(state, productList, noOfLives) {
		def keyList = [:] as Map
		def keyForHi =[:] as Map
		def flag= false
		for(def prod: productList){
			def productCode= prod?.productCode
			def key= state+"_"+noOfLives+"_"+productCode
			def formName=generateMasterAppKeyWV(state, productCode, noOfLives)
			if(formName && formName.equalsIgnoreCase("GAPP13-02-WV(with Hospital)")){
				keyForHi.put(key, formName)
				flag=true
			}
			else if(formName){
				keyList.put(key, formName)
			}
		}
		if(flag){
			keyForHi
		}else{
			keyList
		}
	}

	def generateMasterAppKeyListForDHMO(states,keySets,productList, noOfLives) {
		//		def keyList = [:] as Map

		for(def state :states) {
			for(def prod: productList){
				def formName
				def productCode= prod?.productCode
				def key= state+"_"+noOfLives+"_"+productCode

				if(productCode.equals("DHMO")) {
					formName=generateMasterAppKeyDHMO(state, productCode, noOfLives)
				}
				if(formName!=null)
					keySets.putAt(key, formName)
			}
		}

		keySets
	}

	def generateMasterAppKeyDHMO(state,productCode, noOfLives){
		def eformName =[:] as Map
		def productListofTenPlus=['DHMO'] as List
		int noOfLife= Integer.parseInt(noOfLives)
		if(noOfLife >= 2 && productListofTenPlus.contains(productCode)){
			def eformKey= state+"_2_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		return eformName
	}


	def generateMasterAppKeyListForND(state, productList, noOfLives, classDefinions) {
		def keyList = [:] as Map
		def newkeyList = [:] as Map
		def flag=false
		def productListofNewCondition=['HI', 'CIAA'] as List
		for(def prod: productList){
			def productCode= prod?.productCode
			def key= state+"_"+noOfLives+"_"+productCode
			def formName
			if(productListofNewCondition.contains(productCode)){
				formName=generateMasterAppKeyNDForAdditionalCondition(state, productCode, noOfLives,classDefinions)
				if(formName)
					flag = true
				newkeyList.put(key, formName)
			}
			else{
				formName=generateMasterAppKeyND(state, productCode, noOfLives)
				if(formName){
					keyList.put(key, formName)
				}
			}
		}
		if(flag) {
			return newkeyList
		}else{
			return keyList
		}
	}

	def generateMasterAppKeyListForFL(state, productList, noOfLives) {
		def keyList = [:] as Map
		for(def prod: productList){
			def productCode= prod?.productCode
			def key= state+"_"+noOfLives+"_"+productCode
			def formName=generateMasterAppKeyFL(state, productCode, noOfLives)
			if(formName)
				keyList.put(key, formName)
		}
		keyList
	}



	def generateMasterAppKeyFL(state,productCode, noOfLives){
		def eformName =[:] as Map
		def productListofTwoPlus=['BSCL', 'BSCLD', 'OPTLD', 'OPTL', 'STD', 'LTD'] as List
		def productListofTenPlus=['ACC', 'HI', 'CIAA'] as List
		def productListofTwoToFifty=['DPPO', 'VIS']
		int noOfLife= Integer.parseInt(noOfLives)

		if(noOfLife >= 2  && productListofTwoPlus.contains(productCode)){
			def eformKey= state+"_2_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		else if(noOfLife >= 10 && productListofTenPlus.contains(productCode)){
			def eformKey= state+"_10_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		else if ((noOfLife >= 2 && noOfLife <=50) && productListofTwoToFifty.contains(productCode)){
			def eformKey= state+"_2-50_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		else if (noOfLife >50 && productListofTwoToFifty.contains(productCode)){
			def eformKey= state+"_51_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		return eformName
	}



	def generateMasterAppKeyGS(state,productCode, noOfLives){
		def eformName =[:] as Map
		def productListofTwoPlus=['DPPO', 'VIS', 'STD', 'LTD', 'BSCL', 'OPTL', 'BSCLD', 'OPTLD'] as List
		def productListofTenPlus=['ACC', 'HI', 'CIAA'] as List
		int noOfLife= Integer.parseInt(noOfLives)
		if(noOfLife >= 2  && productListofTwoPlus.contains(productCode)){
			def eformKey= state+"_2_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}else if(noOfLife >= 10 && productListofTenPlus.contains(productCode)){
			def eformKey= state+"_10_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}else if (noOfLife >= 10 && "DHMO".equalsIgnoreCase(productCode) && ("NJ".equalsIgnoreCase(state)||"NY".equalsIgnoreCase(state))){
			def eformKey= state+"_10_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		return eformName
	}
	def generateMasterAppKeyWV(state,productCode, noOfLives){
		def eformName = [:] as Map
		def eformNameHi= [:] as Map
		def flag=false
		def productListofTwoPlus=['DPPO', 'VIS', 'STD', 'LTD', 'BSCL', 'OPTL', 'BSCLD', 'OPTLD'] as List
		def productListofTenPlus=['ACC', 'CIAA'] as List
		int noOfLife= Integer.parseInt(noOfLives)
		if(noOfLife >= 2  && productListofTwoPlus.contains(productCode)){
			def eformKey= state+"_2_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}else if(noOfLife >= 10 && productListofTenPlus.contains(productCode)){
			def eformKey= state+"_10_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}else if (noOfLife >= 10 && "HI".equalsIgnoreCase(productCode)){
			def eformKey= state+"_10_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
			flag=true
		}
		if(flag){
			return eformNameHi
		}else{
			return eformName
		}
	}


	def generateMasterAppKeyND(state,productCode, noOfLives){
		def eformName =[:] as Map
		def productListofTwoPlus=['DPPO', 'VIS', 'STD', 'LTD', 'BSCL', 'OPTL', 'BSCLD', 'OPTLD'] as List
		def productListofTenPlus=['ACC'] as List
		int noOfLife= Integer.parseInt(noOfLives)

		if(noOfLife >= 2  && productListofTwoPlus.contains(productCode)){
			def eformKey= state+"_2_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}else if(noOfLife >= 10 && productListofTenPlus.contains(productCode)){
			def eformKey= state+"_10_"+productCode
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		return eformName
	}
	def generateMasterAppKeyNDForAdditionalCondition(state,productCode, noOfLives,classDefinitions){
		def eformName =[:] as Map
		def productListofTenPlusNewCondition=['HI', 'CIAA'] as List
		int noOfLife= Integer.parseInt(noOfLives)

		def fullTimeHours =0
		def fullTimeFlag =false
		for(def classId: classDefinitions){
			for(def product: classId?.productDetails){
				if(productListofTenPlusNewCondition.contains(product?.productName) && "fullTimeHours".equals(product?.provisionName)){
					fullTimeHours = Integer.parseInt(product?.provisionValue)
					fullTimeFlag=true
				}
			}
		}
		if(fullTimeFlag && noOfLife <= 50 && fullTimeHours > 30){
			def eformKey= state+"_10_"+productCode+"_2"
			eformName.putAt(MasterAppDBKeyList.getAt(eformKey), productCode)
		}
		return eformName
	}
	/**
	 * This Method is used to get the AE license number from ERL system
	 * @param header
	 * @param aePrefix
	 * @param registeredServiceInvoker
	 * @param acoountExecutiveId
	 * @param state
	 * @param productCode
	 * @return
	 */
	def aeLicenseNumber(header,aePrefix,registeredServiceInvoker,acoountExecutiveId,state,productCode) {
		logger.info("AEDetails getLicense() :: Start")
		logger.error("Request End points to ERL System :-----> AEprefix :"+aePrefix)
		def endpoint = "${aePrefix}"
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		uriBuilder.queryParam("id",acoountExecutiveId)
		uriBuilder.queryParam("state",state)
		uriBuilder.queryParam("lineOfAuthority",productCode)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info("Request End points to ERL System :->${serviceUri} header: ${header} acoountExecutiveId: ${acoountExecutiveId}  state :${state} productCode: ${productCode}")
		def response
		def actualResponse
		try{
			header=getRequiredHeaders(header)
			logger.info("${SERVICE_ID} ----> acoountExecutiveId: ${acoountExecutiveId} ==== Calling AELicence API : ${serviceUri}")
			Date start = new Date()
			response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], header)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ----> acoountExecutiveId: ${acoountExecutiveId} ==== Calling AELicence API : ${serviceUri}, Response time : "+ elapseTime)
			actualResponse = response?.getBody()?.licenseNumber
			if((response?.getBody())?.error) {
				//JSONParser parser = new JSONParser()
				def err= response?.getBody()?.description
				def errDetails= response?.getBody()?.moreInformation
				logger.info("Error Message and Details Info from AE License Service ${err} errDetails: ${errDetails}")
			}
			logger.info("Sucessfully Retrieved AE response from ERL..."+response)
		}catch(e) {
			logger.error("Error retrieving AE response from ERL  ${e.message}")
			//throw new GSSPException("AE_SERVICE_ERROR")
		}
		logger.info("AE response from ERL --->${response}")
		logger.info("AEDetails getLicense() :: End")
		actualResponse
	}
	def getRequiredHeaders(headersList) {
		def headerKey= headersList.keySet()
		def spiHeaders = [:]
		headerKey.each(){header->
			spiHeaders << [(header): [headersList[header]]]
		}
		spiHeaders
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
