package groovy.US

import static java.util.UUID.randomUUID

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

import org.slf4j.MDC;
import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService
import groovy.time.TimeCategory
import groovy.time.TimeDuration
/**
 * This is a generic API for submitting the groupSetup data to IIB/Majesco
 * @author Vishal/Durgesh Kumar Gupta
 *
 */
class SubmitGroupSetupDetails implements Task{
	Logger logger = LoggerFactory.getLogger(SubmitGroupSetupDetails)
	GroupSetupUtil util = new GroupSetupUtil()
	GetGroupSetupData getGSData = new GetGroupSetupData()
	def static final SERVICE_ID = "SGSD004"
	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER, RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def requestParamsMap = workFlow.getRequestParams()
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def spiPrefix = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def tenantId = requestPathParamsMap['tenantId']
		def requestBody = workFlow.getRequestBody()
		def demo=workFlow.getEnvPropertyFromContext('spi.switch')
		def groupNumber = requestPathParamsMap['groupSetUpId']
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupNumber.split('_')[0])
		logger.info("SubmitGroupSetupDetails : secValidationList: {" + secValidationList +"}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("SubmitGroupSetupDetails : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def userRoleTypeCode=requestBody.getAt(GroupSetupConstants.USER_ROLE_TYPE_CODE)
		def metrefid=requestBody.getAt(GroupSetupConstants.METREF_ID)
		def SSN
		if(metrefid!=null && userRoleTypeCode!=null && userRoleTypeCode.equals("30002")){
			SSN=util.getEmployerSSNFromDB(entityService,metrefid,userRoleTypeCode);
		}
		String module
		if (requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q) != null) {
			requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q)?.tokenize(GroupSetupConstants.SEMI_COLON).each( { queryParam ->
				def (key, value) = queryParam.tokenize(GroupSetupConstants.DOUBLE_EQUAL)
				if(key != null) {
					switch(key){
						case GroupSetupConstants.MODULE_NAME : module = value
							break;
						default:
							logger.info("key::: "+key)
							break;
					}
				}
			})
		}
		//Defect#57176 fix start
		if(module.equals(GroupSetupConstants.CLASS_DEFINITION) || module.equals(GroupSetupConstants.ASSIGN_CLASS)){
			SaveGroupSetupDraftDetails saveAsDraftObj=new SaveGroupSetupDraftDetails()
			def transalatedData=saveAsDraftObj.modifyData(requestBody)
			def clientInfo=transalatedData['clientInfo']
			if(clientInfo){
				requestBody['clientInfo']=clientInfo
			}
			//Duplicate Contacts fix - Identifying duplicate contacts
			if(module.equals(GroupSetupConstants.ASSIGN_CLASS)){
				updateLocationAndContactDetails(workFlow, registeredServiceInvoker, entityService, gsspRepository, groupNumber, spiPrefix,  profile, tenantId)
			}
			identifyingDuplicateContacts(entityService, requestBody, groupNumber, module)
		}
		logger.info("after erisa year calculation requestBody:::: "+requestBody)
		//Defect#57176 fix end
		def storedData = getExistingData(gsspRepository,tenantId,GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,groupNumber,requestBody,module)
		def requestPayload = getModuleData(entityService, groupNumber, module, GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, requestBody, storedData)
		def spiHeadersMap = util.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)
		util.saveIIBRequestPayload(gsspRepository, requestPayload, module, groupNumber)
		logger.info("requestPayload::: "+requestPayload)
		def response=submitGSDetails(demo,spiPrefix, registeredServiceInvoker, requestPayload, module, spiHeadersMap, entityService, profile)
		logger.info("submitGSDetails IIB response::: "+response)
		if(response?.resultId !=null && module.equals(GroupSetupConstants.NO_CLAIMS) && ((response?.resultId).equalsIgnoreCase('ReviewRequired') || (response?.resultId).equalsIgnoreCase('Review Required'))) {
			String isClaimsIncurred = requestBody?.getAt("claimsIncurred")
			def isClaimsKickOut= noClaimsKickOutValidation(storedData)
			if(isClaimsIncurred !=null && isClaimsIncurred.equalsIgnoreCase("Yes")) {
				response << ['kickOutIdentifer':'noClaims']
			}else if(isClaimsKickOut){
				response << ['kickOutIdentifer':'BoardofDirectors']
			}else{
				response << ['kickOutIdentifer':'custom']
			}
			logger.info("submitGSDetails IIB response after Added kickOutIdentifer:: "+response)
		}
		if(module.equals(GroupSetupConstants.ASSIGN_CLASS) && SSN!=null){
			overrideRoleUniqueIdValueWithSSN(requestBody,SSN)
		}
		if(module.equals(GroupSetupConstants.CLASS_DEFINITION)){
			
			convertNewClassToExisting(response,entityService,groupNumber,requestBody,storedData)
		}else if(module.equals(GroupSetupConstants.RISK_ASSESSMENT) || module.equals(GroupSetupConstants.NO_CLAIMS) || module.equals(GroupSetupConstants.FINALIZE_GROUP_SETUP)){
			if(module.equals(GroupSetupConstants.NO_CLAIMS)){
				storedData?.extension << ["isAuthorizationsDone": true]
			}
			updateModuleAndActualStatusInExtension(entityService,storedData, GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, module, groupNumber, response,requestBody)
			updateModuleInPermanentCollection (entityService, module, groupNumber, storedData?.extension?.kickOutIdentifer, storedData?.extension?.isKickOut,response)
		}
		if(module.equals(GroupSetupConstants.NO_CLAIMS)){
			//triggerBrokerEmails(workFlow,storedData,groupNumber)
			sendEmailsToWritingProducers(workFlow,storedData,groupNumber)
		}
		//Duplicate Contacts fix -To update LocationId and contactId, calling IIB to get groupsetup data
		/*if(module.equals(GroupSetupConstants.ASSIGN_CLASS) && response?.result && response?.result == "true")
		 updateLocationAndContactDetails(workFlow, registeredServiceInvoker, entityService, gsspRepository, groupNumber, spiPrefix,  profile, tenantId)*/
		workFlow.addResponseBody(new EntityResult(response, true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${groupNumber} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)

	}
	def getExistingData(gsspRepository,tenantId,collectionName, groupId,requestBody,module){
		MDC.put("SAVE_DRAFT_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		if(module=="classDefinition") {
			def policyUnderSection125=requestBody?.clientInfo?.erisa?.policyCoveredUnderSection125
			policyUnderSection125.each { output->
				def productCode=output?.productCode
				def provisionValue=output?.provisionValue
				if(productCode.equalsIgnoreCase("DPPO") || productCode.equalsIgnoreCase("VIS") ) {
					if("Yes".equalsIgnoreCase(provisionValue)){
						output.putAt("provisionValue","Y")
					}else if("No".equalsIgnoreCase(provisionValue)){
						output.putAt("provisionValue","N")
					}
				}
			}
			requestBody?.clientInfo?.erisa.putAt("policyCoveredUnderSection125", policyUnderSection125)
		}

		HashMap storedValue
		try{
			storedValue=gsspRepository.updateById(tenantId, collectionName,groupId, requestBody)
		}catch(e){
			logger.error("Error getting Group Set UP Module Data::: "+e.getMessage())
			throw new GSSPException("40001")
		}
		MDC.put("SAVE_DRAFT_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		storedValue
	}
	/**
	 * This method is use to trigger a mail to broker in Authorization
	 * @param workFlow
	 * @param storedData
	 * @param groupId
	 * @return
	 */
	def triggerBrokerEmails(WorkflowDomain workFlow,storedData,groupId){
		MDC.put("Trigger_Broker_Emails_"+GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		Date date=new Date()
		Instant startTime = Instant.now()
		logger.info("*********Start triggerBrokerEmails time********"+date.getCalendarDate())
		List writingProducers=storedData?.licensingCompensableCode?.writingProducers
		int numThreads = writingProducers.size()
		GroupSetupSendMail sendEmail = new GroupSetupSendMail()
		def groupNumber= groupId.split('_')[0]
		writingProducers.each{ wp ->
			if(!"Employer".equalsIgnoreCase(wp?.roleType)){
				CompletableFuture.supplyAsync({
					->
					try {
						def emailPayload = [:] as Map
						def messageBody = [:] as Map
						def extension =[:] as Map
						def name= [:] as Map
						name.put("firstName", wp?.firstName)
						name.put("lastName", wp?.lastName)
						extension.put("name", name)
						extension.put("userPersonaTypeCode", "30002")
						extension.put("groupNumber", groupNumber)
						messageBody.put("extension", extension)
						emailPayload.put("messageBody",messageBody)
						emailPayload.put("templateId", "15123471")
						emailPayload.put("toAddress", wp?.email)
						logger.info(" ==== Broker Email Payload===="+emailPayload)
						workFlow.addFacts("emailRequestBody", emailPayload);
						sendEmail.execute(workFlow)
						long id=Thread.currentThread().id
						String threadName = Thread.currentThread().getName()
						logger.info("******Time to complete task********"+date.getCalendarDate() +" Thread Name : "+threadName + " id : "+id)
					} catch (InterruptedException e) {
						logger.error("Thread intruption during executing task"+e)
					}
					catch(e){
						logger.error("email not sent for broker"+ e.message)
					}
				})
			}
		}
		logger.info("******End triggerBrokerEmails time*****"+date.getCalendarDate())
		Instant endTime = Instant.now()
		Duration timeElapsed = Duration.between(startTime, endTime)
		MDC.put("Trigger_Broker_Emails_"+GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		logger.info("******Total Time*****"+(timeElapsed.toSeconds()))
	}
	/**
	 * This method is use to trigger mail to Broker/Employer/GA/TPA  writing producers in Authorization
	 * @param workFlow
	 * @param storedData
	 * @param groupId
	 * @return
	 */
	def sendEmailsToWritingProducers(WorkflowDomain workFlow, storedData, groupId){
		Date date=new Date()
		Instant startTime = Instant.now();
		logger.info("*****Start sendEmailsToWritingProducers time******"+date.getCalendarDate())
		try{
			def writingProducers=storedData?.licensingCompensableCode?.writingProducers
			def comissionAck=storedData?.comissionAcknowledgement
			def isERDirect=storedData?.extension?.isERDirect
			def groupNumber= groupId.split('_')[0]
			def groupName=storedData?.extension?.companyName
			def rfpTypeValue = storedData?.extension?.rfpType
			def customerNameKey = GroupSetupConstants.CUSTOMER_NAME_KEY
			for(def wp: writingProducers){
				def emailPayload
				def roleType = wp?.roleType
				def emailId = wp?.email
				try
				{
					def templateID
					if(GroupSetupConstants.ROLE_BROKER.equalsIgnoreCase(roleType))
					{
						def brokerCode=wp?.compensableCode[0]?.brokerCode
						//def IsEsign=checkEsign(comissionAck,brokerCode)
						// && IsEsign commented to send mail to broker without esign
						if(brokerCode){
						if(rfpTypeValue.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS)){
							templateID=GroupSetupConstants.BROKER_EMAIL_TEMPLATEID
						}
						else{
							templateID=GroupSetupConstants.BROKER_GA_TPA_EMAIL_TEMPLATE_ID_ADDPRODUCT
						}
							
							emailPayload = preparePayload(wp, groupNumber, templateID, customerNameKey, groupName, GroupSetupConstants.BROKER_PERSONA_TYPE_CODE)
						}
					}else if(GroupSetupConstants.ROLE_GA.equalsIgnoreCase(roleType)){

						if(rfpTypeValue.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS))
							templateID=GroupSetupConstants.GA_TPA_EMAIL_TEMPLATE_ID
						else
							templateID=GroupSetupConstants.BROKER_GA_TPA_EMAIL_TEMPLATE_ID_ADDPRODUCT
						emailPayload = preparePayload(wp, groupNumber, templateID, customerNameKey, groupName, GroupSetupConstants.GA_PERSONA_TYPE_CODE)
					}else if(GroupSetupConstants.ROLE_TPA.equalsIgnoreCase(roleType)){

						if(rfpTypeValue.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS))
							templateID=GroupSetupConstants.GA_TPA_EMAIL_TEMPLATE_ID
						else
							templateID=GroupSetupConstants.BROKER_GA_TPA_EMAIL_TEMPLATE_ID_ADDPRODUCT
						emailPayload = preparePayload(wp, groupNumber, templateID, customerNameKey, groupName, GroupSetupConstants.TPA_PERSONA_TYPE_CODE)
					}else if(GroupSetupConstants.ROLE_EMPLOYER.equalsIgnoreCase(roleType) && GroupSetupConstants.TRUE.equalsIgnoreCase(isERDirect)){


						if(rfpTypeValue.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS))
							templateID=GroupSetupConstants.EMPLOYER_DIRECT_EMAIL_TEMPLATE_ID
						else
							templateID=GroupSetupConstants.EMPLOYER_DIRECT_EMAIL_TEMPLATE_ID_ADDPRODUCT
						emailPayload = preparePayload(wp, groupNumber, templateID, customerNameKey, groupName, GroupSetupConstants.EMPLOYER_PERSONA_TYPE_CODE)


					}else if(GroupSetupConstants.ROLE_EMPLOYER.equalsIgnoreCase(roleType) ){
						def brokerName = ""
						for(def wpp: writingProducers){
							if(GroupSetupConstants.ROLE_BROKER.equalsIgnoreCase(wpp?.roleType)){
								def brokerCode = wpp?.compensableCode[0]?.brokerCode
								def IsEsign = checkEsign(comissionAck,brokerCode)
								if(brokerCode && IsEsign){
									brokerName = wpp?.firstName+" "+wpp?.lastname
									break
								}
							}
						}

						if(rfpTypeValue.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS))
							templateID=GroupSetupConstants.EMPLOYER_EMAIL_TEMPLATE_ID
						else
							templateID=GroupSetupConstants.EMPLOYER_EMAIL_TEMPLATE_ID_ADDPRODUCT
						emailPayload = preparePayload(wp, groupNumber, templateID, GroupSetupConstants.BROKER_NAME_KEY, brokerName, GroupSetupConstants.EMPLOYER_PERSONA_TYPE_CODE)
						def extension = emailPayload?.messageBody?.extension
						extension.putAt(customerNameKey, groupName)
						emailPayload?.messageBody.putAt("extension", extension)
					}
					if(emailPayload){
						logger.info("${roleType} Email Request Payload : "+emailPayload)
						GroupSetupSendMail sendEmail = new GroupSetupSendMail()
						workFlow.addFacts("emailRequestBody", emailPayload);
						sendEmail.execute(workFlow)
						logger.info("Successfully sent email to ${roleType}, and emailId is : ${emailId}")
					}else
						logger.info("Email not sending to ${roleType}, since emailrequest payload not valid.  EmailPayload : "+emailPayload)

				}catch(e){
					logger.error("Exception occured while sending email to ${roleType},  emailId ${emailId} : "+ e.message)
					continue
				}
			}
			logger.info("*****End sendEmailsToWritingProducers time*****"+date.getCalendarDate())
			Instant endTime = Instant.now()
			Duration timeElapsed = Duration.between(startTime, endTime)
			logger.info("******Total Time*****"+(timeElapsed.toSeconds()))
		}catch(e){
			logger.error("Exception occured while sending emails : "+e.message)
		}
	}

	def preparePayload(def wp, def groupNumber, def templateId, def keyName, def keyValue, def userPersonaTypeCode){
		def emailPayload = [:] as Map
		def messageBody = [:] as Map
		def extension =[:] as Map
		def name= [:] as Map
		name.put("firstName", wp?.firstName)
		name.put("lastName", wp?.lastName)
		extension.put("name", name)
		extension.put("userPersonaTypeCode",userPersonaTypeCode)
		extension.put("SMDKey", "")
		extension.put("groupNumber", groupNumber)
		extension.put(keyName, keyValue)
		messageBody.put("extension", extension)
		emailPayload.put("messageBody",messageBody)
		emailPayload.put("templateId", templateId)
		emailPayload.put("toAddress", wp?.email)
		return emailPayload
	}

	def checkEsign(CopyOnWriteArrayList comissionAck,brokerCode){
		boolean isEsign=false
		comissionAck.eachWithIndex { output ,index->
			def brokerCodeAck=output?.brokerDetails?.brokerCode
			if(brokerCodeAck.equalsIgnoreCase(brokerCode)) {
				def eSignAck=output?.eSign?.isChecked
				if("Yes".equals(eSignAck)) {
					isEsign=true
					comissionAck.remove(index)
				}
			}
		}
		isEsign
	}
	/**
	 * This Method is used to build url and call the post api
	 * @param spiPrefix
	 * @param registeredServiceInvoker
	 * @param requestPayload
	 * @param module
	 * @param spiHeadersMap
	 * @param entityService
	 * @return
	 */
	def submitGSDetails(demo,spiPrefix, registeredServiceInvoker,requestPayload,module,spiHeadersMap,entityService, String[] profile) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def uri
		def response = null
		def spiResponse = [:] as Map
		uri = prepareURI(uri, spiPrefix, module)
		MDC.put(GroupSetupConstants.SUB_API_NAME, uri);
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)
			|| ((module.equals(GroupSetupConstants.CLASS_DEFINITION)
			|| module.equals(GroupSetupConstants.ASSIGN_CLASS ))
			&& demo.equals("demo"))) {
				// spiResponse= new GroupSetupUtil().getTestData("riskAssmentMock.json")
				// spiResponse = response?.getBody()
				spiResponse.putAt("result", "true")
				spiResponse.putAt("resultId", "approved")
			}
			else {
				logger.info("spiHeadersMap:::"+spiHeadersMap)
				logger.info("${SERVICE_ID} ----> ${module} ==== Calling ${uri} API ")
				Date start = new Date()
				def request = registeredServiceInvoker?.createRequest(requestPayload,spiHeadersMap)
				response = registeredServiceInvoker?.postViaSPI(uri, request,Map.class)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${module} === ${uri} api elapseTime : " + elapseTime)
				spiResponse = response?.getBody()
			}
		} catch (e) {
			logger.error("Error while Submiting GS ---> ${e}")
			throw new GSSPException('GS_NOT_SUBMITTED')
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		spiResponse
	}


	private String prepareURI(String uri, spiPrefix, module) {
		if(module.equals(GroupSetupConstants.RISK_ASSESSMENT)){
			uri= "${spiPrefix}/submitriskassessment"
		}
		else if(module.equals(GroupSetupConstants.CLASS_DEFINITION)){
			uri= "${spiPrefix}/validateclassdefinition"
		}
		else if(module.equals(GroupSetupConstants.ASSIGN_CLASS)){
			uri= "${spiPrefix}/submitgroupstructure"
		}
		else if(module.equals(GroupSetupConstants.NO_CLAIMS)){
			uri= "${spiPrefix}/submitnoclaims"
		}
		else if(module.equals(GroupSetupConstants.FINALIZE_GROUP_SETUP)){
			uri= "${spiPrefix}/submitgroupsetup"
		}
		else{
			logger.info("Invalid module :"+ "${module}")
		}
		uri
	}

	/**
	 *
	 * @param collectionName
	 * @param module
	 * @param groupNumber
	 * @param response
	 * @return
	 */
	def updateModuleAndActualStatusInExtension(entityService,storedValue, collectionName, module, groupNumber, response,requestBody) {
		MDC.put(module+"_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def extension = storedValue?.extension
		if((module.equals(GroupSetupConstants.RISK_ASSESSMENT) || module.equals(GroupSetupConstants.NO_CLAIMS)) && (response?.resultId != null ? (response?.resultId).equalsIgnoreCase('ReviewRequired') || (response?.resultId).equalsIgnoreCase('Review Required'):false)) {
			String isClaimsIncurred = requestBody?.getAt("claimsIncurred")
			def isClaimsKickOut= noClaimsKickOutValidation(storedValue)
			if(module.equals(GroupSetupConstants.NO_CLAIMS) && isClaimsIncurred !=null && isClaimsIncurred.equalsIgnoreCase("Yes")) {
				extension << ['kickOutIdentifer':'noClaims']
			}else if(isClaimsKickOut){
				extension << ['kickOutIdentifer':'BoardofDirectors']
			}
			else if(module.equals(GroupSetupConstants.NO_CLAIMS)) {
				extension << ['kickOutIdentifer':'custom']
			}
			extension << ['actualStatus':'PendingReview']
			extension << ['isKickOut':GroupSetupConstants.TRUE]

		}
		if((response?.result) && ((response?.result == "true") || ("true".equals(response?.result)))){
			extension << ['module':module]
		}
		storedValue << ['extension':extension]
		def rfpType = storedValue?.extension?.rfpType
		if(GroupSetupConstants.ADD_PRODUCT.equalsIgnoreCase(rfpType))
			storedValue?.clientInfo?.contributions.putAt("products", [])
		logger.info("********** TempStoredData :: ${storedValue}  **********")
		entityService.updateById(collectionName, groupNumber, storedValue)
		MDC.put(module+"_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}

	def updateModuleInPermanentCollection(entityService, module, groupNumber, kickOutIdentifer, isKickOut,response){
		try{
			logger.info("**********Updating module in PermanentCollection  :: module : ${module} and groupNumber : ${groupNumber} ***********")
			EntityResult entResult = entityService?.get(GroupSetupConstants.PER_COLLECTION_NAME, groupNumber, [])
			def perStoredData = entResult.getData()
			logger.info("********** perStoredData :: ${perStoredData}  **********")
			def extension = perStoredData?.extension
			if((response?.result) && ((response?.result == "true") || ("true".equals(response?.result)))){
				extension << ['module':module]
			}
			extension << ['kickOutIdentifer':kickOutIdentifer]
			extension << ['isKickOut': isKickOut]
			extension << ["isAuthorizationsDone": true]
			perStoredData << ['extension':extension]
			entityService.updateById(GroupSetupConstants.PER_COLLECTION_NAME, groupNumber, perStoredData)
		}catch(any){
			logger.error("Error while updating Module in Permanent collection ---> ${any}")
		}
	}

	/**
	 * Prepare the payload for validate api
	 * @param entityService
	 * @param groupId
	 * @param module
	 * @param collectionName
	 * @param requestBody
	 * @return
	 */

	def getModuleData(entityService, groupId, module, collectionName, requestBody, storedValue){

		def result=[:]
		try{
			logger.info("REQUEST BODY FOR Module::: "+module)
			def groupNumber = groupId.split('_')[0]
			def soldCaseId = groupId.split('_')[1]
			def uniqueId = groupId.split('_')[2]
			def erDirectFlagVal = storedValue?.extension?.isERDirect
			logger.info("erDirectFlagVal::: "+erDirectFlagVal)
			if(module.equals(GroupSetupConstants.RISK_ASSESSMENT)){
				storedValue = frameContributionsForAddProduct(storedValue, soldCaseId)
				result << [(GroupSetupConstants.CLIENT_INFO): storedValue?.clientInfo]
				result << [(GroupSetupConstants.LICENSING_COMPENSABLE_CODE): storedValue?.licensingCompensableCode]
				def riskAssessment = validateDisabledEmployeesQuestions(storedValue?.riskAssessment)
				result << [(GroupSetupConstants.RISK_ASSESSMENT): riskAssessment]
			}
			else if(module.equals(GroupSetupConstants.ASSIGN_CLASS)){
				def brokerDetails
				if(storedValue?.comissionAcknowledgement[0]) {
					def brokerCode = storedValue?.comissionAcknowledgement[0].brokerDetails?.brokerCode
					brokerDetails = addCompensableBroker(storedValue)
					if(!brokerDetails) {
						brokerDetails = storedValue?.comissionAcknowledgement
					}
				}
				result << [(GroupSetupConstants.CLIENT_INFO): storedValue?.clientInfo]
				result << [(GroupSetupConstants.COMISSION_ACKNOWLEDGEMENT): brokerDetails]
				result << [(GroupSetupConstants.RENEWAL_NOTIFICATION_PERIOD): storedValue?.renewalNotificationPeriod]
				//Duplicate Contacts fix -  Removing Old / deleted contacts -- starts
				def subGroup = util.removeDeletedContantsAndLocationsFromSubGroup(storedValue?.groupStructure?.subGroup)
				storedValue?.groupStructure.putAt("subGroup", subGroup)
				// Removing Old / deleted contacts -- end
				def groupStructure = reframeGroupStructure(storedValue)
				result << [(GroupSetupConstants.GROUP_STRUCTURE): groupStructure]
			}
			else if(module.equals(GroupSetupConstants.CLASS_DEFINITION)){
				def groupStructure=reframeGroupStructure(storedValue)
				result << [(GroupSetupConstants.CLASS_DEFINITION): groupStructure?.classDefinition]
				result <<[(GroupSetupConstants.CONTRIBUTIONS): storedValue?.clientInfo?.contributions]
			}
			else if(module.equals(GroupSetupConstants.NO_CLAIMS)){
				def billingObj = storedValue?.billing
				def paymentMode= billingObj?.paymentMode
				logger.info("paymentMode::: "+paymentMode)
				if(paymentMode.contains("Offline")){
					paymentMode="Pay Offline";
				}
				if(paymentMode.contains("Online")){
					paymentMode="Pay Online";
				}
				logger.info("AFTER UPDATE::paymentMode::: "+paymentMode)
				billingObj << ['paymentMode': paymentMode]
				storedValue << ['billing' : billingObj]
				def authorization= frameAuthorization(storedValue)
				logger.info("frameAuthorization after authorization frame"+authorization)
				result << [(GroupSetupConstants.BILLING): storedValue?.billing]
				result << [(GroupSetupConstants.AUTHORIZATION): authorization]
				result << [(GroupSetupConstants.BASICINFO): storedValue?.clientInfo?.basicInfo]
			}
			if(module.equals(GroupSetupConstants.FINALIZE_GROUP_SETUP)){
				result << [(GroupSetupConstants.GROUP_NUMBER):groupNumber]
			}
			else{
				result << [(GroupSetupConstants.SOLD_CASE_ID):soldCaseId]
				result << [(GroupSetupConstants.GROUP_NUMBER):groupNumber]
				result << ['isERDirect':erDirectFlagVal]
			}
			logger.info("UPDATED REQUEST BODY::: "+result)
			result
		}catch(e){
			logger.error("Error getting Group Set UP Module Data ---> ${e.getMessage()}")
			throw new GSSPException("40001")
		}
	}
	/**
	 * Framing Authorization as part of CR-242/253
	 * @param storedValue
	 * @return
	 */
	def frameAuthorization(storedValue) {
		def authorization=storedValue?.authorization
		def contact=storedValue?.groupStructure?.contact[0]
		logger.info("frameAuthorization contact "+contact)
		def contactPhone=contact?.workPhone
		def contactEmail=contact?.email
		def contactFax=contact?.fax
		def documentDeliveryPreference=authorization?.grossSubmit?.onlineAccess?.documentDeliveryPreference
		def deliveryPreferenve
		if(documentDeliveryPreference.equalsIgnoreCase("Electronic/Email"))
			deliveryPreferenve="Email"
		else
			deliveryPreferenve="Paper"
			
		def contactInfoMap=[:] as Map
		contactInfoMap.put("contactPhone",contactPhone)
		contactInfoMap.put("contactEmail",contactEmail)
		contactInfoMap.put("contactFax",contactFax)
		logger.info("frameAuthorization contactInfoMap "+contactInfoMap)
		storedValue?.authorization?.grossSubmit?.onlineAccess?.putAt("contactInfo",contactInfoMap)
		storedValue?.authorization?.grossSubmit?.onlineAccess.putAt("documentDeliveryPreference",deliveryPreferenve)
		
		storedValue?.authorization
		
	}

	def frameContributionsForAddProduct(storedValue, rfpId){
		try{
			def rfpType = storedValue?.extension?.rfpType
			if(GroupSetupConstants.ADD_PRODUCT.equalsIgnoreCase(rfpType))
			{
				def prodRFPType = GroupSetupConstants.ADD_PRODUCT+"_"+rfpId
				def contriProds = [] as List
				def products = storedValue?.extension?.products
				products.each { product ->
					if(prodRFPType.equalsIgnoreCase(product?.rfpType)){
						def prodObj = [:] as Map
						prodObj.put("productCode", product?.productCode)
						contriProds.add(prodObj)
					}
				}
				if(contriProds)
					storedValue?.clientInfo?.contributions.putAt("products", contriProds)
				logger.info("Updated contribution products for ADDPRODUCT business. Cotribution Products: "+contriProds)
			}
		}catch(any){
			logger.error("Error while updating contribution products for ADDPRODUCT business---> ${any.getMessage()}")
		}
		storedValue
	}

	/**
	 * This method validating disabled employees question, If any question answer is empty
	 * removing that object from risk assessment Disabled Employees questions payload.
	 * @param riskAssessment
	 * @return
	 */
	def validateDisabledEmployeesQuestions(riskAssessment) {
		def filteredQues = []
		def questions = riskAssessment?.disabledEmployees?.questions
		questions.each{questionObj ->
			if(questionObj?.answer)
				filteredQues.add(questionObj)
		}
		riskAssessment?.disabledEmployees.putAt('questions', filteredQues)
		riskAssessment
	}

	/**
	 * This is method to add compensable broker in payload to Send Majesco
	 * @param storedValue
	 * @return
	 */
	def addCompensableBroker(def storedValue){
		def comissonAckExisting=storedValue?.comissionAcknowledgement
		def licensingProducers=storedValue?.licensingCompensableCode?.writingProducers
		def comissionAckList=[] as HashSet
		def loginBrokerList=[] as HashSet
		comissonAckExisting.each() { comissionInput ->
			def brokerCode=comissionInput?.brokerDetails?.brokerCode
			def eConsent=comissionInput?.eConsent?.isConsented
			if(eConsent && eConsent.equalsIgnoreCase("Y")) {
				comissionAckList.add(comissionInput)
			}
			if(brokerCode) {
				loginBrokerList.add(brokerCode)
			}
		}
		licensingProducers.each() { input->
			def isSelected = input?.compensableCode?.isSelected[0]
			def roleType = input?.roleType
			if(isSelected && isSelected.equals("true") && roleType && roleType == "Broker") {
				def brokerCode=input?.compensableCode[0]?.brokerCode
				if(!(loginBrokerList.contains(brokerCode))) {
					def comissions=[:] as Map
					def spiltComission=input?.comissionSplitValue
					comissionAckList=createPayload(comissions,brokerCode,spiltComission,comissionAckList)
				}
			}
		}
		// If partyTypeRefId is avilable in compensable code then sending 'partyTypeRefId' as a brokercode to Majesco.
		/*logger.info("Before updating comissionAcknowledgement with partyTypeRefId -> comissionAckList is:::************************** ${comissionAckList}")
		 comissionAckList.each(){ commission ->
		 def comissionBrokerCode = commission?.brokerDetails?.brokerCode
		 if(comissionBrokerCode)
		 {
		 licensingProducers.each() { input ->
		 def licenbrokerCode = input?.compensableCode[0]?.brokerCode
		 def partyTypeRefId = input?.compensableCode[0]?.partyTypeRefId
		 if(licenbrokerCode && partyTypeRefId && licenbrokerCode == comissionBrokerCode)
		 commission?.brokerDetails.putAt("brokerCode", partyTypeRefId)
		 }
		 }
		 }
		 logger.info("After updating comissionAcknowledgement with partyTypeRefId -> comissionAckList is:::************************** ${comissionAckList}")*/
		comissionAckList
	}
	/**
	 * Method to create Payload for Mejesco
	 * @param comissions
	 * @param brokerCode
	 * @param spiltComission
	 * @param comissionAckList
	 * @return
	 */
	def createPayload(Map comissions,brokerCode,spiltComission,HashSet comissionAckList){
		Map eConsent=[:]
		def brokerDetails=[:]
		Map payeeDetails=[:]
		def comissionRate=[:]
		eConsent.putAt("isChecked","")
		eConsent.putAt("type","")
		eConsent.putAt("purpose","")
		eConsent.putAt("date","")
		eConsent.putAt("time","")
		eConsent.putAt("state","")
		eConsent.putAt("relation","")
		eConsent.putAt("globalVersion","")
		eConsent.putAt("isConsented","")
		eConsent.putAt("isConsentWaivedOff","")
		eConsent.putAt("wetInkIndicator","")
		eConsent.putAt("city","")
		eConsent.putAt("country","")
		eConsent.putAt("stateVersion","")
		eConsent.putAt("transactionReference","")
		payeeDetails.putAt("payeeAddress", "")
		payeeDetails.putAt("payeeBrokerCode", "")
		payeeDetails.putAt("payeName", "")
		brokerDetails.putAt("brokerId", "")
		brokerDetails.putAt("brokerCode", brokerCode)
		brokerDetails.putAt("brokerEmailId", "")
		comissions.putAt("situsState","")
		comissions.putAt("experienceNumber","")
		comissions.putAt("eConsent",eConsent)
		comissionRate.putAt("productName", "")
		comissionRate.putAt("commissionType", "")
		comissionRate.putAt("splitComission", spiltComission)
		comissionRate.putAt("commissionRate", "")
		comissions.putAt("comissionRate",[comissionRate])
		comissions.putAt("payeeDetails",payeeDetails)
		comissions.putAt("eSign",eConsent)
		comissions.putAt("effectiveDate","")
		comissions.putAt("brokerDetails",brokerDetails)
		comissions.putAt("situsState","")
		comissionAckList.add(comissions)
		comissionAckList
	}
	/**
	 * Building Header part for GetViaSpi
	 * @param headersList
	 * @param headerMap
	 * @return
	 */
	def getRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[(GroupSetupConstants.X_GSSP_TRACE_ID):randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): [headerMap[header]]]
			}
		}
		spiHeaders
	}
	/**
	 *
	 * @param groupStructure
	 * @return
	 */
	def reframeGroupStructure(storedValue){
		def groupName = storedValue?.extension?.companyName
		def effectiveDate = storedValue?.clientInfo?.basicInfo?.effectiveDate
		def groupStructure = storedValue?.groupStructure
		def classDefinition = groupStructure?.classDefinition
		def subGroup = groupStructure?.subGroup
		def partTimeApplicableProducts=['ACC', 'HI', 'CIAA', 'LGL'] as List
		def earningsAvgOverApplicableProducts=['BSCL', 'OPTL', 'STD', 'LTD'] as List
		//updated below array with all applicable products as part of defect#57307 fix
		def waiveWaitingApplicableProducts=['DHMO', 'DPPO', 'VIS', 'ACC', 'HI', 'CIAA', 'LGL', 'BSCL', 'OPTL', 'STD', 'LTD'] as List
		def classList= frameClassDefinition(classDefinition,partTimeApplicableProducts,earningsAvgOverApplicableProducts,waiveWaitingApplicableProducts)
		classList=framClassDefAddProduct(classList)
		def subgroupNew= frameSubGroup(subGroup, effectiveDate, groupName)
		groupStructure.putAt("subGroup", subgroupNew)
		groupStructure.putAt("classDefinition", classList)
		groupStructure
	}
	/**
	 * frame class definition in Amendment case 
	 * @param classList
	 * @return
	 */
	def framClassDefAddProduct(classList) {
		classList.each { classDefAddPrd->
			def rfpType =classDefAddPrd?.rfpType
//			List existingProduct=classDefAddPrd?.products
			List existingproductDetails=classDefAddPrd?.productDetails
			if(rfpType.contains("ADDPRODUCT")) {
				def includeExistingProduct=classDefAddPrd?.includeExistingProduct
				def value=includeExistingProduct?.value
				if(value.equals("Yes") || value =="Yes") {
//					def mirrorClassProducts=includeExistingProduct?.mirrorClassProducts
//					existingProduct.addAll(mirrorClassProducts)
					def mirrorClassProductDetails=includeExistingProduct?.mirrorClassProductDetails
					existingproductDetails.addAll(mirrorClassProductDetails)
				}
//				classDefAddPrd<<["products":existingProduct]
				classDefAddPrd<<["productDetails":existingproductDetails]
			}
		}
		logger.info("CLASS DEFINATION Merge for Add Product::: "+classList)
		classList
	}
	def frameSubGroup(subGroup, effectiveDate, groupName) {
		HashMap effectiveDateNew=[:]
		subGroup.each (){ output ->
			effectiveDateNew.put("effectiveDate", effectiveDate)
			output<<effectiveDateNew
			def isPrimaryAdd = output?.buildCaseStructure?.location?.isPrimaryAddress
			if(isPrimaryAdd.equalsIgnoreCase("Yes") || isPrimaryAdd== "Yes" )
				output?.buildCaseStructure?.location.putAt("locationName", groupName)
		}
		subGroup
	}
	def frameClassDefinition(classDefinition,partTimeApplicableProducts,earningsAvgOverApplicableProducts,waiveWaitingApplicableProducts){
		def classList =[] as List
		for(HashMap classDef: classDefinition){
			def prodDetail=[] as List
			def productList= classDef?.products
			for(def product: productList){
				def productInfo=[:] as Map
				productInfo.put("productName", product)
				def prodDetails=classDef?.productDetails
				def provisionList= frameProvisionList(product,prodDetails,partTimeApplicableProducts,earningsAvgOverApplicableProducts,waiveWaitingApplicableProducts)
				productInfo.put("provisions", provisionList)
				prodDetail.add(productInfo)
			}
			classDef.putAt("productDetails", prodDetail)
			classDef.remove("products")
			classList.add(classDef)
		}
		classList
	}
	def frameProvisionList(product,prodDetails,partTimeApplicableProducts,earningsAvgOverApplicableProducts,waiveWaitingApplicableProducts){
		def provisionlist=[] as List
		def earningDefvalue=""
		for(def productDetails: prodDetails){
			def provisionMap =[:] as Map
			def provisionName = productDetails?.provisionName
			def assignedProducts=productDetails?.products
			if((provisionName.equals("earningDefinition") || provisionName.equals("earningInclude"))
			&& !earningsAvgOverApplicableProducts.contains(product)){
				continue
			}else{
				if(provisionName.equals("earningDefinition")){
					earningDefvalue= productDetails?.provisionValue
					continue
				}else if(provisionName.equals("earningInclude")){
					provisionMap.put("provisionName","earnings")
					def provisionValue = productDetails?.provisionValue
					//below condition updated as part of defect#52283,55216
					provisionMap.put("provisionValue", (provisionValue && !(provisionValue.equals("No Additional Earnings included"))) ? earningDefvalue+"+"+provisionValue : earningDefvalue)
				}else{
					if(provisionName.equals("waitingTime") || provisionName.equals("period")){
						if(assignedProducts.contains(product)){
							provisionMap.put("provisionName",provisionName)
							provisionMap.put("provisionValue",productDetails?.provisionValue)
						}else{
							continue
						}
					} else{
						if((provisionName.equalsIgnoreCase("partTimeHours") && !partTimeApplicableProducts.contains(product))
						|| (provisionName.equalsIgnoreCase("averagedOverPeriod") && !earningsAvgOverApplicableProducts.contains(product))
						|| (provisionName.equalsIgnoreCase("waiveWaitingPeriod") && !waiveWaitingApplicableProducts.contains(product))|| provisionName.equalsIgnoreCase("sameWatingPeriodforProduct")){
							continue
						}else{
							provisionMap.put("provisionName",provisionName)
							provisionMap.put("provisionValue",productDetails?.provisionValue)
							//Defect #45497 fix start
							if(provisionName.equalsIgnoreCase("waiveWaitingPeriod"))
								provisionMap = overrideWaitingTimeProvisionValue(provisionMap, product, prodDetails)
							//Defect#45497 fix end
							//Defect#49651 fix start
							if(provisionName.equalsIgnoreCase("averagedOverPeriod"))
								provisionMap = defaultAveragedOverPeriodValue(provisionMap)
							//Defect#49651 fix start
						}
					}
				}
			}

			provisionMap.put("grouping", productDetails?.grouping)
			provisionMap.put("qualifier", productDetails?.qualifier)
			provisionlist.add(provisionMap)
		}
		provisionlist
	}
	def convertNewClassToExisting(response,entityService,groupNumber,requestBody,storedData){
		if("true".equals(response?.result)){
			def groupStructure=requestBody?.groupStructure
			def classDefinition=groupStructure?.classDefinition
			logger.info("CLASS DEFINATION BEFORE UPDATE::: "+classDefinition)
			classDefinition.each() { classDef ->
				def mode= classDef?.mode
				if(mode.toUpperCase().equals("CREATE")){
					def newclassName= classDef?.newClassName
					def newClassDescription= classDef?.newClassDescription
					classDef << ['mode':'UPDATE']
					classDef << ['existingClassName':newclassName]
					classDef << ['existingClassDescription':newClassDescription]
					classDef << ['newClassName':'']
					//					classDef << ['newClassDescription':'']
					logger.info("class def got changed to UPDATE from CREATE")
				}else if(mode.toUpperCase().equals("UPDATE")){
					def newClassDescription= classDef?.newClassDescription
					classDef << ['existingClassDescription':newClassDescription]
					logger.info("new class definition mode is blank")
				}
				else if(mode.toUpperCase().equals("DELETE")){
					def newClassName= classDef?.existingClassName
					classDef << ['newClassName':newClassName]
				}
			}
			logger.info("CLASS DEFINATION AFTER UPDATE::: "+classDefinition)
			def rfpType = storedData?.extension?.rfpType
			def addproductRfpID
			if(rfpType.equals(GroupSetupConstants.ADD_PRODUCT)) {
				addproductRfpID = GroupSetupConstants.ADD_PRODUCT+"_"+storedData?.extension?.rfpId
			}
			List classDefList=new ArrayList()
			def classRfpTypeList=[] as List
			classDefinition.each { output->
				def rfpIndicator=output?.rfpType
				if(addproductRfpID !=null && !rfpIndicator.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS)) {
					classRfpTypeList.add(output?.rfpType)
				}
				String mode=output?.mode
				if(!(mode.equals("DELETE"))) {
					classDefList.add(output)
				}
			}
			def newExtension
			if(!classRfpTypeList.contains(addproductRfpID)) {
				newExtension=storedData?.extension
				newExtension.putAt("isBillingDone",true)
				requestBody<<['extension':newExtension]
			}
			groupStructure << ['classDefinition':classDefList]
			requestBody << ['groupStructure':groupStructure]
			
			entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupNumber, requestBody)
		}else{
			logger.error("Majesco save did not happened therefore new class is not updated:::")
		}
	}
	/**
	 *
	 * @param entityService
	 * @param result
	 * @param module
	 * @return
	 */
	private def saveIIBRequestPayload(entityService,result,module, groupId){
		MDC.put("SAVE_REQUEST_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try{
			result << ['_id':groupId]
			entityService.create(module.toString().toUpperCase()+"-IIB-REQUEST-PAYLOAD", result)
		}catch(any){
			logger.error("Error Occured while creating IIB REQUEST PAYLOAD IN LOCAL DB>>>>>>>${any.getMessage()}")
			//throw new GSSPException("40001")
		}
		MDC.put("SAVE_REQUEST_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}
	def noClaimsKickOutValidation(storedValue) {
		def isClaimsKickOut = false
		List description=storedValue.groupStructure.classDefinition
		logger.info("preparing static response for NoClaims STD or LTD block execution")
		description.each(){ desc ->
			List productList=desc?.products
			List jobTitle=desc?.classDescription?.jobTitle
			if(productList.contains("STD") || productList.contains("LTD")) {
				jobTitle.each(){ jobtitle->
					if((jobtitle?.title).equals("Board Of Directors") && jobtitle?.isChecked) {
						isClaimsKickOut=true
					}
				}
			}
		}
		logger.info("isClaimsKickOut for board of director::: "+isClaimsKickOut)
		isClaimsKickOut
	}

	def getProductListFromExtention(storedValue) {
		def productList = [] as List
		def products = storedValue?.extension?.products
		products.each() { output ->
			productList.add(output?.productCode)
		}
		productList
	}
	def isValidateCustomeClass(storedValue){
		def iscustomClass =false
		List description=storedValue.groupStructure.classDefinition
		description.each(){desc ->
			def customDescription=desc?.classDescription?.customDescription
			if(customDescription.equalsIgnoreCase("YES")) {
				iscustomClass=true
			}
		}
		iscustomClass
	}

	/**
	 *
	 * @param requestBody
	 * @param SSN
	 * @return
	 */
	def overrideRoleUniqueIdValueWithSSN(requestBody,SSN){
		def contactsList = requestBody?.groupStructure?.contact
		contactsList.each(){ contact ->
			contact?.roleTypes.each(){role ->
				role << ['roleUniqueId' : 'SSN']
				role << ['roleValue' : SSN]
			}
		}
		logger.info("contactsList::: "+contactsList)
	}
	/**
	 *
	 * @param provisionsMap
	 * @return
	 */
	//Defect#45497 fix start
	def overrideWaitingTimeProvisionValue(provisionsMap, product, prodDetails){
		logger.info("before update provisionsMap::: "+provisionsMap)
		if((provisionsMap.getAt("provisionValue").toString()).equalsIgnoreCase("Yes")){
			provisionsMap.putAt("provisionValue", "0")
		}
		if((provisionsMap.getAt("provisionValue").toString()).equalsIgnoreCase("No")){
			prodDetails.each{ prodDetail ->
				if(prodDetail?.provisionName.equalsIgnoreCase("waitingTime") && prodDetail?.products.contains(product))
					provisionsMap.putAt("provisionValue", prodDetail?.provisionValue)
			}
		}
		logger.info("After override provisions are::"+provisionsMap)
		provisionsMap
	}
	//Defect#45497 fix end
	//Defect#49651 fix start
	def defaultAveragedOverPeriodValue(provisionsMap){
		logger.info("before defaulting averagedOverPeriod provison is::: "+provisionsMap)
		if(provisionsMap && provisionsMap.getAt("provisionValue").toString().contentEquals("")){
			provisionsMap.putAt("provisionValue", "12")
		}
		logger.info("after defaulting averagedOverPeriod provison is::: "+provisionsMap)
		provisionsMap
	}
	//Defect#49651 fix end
	/**
	 * Getting Groupsetup data from mongodb.
	 * @param entityService
	 * @param brokerId
	 * @return
	 */
	def getGroupsetupData(collectionName, entityService, String id) {
		def result = null
		try{
			EntityResult entResult = entityService?.get(collectionName, id,[])
			result = entResult.getData()
			logger.info("result: ${result}")
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(any){
			logger.error("Error while getting checkDataAvailability by Id ---> "+any)
			//			throw new GSSPException("40001")
		}
		result
	}

	def identifyingDuplicateContacts(entityService, requestBody, groupNumber, module)
	{
		try{
			def mongoExistingCacheData = getGroupsetupData(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupNumber)
			def dbSubGroups = mongoExistingCacheData?.groupStructure?.subGroup
			logger.info("DB subgroups data :  ---> ${dbSubGroups}")
			if(module.equals(GroupSetupConstants.CLASS_DEFINITION))
				requestBody?.groupStructure?.putAt("subGroup", dbSubGroups)
			else if(module.equals(GroupSetupConstants.ASSIGN_CLASS)){
				List uiSubGroups = requestBody?.groupStructure?.subGroup
				logger.info("UI subgroups data :  ---> ${uiSubGroups}")
				if(dbSubGroups && uiSubGroups)
				{
					uiSubGroups.each { uisubGroup ->
						boolean isNewSubGroup = true
						def uisubGroupNumber = uisubGroup?.subGroupNumber
						def uibuildCaseStructure = uisubGroup?.buildCaseStructure
						def uiLocationDetails = uisubGroup?.buildCaseStructure.location
						def uiIsPrimaryAddress = uiLocationDetails?.isPrimaryAddress
						def uiLocationName = uiLocationDetails?.locationName
						def uiAddressLine1 = uiLocationDetails?.addressLine1
						def uiAddressLine2 = uiLocationDetails?.addressLine2
						dbSubGroups.each { dbsubGroup ->
							def dbsubGroupNumber = dbsubGroup?.subGroupNumber
							def dbbuildCaseStructure = dbsubGroup?.buildCaseStructure
							def dbLocationDetails = dbsubGroup?.buildCaseStructure.location
							def dbIsPrimaryAddress = dbLocationDetails?.isPrimaryAddress
							def dbLocationName = dbLocationDetails?.locationName
							def dbAddressLine1 = dbLocationDetails?.addressLine1
							def dbAddressLine2 = dbLocationDetails?.addressLine2
							if((uisubGroupNumber == dbsubGroupNumber) || (uiIsPrimaryAddress == "Yes" && (uiIsPrimaryAddress == dbIsPrimaryAddress)) || (uiLocationName.equalsIgnoreCase(dbLocationName) && uiAddressLine1.equalsIgnoreCase(dbAddressLine1) && uiAddressLine2.equalsIgnoreCase(dbAddressLine2))) // if location is matching then comparing contacts else considering as new location
							{
								uisubGroup.putAt("subGroupNumber", dbsubGroupNumber)
								isNewSubGroup = false
								List uicontacts = uibuildCaseStructure?.contacts
								def dbcontacts = dbbuildCaseStructure?.contacts
								uicontacts.each { uicontact ->
									def uiFName = uicontact?.firstName
									def uiLName = uicontact?.lastName
									def uiEmail = uicontact?.email
									dbcontacts.each { dbcontact ->
										def dbFName = dbcontact?.firstName
										def dbLName = dbcontact?.lastName
										def dbEmail = dbcontact?.email
										if(uiFName.equalsIgnoreCase(dbFName) && uiLName.equalsIgnoreCase(dbLName) && uiEmail.equalsIgnoreCase(dbEmail))
										{
											uicontact?.roleTypes.each { uiRoleObj ->
												def uiClientId = uiRoleObj?.clientId
												if(!uiClientId){
													dbcontact?.roleTypes.each { dbRoleObj ->
														if(uiRoleObj?.roleType.equalsIgnoreCase(dbRoleObj?.roleType))
														{
															def dbClientId = dbRoleObj?.clientId
															uiRoleObj.putAt("clientId", dbClientId)
														}
													}
												}
											}
										}
									}
									uicontact.putAt("isDeleted", "false")
								}
								//Identifying deleted contacts in this loaction
								def dbContactsDeletedList = [] as List
								dbcontacts.each { dbcontact ->
									boolean isDeletedContact = true
									def dbFName = dbcontact?.firstName
									def dbLName = dbcontact?.lastName
									def dbEmail = dbcontact?.email
									uicontacts.each { uicontact ->
										def uiFName = uicontact?.firstName
										def uiLName = uicontact?.lastName
										def uiEmail = uicontact?.email
										if(uiFName.equalsIgnoreCase(dbFName) && uiLName.equalsIgnoreCase(dbLName) && uiEmail.equalsIgnoreCase(dbEmail))
											isDeletedContact = compareContactRoles(dbcontact?.roleTypes, uicontact?.roleTypes)
									}
									if(isDeletedContact){
										dbcontact.putAt("isDeleted", "true")
										dbContactsDeletedList.add(dbcontact)
									}
								}
								//New & existing contacts already available in uicontacts list , adding deleted contact from db contacts.
								if(dbContactsDeletedList)
									uicontacts.addAll(dbContactsDeletedList)
								uibuildCaseStructure.putAt("contacts", uicontacts)
							}
						}
						if(isNewSubGroup)
						{
							uibuildCaseStructure?.contacts.each { contact ->
								contact.putAt("isDeleted", "false")
							}
						}
						uisubGroup.putAt("isDeleted", "false")
						uisubGroup.putAt("buildCaseStructure", uibuildCaseStructure)
					}
					// Identifying deleted subgroups/locations
					def dbSubGroupDeletedList = [] as List
					dbSubGroups.each { dbsubGroup ->
						boolean isdeletedSubGroup = true
						def dbsubGroupNumber = dbsubGroup?.subGroupNumber
						uiSubGroups.each { uisubGroup ->
							def uisubGroupNumber = uisubGroup?.subGroupNumber
							if(uisubGroupNumber == dbsubGroupNumber)
								isdeletedSubGroup = false
						}
						if(isdeletedSubGroup){
							dbsubGroup.putAt("isDeleted", "true")
							dbSubGroupDeletedList.add(dbsubGroup)
						}
					}
					if(dbSubGroupDeletedList)
						uiSubGroups.addAll(dbSubGroupDeletedList)
					logger.info("Finall  subgroups data to save in mongo db :  ---> ${uiSubGroups}")
					requestBody?.groupStructure?.putAt("subGroup", uiSubGroups)
					logger.info("Finall  requestBody data to save in mongo db :  ---> ${requestBody}")
				}
			}
		}catch(any){
			logger.error("Error while identifying duplicate contacts between UI & DB subgroups data. ---> "+any)
		}
	}
	def compareContactRoles(List dbRoleTypes, List uiRoleTypes)
	{
		if(dbRoleTypes.size() != uiRoleTypes.size())
			return true
		for(def dbRole : dbRoleTypes)
		{
			def isRoleMatched = false
			for(def uiRole : uiRoleTypes)
			{
				if(dbRole?.roleType.equalsIgnoreCase(uiRole?.roleType))
					isRoleMatched = true
			}
			if(!isRoleMatched)
				return true
		}
		return false
	}
	def updateLocationAndContactDetails(workFlow, registeredServiceInvoker, entityService, gsspRepository, groupSetUpId, spiPrefix, profile, tenantId)
	{
		try{
			def mongoExistingCacheData = getGroupsetupData(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId)
			def dbSubGroups = mongoExistingCacheData?.groupStructure?.subGroup
			logger.info("SubGroup details From mongodb....${dbSubGroups}")
			def groupNumber = groupSetUpId.split('_')[0]
			def spiHeadersMap = util.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
			def gropSetUpData = getGSData.getConsolidateData(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, profile)
			logger.info("GropSetUpData From IIB....${gropSetUpData}")
			def iibsubGroups = gropSetUpData?.groupStructure?.subGroup
			logger.info("SubGroup details From IIB....${dbSubGroups}")
			if(iibsubGroups && dbSubGroups)
			{
				dbSubGroups.each { dbsubGroup ->
					def dbsubGroupNumber = dbsubGroup?.subGroupNumber
					def dbbuildCaseStructure = dbsubGroup?.buildCaseStructure
					def dbLocationDetails = dbbuildCaseStructure.location
					def dbIsPrimaryAddress = dbLocationDetails?.isPrimaryAddress
					def dbLocationName = dbLocationDetails?.locationName
					def dbAddressLine1 = dbLocationDetails?.addressLine1
					def dbAddressLine2 = dbLocationDetails?.addressLine2
					iibsubGroups.each { iibsubGroup ->
						boolean isNewSubGroup = true
						def iibsubGroupNumber = iibsubGroup?.subGroupNumber
						def iibbuildCaseStructure = iibsubGroup?.buildCaseStructure
						def iibLocationDetails = iibsubGroup?.buildCaseStructure.location
						def iibIsPrimaryAddress = iibLocationDetails?.isPrimaryAddress
						def iibLocationName = iibLocationDetails?.locationName
						def iibAddressLine1 = iibLocationDetails?.addressLine1
						def iibAddressLine2 = iibLocationDetails?.addressLine2
						if((dbIsPrimaryAddress == "Yes" && (iibIsPrimaryAddress == dbIsPrimaryAddress)) || (iibLocationName.equalsIgnoreCase(dbLocationName) && iibAddressLine1.equalsIgnoreCase(dbAddressLine1) && iibAddressLine2.equalsIgnoreCase(dbAddressLine2))) // if location is matching then comparing contacts else considering as new location
						{
							dbsubGroup.putAt("subGroupNumber", iibsubGroupNumber)
							if(iibIsPrimaryAddress == dbIsPrimaryAddress && dbLocationName == "Primary Address")
							{
								dbLocationDetails?.putAt("locationName", iibLocationName)
								dbbuildCaseStructure?.putAt("location", dbLocationDetails)
							}
							def dbcontacts = dbbuildCaseStructure?.contacts
							def iibcontacts = iibbuildCaseStructure?.contacts
							dbcontacts.each { dbcontact ->
								def dbFName = dbcontact?.firstName
								def dbLName = dbcontact?.lastName
								def dbEmail = dbcontact?.email
								iibcontacts.each { iibcontact ->
									def iibFName = iibcontact?.firstName
									def iibLName = iibcontact?.lastName
									def iibEmail = iibcontact?.email
									if(iibFName.equalsIgnoreCase(dbFName) && iibLName.equalsIgnoreCase(dbLName) && iibEmail.equalsIgnoreCase(dbEmail))
									{
										dbcontact?.roleTypes.each { dbRoleObj ->
											def dbClientId = dbRoleObj?.clientId
											if(!dbClientId){
												iibcontact?.roleTypes.each { iibRoleObj ->
													if(dbRoleObj?.roleType.equalsIgnoreCase(iibRoleObj?.roleType))
													{
														def iibClientId = iibRoleObj?.clientId
														dbRoleObj.putAt("clientId", iibClientId)
													}
												}
											}
										}
									}
								}
							}
						}
					}
				}
				logger.info("Final SubGroup details after updating location & contact details....${dbSubGroups}")
				def buildCaseStructureList = [] as List
				dbSubGroups.each { sGroup ->
					def isDeleted = sGroup?.isDeleted
					if (isDeleted != "true")
						buildCaseStructureList.add(sGroup?.buildCaseStructure)
				}

				mongoExistingCacheData?.groupStructure?.putAt("buildCaseStructure", buildCaseStructureList)
				mongoExistingCacheData?.groupStructure?.putAt("subGroup", dbSubGroups)
				mongoExistingCacheData?.groupStructure?.locations[0].putAt("locationName", mongoExistingCacheData?.extension?.companyName)
				mongoExistingCacheData?.extension.putAt("isBillingDone", true)
				logger.info("mongoExistingCacheData details before updating mongodb....${mongoExistingCacheData}")
				gsspRepository.updateById(tenantId, GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetUpId, mongoExistingCacheData)
				gsspRepository.updateById(tenantId, GroupSetupConstants.PER_COLLECTION_NAME, groupSetUpId, mongoExistingCacheData)
			}
		}catch(any)
		{
			logger.error("Error while updating Location Id and contactid details. ---> "+any)
		}
	}
}
