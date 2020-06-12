package groovy.US

import java.time.Instant
import java.util.concurrent.CompletableFuture

import org.slf4j.MDC;
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

import groovy.json.JsonOutput
import groovy.time.TimeCategory
import groovy.time.TimeDuration

/**
 * Edpm Save Preference
 * @author Durgesh Kumar Gupta
 *
 */
class GroupSetupSavePreference implements Task{
	Logger logger = LoggerFactory.getLogger(GroupSetupSavePreference)
	def static final SERVICE_ID = "GSSP005"
	@Override
	Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def requestPathParamsMap = workFlow.getRequestPathParams()
		Map<String, Object> requestBody = workFlow.getRequestBody()
		def userId=requestBody.getAt(GroupSetupConstants.METREF_ID)
		def tenantId= requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		def spiHeadersMap = [:]
		spiHeadersMap = buildSPIRequestHeaderMapPut(workFlow, spiHeadersMap)
		def groupSetupId = workFlow.getRequestPathParams().get(GroupSetupConstants.GROUPSETUP_ID)
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetupId.split('_')[0])
		def groupNumber=groupSetupId.split('_')[0]
		logger.info("GroupSetupSavePreference : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GroupSetupSavePreference : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def groupSetupData= getGSDraftById(entityService,groupSetupId)
		def edpmRequestBody = buildSavePreferenceStructure(entityService, groupSetupId,groupSetupData)
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def edpmPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.EDPMPREFIX)
		//changes start by mohan
		def roleTypeCode=requestBody.getAt(GroupSetupConstants.USER_ROLE_TYPE_CODE)
		def SSN
		GroupSetupUtil util=new GroupSetupUtil()
		if(userId!=null && roleTypeCode!=null && roleTypeCode.equals("30002")){
			SSN=util.getEmployerSSNFromDB(entityService,userId,roleTypeCode);
		}
		//changes end by mohan
		def userIds = util.getDataFromDB(entityService,GroupSetupConstants.USER_KEY,GroupSetupConstants.GROUP_NUMBER,groupSetupData?.extension?.groupNumber)
		def metRefIds = filterEmployerIds(userIds,userId)
		logger.info("${SERVICE_ID} ----> ${groupSetupId} ----> ${metRefIds} ==== Calling preferences API ")
		Date start2 = new Date()
		for(def metRefId :metRefIds){
			putCommunicationPreference(edpmPrefix, registeredServiceInvoker, spiHeadersMap, metRefId, edpmRequestBody,groupSetupId,gsspRepository)
		}
		Date stop2 = new Date()
		TimeDuration elapseTime2 = TimeCategory.minus(stop2, start2)
		logger.info("${SERVICE_ID} ----> ${groupSetupId} ----> ${metRefIds} ==== preferences API Elapse Time : "+ elapseTime2)
		def iibSpiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def spiIIBHeaderMap=util.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)
		def submitGs= submitGSDetails(iibSpiPrefix ,registeredServiceInvoker,spiIIBHeaderMap,groupSetupData)
		if(submitGs?.result && "true".equalsIgnoreCase(submitGs?.result)) {
			updateModuleAndActualStatusInExtension(entityService,groupSetupId,groupSetupData)
		}
		def maintenenceServiceVip = workFlow.getEnvPropertyFromContext(GroupSetupConstants.MAINTENANCEVIP)
		def subGroupList=groupSetupData?.groupStructure?.subGroup
		def contact=groupSetupData?.groupStructure?.contact[0]
		HashMap conatactMap=new HashMap()
		UpdateEmployerProfile updateProfileEmployer=new UpdateEmployerProfile()
		if(!subGroupList.isEmpty()) {
			subGroupList.each{ subgroup ->
				def contacts=subgroup?.buildCaseStructure?.contacts
				def isDeleted=subgroup?.isDeleted
				if(isDeleted.equalsIgnoreCase("false") || isDeleted=="false"){
					for(def contactObj: contacts){
						def roles = contactObj?.roleTypes
						for(def role : roles) {
							def clientID=role?.clientId
							def roleName=role?.roleType
							
							def firstName =contactObj?.firstName
							def lastName=contactObj?.lastName
							def email=contactObj?.email
							def workPhone=contactObj?.workPhone
							def cellPhone=contactObj?.cellPhone
							
							def contactObj2=[:] as Map;
							contactObj2<<["clientID":clientID]
							contactObj2<<["roleName":roleName]
							contactObj2<<["firstName":firstName]
							contactObj2<<["lastName":lastName]
							contactObj2<<["email":email]
							contactObj2<<["workPhone":workPhone]
							contactObj2<<["cellPhone":cellPhone]
							conatactMap.put(clientID, contactObj2)
						}
					}
				}
			}
		}
		String isExecutive=contact?.isExecutiveSameForAllRole
		def contactsSize = conatactMap.size()
		if(!conatactMap.isEmpty()  && (!isExecutive.equalsIgnoreCase("Yes") || isExecutive.isEmpty())) {
			Collection<CompletableFuture<Map<String, Object>>> preferenceFuture = new ArrayList<>(conatactMap.size())
			logger.info("${SERVICE_ID} ----> ${groupSetupId} ----> contacts: ${contactsSize} ==== Calling OrganizationUser API ")
			Date start3 = new Date()
			conatactMap.each { clientId, contactObj ->
				preferenceFuture.add(CompletableFuture.supplyAsync({
					logger.info(" contactMap  :: "+contactObj?.roleName +" "+clientId)
					//As Part of defect 64335 duplicate Executive Contact in Orgmaintenace
					if(!contactObj?.roleName.equals("Executive Contact")) {

						callMaintenance(groupSetupData, contactObj, clientId,tenantId,registeredServiceInvoker,maintenenceServiceVip, workFlow,SSN)
					}
				}))
				CompletableFuture.allOf(preferenceFuture.toArray(new CompletableFuture<?>[preferenceFuture.size()])).join()
			}
			Date stop3 = new Date()
			TimeDuration elapseTime3 = TimeCategory.minus(stop3, start3)
			logger.info("${SERVICE_ID} ----> ${groupSetupId} ---->  contacts: ${contactsSize} ==== OrganizationUser API Elapse Time : "+ elapseTime3)
		}
		//As Part of defect 66259 i.e SSOG record not created for Executive Contact
		conatactMap.each { clientId, contactObj ->
			if(contactObj?.roleName.equals("Executive Contact")) {
				def clientID=groupNumber+"-"+clientId
				logger.info(" Calling SSOG to add Executive Contact  :: "+contactObj?.roleName +" and Client Id"+clientID + " Metref id "+userId)
				workFlow.addFacts("metRefId", userId)
				workFlow.addFacts("clientId", clientID)
				updateProfileEmployer.execute(workFlow)
			}
		}
		workFlow.addResponseBody(new EntityResult(submitGs,true))
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

	def filterEmployerIds(userIds, metRefId){
		def metRefIds =[] as Set
		metRefIds.add(metRefId)
		for(def userId :userIds){
			for(def roleCapability :userId?.groups?.roleCapability){
				def userRoleTypeCode = roleCapability?.userRoleTypeCode[0]
				if(GroupSetupConstants.EMPLOYER_EXECUTIVE_CONTACT.equals(userRoleTypeCode) || GroupSetupConstants.EMPLOYER_BA.equals(userRoleTypeCode)){
					metRefIds.add(userId?.partyKey)
				}
			}
		}
		metRefIds
	}
	def callMaintenance(groupSetupData, contactObj, clientId,tenantId, registeredServiceInvoker, maintenenceServiceVip,WorkflowDomain workflow,SSN) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def maintenanceUri = "/v1/tenants/$tenantId/registration/organizationUser?sourceSystemCode=GS"
		logger.info("callMaintenance  maintenanceUri:: "+maintenanceUri+" role "+contactObj?.roleName)
		def maintenanceData = createRequestPayload(groupSetupData, contactObj, clientId, tenantId,SSN)
		def Jsonutput=JsonOutput.toJson(maintenanceData);
		logger.info("maintenanceData rquest Payload : "+Jsonutput)
		try {
			registeredServiceInvoker.post(maintenenceServiceVip,"${maintenanceUri}",new HttpEntity(maintenanceData), Map.class)
		} catch (e){
			logger.error(" Exception while calling Enrollment service, createGroups - ${maintenanceUri} - ${e.getMessage()}")
			//throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
	}
	def createRequestPayload(groupSetupData, contactObj, clientId, tenantId, SSN){
		def request =[:] as Map
		def groupNumber = groupSetupData?.extension?.groupNumber
		request.put("number", groupNumber)
		request.put("statusCode", "101")
		request.put("statusEffectiveDate", groupSetupData?.clientInfo?.basicInfo?.effectiveDate)
		request.put("typeCode", "107")
		request.put("name", groupSetupData?.extension?.companyName)

		//def contacts = getContactList(groupSetupData?.groupStructure?.contact)
		//As per new changes request by shared
		def contacts =[] as List
		request.put("contacts", contacts)
		def locations = getLocationList(groupSetupData?.groupStructure?.locations)
		request.put("locations", locations)
		def items = getItemsList(groupSetupData?.groupStructure?.contact,locations, contactObj,clientId, tenantId,groupNumber,SSN)
		def users =['items':items]
		request.put("users", users)
		return request
	}
	def status(actualStatus){
		def status =''
		switch(actualStatus){
			case 'Active' :
				status = '101'
				break
			case 'Inactive' :
				status = '102'
				break
			case 'Draft' :
				status = '103'
				break
			case 'Aplication Not Started' :
				status = '104'
				break
			case 'Application In Progress' :
				status = '105'
				break
			case 'Submitted' :
				status = '106'
				break
		}
		status
	}

	def getItemsList(contacts,locations,contact,clientId,tenantId,groupNumber,SSN){
		def userRoleTypeCode

		if("Executive Contact".equalsIgnoreCase(contact?.roleName) ){
			userRoleTypeCode = "40005"
		}
		else{
			userRoleTypeCode = "40006"
		}
		def items = [] as List

		def ncontact = [:] as Map
		def item =[:] as Map
		ncontact.put("organizationUserIdentifier", SSN)
		ncontact.put("statusCode", '101')
		ncontact.put("statusEffectiveDate", '')
		def name =[:] as Map
		name.put('title', '')
		name.put('suffix', '')
		name.put('firstName', contact?.firstName)
		name.put('lastName', contact?.lastName)
		name.put('middleName', '')
		ncontact.put('name', name)
		def emails =[] as List
		def email =[:]
		email.put("address", contact?.email)
		email.put("typeCode", "Business")
		email.put("isPrimary", "1")
		emails.add(email)
		ncontact.put("emails", emails)
		def phoneNumbers = [] as List
		def phoneNumber = [:]
		String numberWithdash=contact?.workPhone
		def phonNumber=""
		if(numberWithdash)
			phonNumber=numberWithdash.replaceAll("\\D", "")
		phoneNumber.put("countryCode", '')
		phoneNumber.put("isPrimary", '1')
		phoneNumber.put("number", phonNumber)
		phoneNumber.put("typeCode", 'BusinessPhone')
		phoneNumbers.add(phoneNumber)
		if(contact?.cellPhone){
			String cellNumberWithdash=contact?.workPhone
			def cellNumber=""
			if(cellNumberWithdash)
				cellNumber=cellNumberWithdash.replaceAll("\\D", "")
			def sphoneNumber = [:]
			sphoneNumber.put("countryCode", '')
			sphoneNumber.put("isPrimary", '1')
			sphoneNumber.put("number", cellNumber)
			sphoneNumber.put("typeCode", 'MobilePhone')
			phoneNumbers.add(sphoneNumber)
		}
		ncontact.put("phoneNumbers", phoneNumbers)
		def extension = [:] as Map
		extension.put('brokerKey','')
		extension.put('partyKey','')
		extension.put('employerKey',clientId)
		extension.put('userPersonaTypeCode','30002')
		extension.put('userRoleTypeCode',userRoleTypeCode)
		extension.put('organizationNumber',groupNumber)
		extension.put('tenantId',tenantId)
		extension.put('associatedOrgLocations',locations)
		ncontact.put("extension", extension)
		item.put('item', ncontact)
		items.add(item)
		return items
	}
	def getLocationList(locations){
		def newLocation = [] as List
		for (def location : locations){
			def nlocation =[:] as Map
			def address = [:] as Map
			address.put("addressLine1", location?.addressLine1)
			address.put("addressLine2", location?.addressLine2)
			address.put("city", location?.city)
			address.put("country", '')
			address.put("state", location?.state)
			address.put("type", 'Business')
			address.put("zipCode", location?.zipCode)
			nlocation.put('address', address)
			nlocation.put('displayName', location?.locationName)
			nlocation.put('typeCode', 'home')
			def taxIds = [] as List
			def taxId = [:] as Map
			taxId.put("number", location?.taxId)
			taxId.put("type", "TIN")
			taxIds.add(taxId)
			nlocation.put('taxIds', taxIds)
			newLocation.add(nlocation)
		}
		return newLocation
	}
	def getContactList(contacts){
		def newContactFormat = [] as List
		for (def contact : contacts){
			def ncontact = [:] as Map
			def firstName=contact?.firstName
			def lastName=contact?.lastName
			ncontact.put("displayName",firstName +" "+lastName)
			def emails =[] as List
			def email =[:]
			email.put("address", contact?.email)
			email.put("typeCode", "Business")
			email.put("isPrimary", "1")
			emails.add(email)
			ncontact.put("emails", emails)
			def phoneNumbers = [] as List
			def phoneNumber = [:]
			String numberWithdash=contact?.workPhone
			def phonNumber=""
			if(numberWithdash)
				phonNumber=numberWithdash.replaceAll("\\D", "")
			phoneNumber.put("countryCode", '')
			phoneNumber.put("isPrimary", '1')
			phoneNumber.put("number", phonNumber)
			phoneNumber.put("typeCode", 'BusinessPhone')
			phoneNumbers.add(phoneNumber)
			if(contact?.cellPhone){
				String cellNumberWithdash=contact?.workPhone
				def sphoneNumber = [:]
				sphoneNumber.put("countryCode", '')
				sphoneNumber.put("isPrimary", '1')
				sphoneNumber.put("number", cellNumberWithdash.replaceAll("\\D", ""))
				sphoneNumber.put("typeCode", 'MobilePhone')
				phoneNumbers.add(sphoneNumber)
			}
			ncontact.put("phoneNumbers", phoneNumbers)
			newContactFormat.add(ncontact)
		}
		return newContactFormat
	}
	def updateModuleAndActualStatusInExtension(entityService,groupSetupId,groupSetupData) {
		def extension = groupSetupData?.extension
		extension << ['actualStatus':'Submitted']
		groupSetupData << ['extension':extension]
		entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetupId, groupSetupData)
	}

	def putCommunicationPreference(spiPrefix, registeredServiceInvoker, spiHeadersMap, userId, edpmRequestBody,groupSetupId,gsspRepository) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		GroupSetupUtil util = new GroupSetupUtil()
		def uri
		uri = spiPrefix+"/"+userId+"/preferences"
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		logger.info("putCommunicationPreference URI for Save Preference "+ uri)
		def json= JsonOutput.toJson(edpmRequestBody)
		util.saveIIBRequestPayload(gsspRepository, edpmRequestBody, "Preference", groupSetupId)
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		def serviceUri = uriBuilder.build(false).toString()
		def httpEntityRequest = registeredServiceInvoker.createRequest(edpmRequestBody, spiHeadersMap)
		ResponseEntity<Map> responseData
		try {
			responseData = registeredServiceInvoker.putViaSPIWithResponse(serviceUri, httpEntityRequest, [:])
		} catch (e) {
			logger.error 'Exception in getting SPI for Communication Preference PutPreferences ' + e.getMessage()
			//throw new GSSPException("SERVICE_NOT_AVAILABLE")
		}
		logger.info("putCommunicationPreference "+responseData)
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		responseData?.getBody()
	}



	def buildSavePreferenceStructure(entityService, groupSetupId, groupSetupData){
		def items= [] as List
		def item = [:] as Map
		item.put("self", "")
		item.put("number", "smd.groupsetup.status")
		item.put("userIDTypeCode", "GRF")
		def subscription =[:] as Map
		subscription.put("subscriptionName","smd.groupsetup.status")
		subscription.put("productCode","")
		subscription.put("subscriptionStatusCode","Valid")
		subscription.put("subscriptionID","smd.groupsetup.status")
		subscription.put("subscriptionTypeCode","")
		item.put("subscription", subscription)
		def communicationMethods =[] as List
		def communicationMethod =[:] as Map
		communicationMethod.put("communicationMethodCode", "Electronic")
		communicationMethods.add(communicationMethod)
		item.put("communicationMethods", communicationMethods)
		def electronicContactPoints =[] as List
		def electronicContactPoint =[:] as Map
		if("Electronic/Email".equalsIgnoreCase(groupSetupData?.authorization?.grossSubmit?.onlineAccess?.documentDeliveryPreference))
		{
			electronicContactPoint.put("electronicContactPointValue", getEmailId(groupSetupData))
		}
		else{
			electronicContactPoint.put("electronicContactPointValue", '')
		}
		electronicContactPoint.put("preferredIndicator", true)
		electronicContactPoints.add(electronicContactPoint)
		item.put("electronicContactPoints", electronicContactPoints)

		def phones = [] as List
		item.put("phones", phones)
		def consents = [] as List
		item.put("consents", consents)
		items.add(item)
		logger.info("buildSavePreferenceStructure ::"+items)
		return ['items':items]
	}
	def getEmailId(groupSetupData){
		def emailAddress = ''
		boolean flag =true
		def buildCaseStructures =groupSetupData?.groupStructure?.buildCaseStructure
		for(def buildCaseStructure: buildCaseStructures){
			def locations = buildCaseStructure?.location
			if("yes".equalsIgnoreCase(locations?.isPrimaryAddress)){
				emailAddress =getContactEmailId(buildCaseStructure?.contacts, true)
				flag = false
			}
		}
		if(flag){
			emailAddress =getContactEmailId(groupSetupData?.groupStructure?.contact, false)
		}
		logger.info("buildSavePreferenceStructure emailAddress ::"+emailAddress)
		emailAddress
	}
	def getContactEmailId(contacts, flag){
		def emailAddress= ''
		for(def contact: contacts){
			def roleTypes =contact?.roleTypes
			for(def roleType: roleTypes){
				if(flag){
					emailAddress = contact?.email
				}
			}
			return emailAddress
		}
	}
	def submitGSDetails(spiPrefix, registeredServiceInvoker,spiHeadersMap,groupSetupData) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def uri
		def response = null
		def spiResponse = [:] as Map
		def requestPayload =[:] as Map
		def groupNuber = groupSetupData?.extension?.groupNumber
		requestPayload  << ['masterAppSignature':groupSetupData?.masterAppSignature]
		requestPayload  << ['groupNumber':groupNuber]
		uri= "${spiPrefix}/submitgroupsetup"
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		try {
			logger.info("Preparing SPI URL to Submit to IIB :: spiHeadersMap:${spiHeadersMap} :: SPI URL :: $uri")
			Date start4 = new Date()
			def request = registeredServiceInvoker?.createRequest(requestPayload,spiHeadersMap)
			response = registeredServiceInvoker?.postViaSPI(uri, request,Map.class)
			Date stop4 = new Date()
			TimeDuration elapseTime4 = TimeCategory.minus(stop4, start4)
			logger.info("${SERVICE_ID} ----> ${groupNuber} === ${uri} api elapseTime : " + elapseTime4)
			spiResponse = response?.getBody()
		} catch (e) {
			logger.error 'Error while Submiting GS  ' + e.getMessage()
			throw new GSSPException('GS_NOT_SUBMITTED')
		}
		logger.info("submitGSDetails() :: Request Successfully Submitted to IIB" +response)
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		spiResponse
	}
	def getGSDraftById(entityService, groupId) {
		MDC.put("GET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def result=null
		try{
			EntityResult entResult = entityService?.get(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupId,[])
			result=entResult.getData()

		}
		catch(any){
			logger.error("Error getting draft Group Set UP Data  ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		MDC.put("GET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		result
	}


	def buildSPIRequestHeaderMapPut(WorkflowDomain workFlow, spiHeadersMap) {
		TokenManagementService devtoken = workFlow.getBeanFromContext("tokenManagementService", TokenManagementService.class)
		def token = devtoken.getToken()
		spiHeadersMap << ["Authorization": token]
		spiHeadersMap << ["Content-Type" : "application/json"]
		spiHeadersMap << ["UserId" : workFlow.getEnvPropertyFromContext(GroupSetupConstants.EDPM_USER)]
		spiHeadersMap << ["Password" : workFlow.getEnvPropertyFromContext(GroupSetupConstants.EDPM_PASS)]
		spiHeadersMap << ["X-IBM-Client-Id": workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_CLIENT_ID)]
		spiHeadersMap << ["x-spi-service-id": "APIC"]
		spiHeadersMap << ['x-gssp-tenantid': workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_TENTENT_ID)]
		spiHeadersMap << ['x-spi-service-id': workFlow.getEnvPropertyFromContext(GroupSetupConstants.APMC_SERVICE_ID)]
		logger.info("spi header map.."+spiHeadersMap)
		spiHeadersMap
	}
}

