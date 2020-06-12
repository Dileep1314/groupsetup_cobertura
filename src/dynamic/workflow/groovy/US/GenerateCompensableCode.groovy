package groovy.US

import java.time.Instant

import org.slf4j.MDC;
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.TokenManagementService
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration
import net.minidev.json.parser.JSONParser

/**
 * This class is using for Verify Licensing Status and Generating Compensable code.
 * @author Vishal
 *
 */

class GenerateCompensableCode implements Task{
	Logger logger = LoggerFactory.getLogger(this.class)
	public static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	def static final SERVICE_ID = "GCC007"

	@Override
	Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		logger.info("GenerateCompensableCode: execute() Start")
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def registeredServiceInvoker = workFlow.getBeanFromContext(GroupSetupConstants.REGISTERED_SERVICE_INVOKER , RegisteredServiceInvoker)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		Map<String, Object> requestPathParamsMap = workFlow.getRequestPathParams()
		Map<String, Object> requestBody = workFlow.getRequestBody()
		def requestBodyGbr = requestBodyGbr(requestBody)
		def requestBodyErl = requestBodyErl(requestBody)
		def profile = workFlow.applicationContext.environment.activeProfiles

		def groupSetUpId= requestPathParamsMap[GroupSetupConstants.GROUPSETUP_ID]
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetUpId.split('_')[0])
		logger.info("GenerateCompensableCode : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GenerateCompensableCode : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def productList=requestBody.getAt('productList')
		def isErl=requestBody.getAt("isErl")
		String writingProducerId =requestBody.getAt('writingProducerId')
		logger.info("GenerateCompensableCode requestBodyErl :${requestBodyErl} : requestBodyGbr:${requestBodyGbr}")
		def tokenService = workFlow.getBeanFromContext(GroupSetupConstants.TOKENMANAGEMENTSERVICE,
				TokenManagementService.class)
		def headerGbr =[:]
		def headerErl =[:]
		def compensableCodes=[]
		def token=tokenService.getToken()
		headerGbr << [
			'X-IBM-Client-Id': workFlow.getEnvPropertyFromContext('apmc.clientId'),
			'Authorization':token,
			'X-GSSP-Tracking-Id': workFlow.getEnvPropertyFromContext('apmc.X-Gssp-Tracking-Id'),
			'UserId': workFlow.getEnvPropertyFromContext('gbr.UserId'),
			'Password': workFlow.getEnvPropertyFromContext('gbr.Password'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('apmc.serviceId'),
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('apmc.tenantId')]
		headerErl << [
			'X-IBM-Client-Id': workFlow.getEnvPropertyFromContext('apmc.clientId'),
			'Authorization':token,
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('apmc.tenantId'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('apmc.serviceId')]
		logger.info("GenerateCompensableCode Fetched Headers Successfully. headerGbr :${headerGbr} : headerErl :${headerErl}")
		def demo=workFlow.getEnvPropertyFromContext('spi.switch')
		compensableCodes = generateCompensableCode(workFlow,demo,registeredServiceInvoker,headerErl,headerGbr,requestBodyGbr,requestBodyErl,productList,isErl)
		logger.info("GenerateCompensableCode:After getting response from SPI :: in execute() -> generateCompensableCode() response: ${compensableCodes}")
		def response=updateCompensableCode(compensableCodes,entityService,groupSetUpId,writingProducerId,isErl)
		if(response) {
			workFlow.addResponseBody(new EntityResult("resultInfo":response,true))
		}
		else {
			workFlow.addResponseBody(new EntityResult(["resultInfo":""],true))
		}
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${groupSetUpId} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
		logger.info("GenerateCompensableCode:  Service End")
	}
	/**
	 * This Method is used for Construct RequestBody for GBR Call
	 * @param requestBody
	 * @return
	 */
	def requestBodyGbr(requestBody){
		def requestBodyGbr=[:]
		requestBodyGbr << ['personGiven1Name': requestBody.getAt(GroupSetupConstants.PERSON_GIVEN1_NAME)]
		requestBodyGbr << ['personLastName': requestBody.getAt(GroupSetupConstants.PERSON_LAST_NAME)]
		requestBodyGbr << ['governmentID': requestBody.getAt('governmentID')]
		requestBodyGbr << ['governmentIDTypeCode': 'SSN']
		requestBodyGbr << ['sfdcRecordsFilterIndicator': 'true']
		requestBodyGbr << ['transactionSubTypeCode': 'C']
		requestBodyGbr << ['lineofBusinessCode': 'INST']
		requestBodyGbr << ['compensableIndicator': 'Y']
		requestBodyGbr << ['sourceGroupName':'14']
		requestBodyGbr << ['sourceSystemCode': '14']
		requestBodyGbr << ['sourceSystemName': 'MGI']
		requestBodyGbr << ['offset':'1']
		requestBodyGbr << ['limit': '49']
		requestBodyGbr
	}
	/**
	 * This Method is used for Construct RequestBody for ERL Call
	 * @param requestBody
	 * @return
	 */
	def requestBodyErl(requestBody){
		def requestBodyErl=[:]
		requestBodyErl << ['governmentID': requestBody.getAt('governmentID')]
		requestBodyErl << ['governmentIDTypeCode':"S"]
		requestBodyErl << ['policycarrierCode': GroupSetupConstants.POLICY_CARRIER_CODE_MLI]
		requestBodyErl << ['applicationRegionCode': requestBody.getAt('applicationRegionCode')]
		requestBodyErl << ['applicationSignDate': requestBody.getAt('applicationSignDate')]
		requestBodyErl << ['applicationStatusCode': 'COMP']
		requestBodyErl << ['relationshipRoleCode': '37']
		requestBodyErl
	}
	/**
	 * This Method is used for Verify Licensing Appointment and Generating Compensable Code
	 * @param spiPrefixGbr
	 * @param spiPrefixErl
	 * @param registeredServiceInvoker
	 * @param headerErl
	 * @param headerGbr
	 * @param requestBodyGbr
	 * @param requestBodyErl
	 * @return
	 */
	def generateCompensableCode(workFlow,demo,registeredServiceInvoker, headerErl,headerGbr,requestBodyGbr,requestBodyErl,productList,isErl) {
		List verifyStatus=[]
		List lincensingComp=[]
		if(GroupSetupConstants.TRUE.equals(isErl)) {
			if(demo!=null && demo.equals("demo")){
				verifyStatus=mockSSNVerifyStatus(requestBodyErl?.governmentID)
			}else {
				verifyStatus=postSPIErl(workFlow, registeredServiceInvoker, headerErl,requestBodyErl,productList)
			}
			if(verifyStatus==null){
				logger.error("GenerateCompensableCode : Error retrieving  Verify Status from ERL via SPI")
			}
		}
		else if(GroupSetupConstants.FALSE.equals(isErl)){
			lincensingComp=postSPIGbr(workFlow, registeredServiceInvoker, headerGbr,requestBodyGbr)
		}
		[lincensingComp, verifyStatus]
	}
	/**
	 * This Method is used to Call GBR via SPI
	 * @param spiPrefixGbr
	 * @param registeredServiceInvoker
	 * @param headerGbr
	 * @param requestBodyGbr
	 * @return
	 */

	def postSPIGbr(workFlow,registeredServiceInvoker,headerGbr,requestBodyGbr){
		def gbrUri
		def gbrResponseBody
		List licensingComp=[]
		def spiPrefixGbr = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_GBRprefix)
		gbrUri = "${spiPrefixGbr}/searches/brokers"
		def uriBuilderGbr = UriComponentsBuilder.fromPath(gbrUri)
		def serviceUriGbr = uriBuilderGbr.build(false).toString()
		logger.info("GenerateCompensableCode:generateCompensableCode() :: serviceUri ->${serviceUriGbr} uriBuilder : ${uriBuilderGbr} gbrUri: ${gbrUri} headerGbr -> ${headerGbr}")
		def responseGbr
		HttpEntity<List> requestGbr
		try {
			requestGbr = registeredServiceInvoker.createRequest(requestBodyGbr,headerGbr)
			logger.info("${SERVICE_ID} ==== Calling Metlife GBR API: ${serviceUriGbr} ")
			Date start = new Date()
			responseGbr = registeredServiceInvoker.postViaSPI(serviceUriGbr, requestGbr, Map.class)
			Date stop = new Date()
			TimeDuration elapseTime = TimeCategory.minus(stop, start)
			logger.info("${SERVICE_ID} ==== Metlife GBR API: ${serviceUriGbr}, Response time : "+ elapseTime)
			logger.info("GenerateCompensableCode:generateCompensableCode() spiResponseContent: ${responseGbr}")
			gbrResponseBody= responseGbr.getBody()
			def compensableUser=gbrResponseBody?.Items
			compensableUser.each() { compensable ->
				def brokerId=compensable?.brokerID
				boolean bkrOrBkcCode=checkBrokerId(brokerId)
				if(bkrOrBkcCode) {
					def brokers
					def directPayIndicator=compensable?.directPayIndicator
					def addressList=[] as List
					try {
						def addres= compensable?.Addresses
						addres.each(){ address->
							if(GroupSetupConstants.BUSINESS.equals(address?.addressTypeCode)) {
								addressList.add(address)
							}
							if(GroupSetupConstants.PAYMENT.equals(address?.addressTypeCode)) {
								addressList.add(address)
								directPayIndicator=false
							}
						}
						brokers =retriveBroker(spiPrefixGbr, registeredServiceInvoker,headerGbr,brokerId)
					}catch(any ) {
						if(any.errorCode.is('GBR_BACKEND_EXCEPTION')) {
							compensable<<['organizationName':""]
						}
					}
					if(brokers) {
						def brokerageID=brokers?.brokerage?.brokerageDetail?.brokerageID
						if(brokerageID) {
							try {

								def brokerage=retriveBrokerage(spiPrefixGbr, registeredServiceInvoker,headerGbr,brokerageID)
								def organizationName=brokerage?.names[0].organizationName
								compensable<<['organizationName':organizationName]
								def addresses= brokerage?.addresses
								logger.info("----Broker Address-----: ${addresses}")
								if(addresses) {
									addresses.each(){ address ->
										if(GroupSetupConstants.PAYMENT.equals(address?.addressTypeCode)){
											addressList.add(address)
										}
									}
								}
							}catch(any) {
								compensable<<['organizationName':""]
							}
						}
						else {
							compensable<<['organizationName':""]
						}
					}
					compensable<<['Addresses':addressList]

				}else {
					logger.info("No BKC or BKC or Less than 10 digit number return from GBR")
				}
			}
			logger.info("GBR Repsone after json Constract ->${compensableUser}")
			licensingComp=constructJason(compensableUser)
		} catch(HttpClientErrorException e) {
			ResponseEntity response = new ResponseEntity(e.getResponseBodyAsString(), e.getStatusCode())
			JSONParser parser = new JSONParser()
			String errorCode = parser.parse(response?.body).errors[0].providerCode
			def statusCode=response.statusCode
			if(statusCode.equals('400')) {
				licensingComp.add('')
			}
			logger.error("Error Occured in while calling GBR--->${e.getMessage()}")
			//throw new GSSPException('GBR_BACKEND_EXCEPTION')
		}catch (any) {
			logger.error("GenerateCompensableCode:Error retrieving Compensable code  from SPI ${any.getMessage()}")
			throw new GSSPException('400013')
		}
		logger.info("Final GBR response-->${licensingComp}")
		licensingComp
	}

	def checkBrokerId(brokerId) {
		String bkrOrBkcCode=brokerId
		int length=bkrOrBkcCode.length()
		logger.info("Compensable code from GBR-->${bkrOrBkcCode}")
		boolean matchBkrOrBkc
		if(length==10) {

			if(bkrOrBkcCode.contains("BKR")) {
				matchBkrOrBkc=true
			}else {
				matchBkrOrBkc=false
			}
		}
		matchBkrOrBkc
	}
	/**
	 * This Method is Used to call GBR to get Single Broker Info
	 * @param spiGetPrefixBroker
	 * @param registeredServiceInvoker
	 * @param headerGbr
	 * @param brokerId
	 * @return
	 */
	def retriveBroker(GBRprefix,registeredServiceInvoker,headerGbr,brokerId) {
		logger.info('Request End points to GBR System :----->'+headerGbr +" GBRprefix :"+GBRprefix +"brokerId :"+brokerId)
		def endpoint = "${GBRprefix}"+"/brokers/"+"${brokerId}"
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		uriBuilder.queryParam("limit","5")
		uriBuilder.queryParam("lineofBusinessCode","INST")
		uriBuilder.queryParam("sourceSystemCode","MGI")
		uriBuilder.queryParam("sourceGroupName","14")
		uriBuilder.queryParam("transactionSubTypeCode","C")
		uriBuilder.queryParam("offset","1")
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('Request End points to GBR System :-->'+serviceUri)
		def response
		try{
			headerGbr=getRequiredHeaders(headerGbr)
			response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], headerGbr)
			response = response?.getBody()
			logger.info("RetriveBroker response--->${response}")
		}catch(e) {
			logger.error("Error retrieving broker reponse from GBR  ${e.message}")
			throw new GSSPException("GBR_BACKEND_EXCEPTION")
		}
		response
	}
	/**
	 * This Method is Used to call GBR to get Single Brokerage Info
	 * @param spiGetPrefixBrokarage
	 * @param registeredServiceInvoker
	 * @param headerGbr
	 * @param brokargeId
	 * @return
	 */
	def retriveBrokerage(GBRprefix, registeredServiceInvoker,headerGbr,brokerageID) {

		def endpoint = "${GBRprefix}/brokerages/${brokerageID}"
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		uriBuilder.queryParam("limit","5")
		uriBuilder.queryParam("lineofBusinessCode","INST")
		uriBuilder.queryParam("sourceSystemCode","14")
		uriBuilder.queryParam("sourceGroupName","6")
		uriBuilder.queryParam("transactionSubTypeCode","IND")
		uriBuilder.queryParam("offset","1")
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('Request End points to GBR System :----->'+headerGbr +" GBRprefix :"+GBRprefix +"brokerageID : "+brokerageID +"serviceUri "+serviceUri)
		def response
		try{
			headerGbr=getRequiredHeaders(headerGbr)
			response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], headerGbr)
			response = response?.getBody()
			logger.info ("Sucessfully Retrieved Brokerage response from GBR..."+response)
		}catch(e) {
			logger.error("Error retrieving brokerage reponse from GBR  ${e.message}")
			throw new GSSPException("GBR_BACKEND_EXCEPTION")
		}
		response
	}
	def getRequiredHeaders(headersList) {
		def headerKey= headersList.keySet()
		def spiHeaders = [:]
		headerKey.each(){header->
			spiHeaders << [(header): [headersList[header]]]
		}
		spiHeaders
	}
	/**
	 *  This Method is used to Call ERL via SPI
	 * @param spiPrefixErl
	 * @param registeredServiceInvoker
	 * @param headerErl
	 * @param requestBodyErl
	 * @return
	 */

	def postSPIErl(workFlow,registeredServiceInvoker, headerErl,requestBodyErl,productList){

		def erlUri
		List verifyStatus=[]
		def spiPrefixErl = workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_ERLprefix)
		erlUri = "${spiPrefixErl}/brokers/licenseAppointment/validation"
		def uriBuilderErl = UriComponentsBuilder.fromPath(erlUri)
		def serviceUriErl = uriBuilderErl.build(false).toString()
		logger.info("postSPIErl() :: serviceUri ->${serviceUriErl} erlUri: ${erlUri} uriBuilderErl: ${uriBuilderErl}")
		def responseErl
		def responseListErl=[]
		responseListErl
		HttpEntity<List> requestErl
		try {
			logger.info("GenerateCompensableCode:postSPIErl() spiResponseContent: ${requestErl}")
			Set mappedProduct=productMapping(productList)
			logger.info("postSPIErl() :: Mapped product ->${mappedProduct}")
			boolean notFound=true
			for(String product : mappedProduct) {
				requestBodyErl<<['productFamilyCode':product]
				requestErl = registeredServiceInvoker.createRequest(requestBodyErl,headerErl)
				logger.info("${SERVICE_ID} ----> ${mappedProduct} ----> ${product} ==== Calling Metlife ERL API: ${serviceUriErl} ")
				Date start = new Date()
				responseErl = registeredServiceInvoker.postViaSPI(serviceUriErl, requestErl, Map.class)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${mappedProduct} ----> ${product} ==== Metlife ERL API: ${serviceUriErl}, Response time : "+ elapseTime)
				logger.info("Response from ERL with body :  ${responseErl.getBody()}")
				responseListErl.add(responseErl?.getBody())
				if((responseErl?.getBody())?.errors) {
					logger.info("Error responseErl ${responseErl?.getBody()}")
					//JSONParser parser = new JSONParser()
					def err= responseErl?.getBody()?.errors[0]
					String errorCode=""+responseErl?.getBody()?.errors[0]?.providerCode
					if(errorCode.equals("9910") || errorCode.equals("9902") || errorCode.equals("9903")) {
						verifyStatus.add(GroupSetupConstants.NOT_FOUND)
						verifyStatus.add("")
						verifyStatus.add("")
						notFound=false
						break
					}
				}
				if(notFound) {
					verifyStatus=constructStatus(responseListErl)
				}
			}
		}
		catch(HttpClientErrorException e) {
			ResponseEntity response = new ResponseEntity(e.getResponseBodyAsString(), e.getStatusCode())
			JSONParser parser = new JSONParser()
			// String errorCode = parser.parse(response?.body).errors[0].providerCode
			def statusCode=response.statusCode
			if(statusCode.equals('400')) {
				verifyStatus.add('')
			}
			logger.error("Error Occured in while calling ERL--->${e.getMessage()}")
			//throw new GSSPException('GBR_BACKEND_EXCEPTION')
		}
		catch(any) {
			logger.error("Error retrieving Verify status reponse from ERL  ${any}")
			throw new GSSPException('400013')
		}
		logger.info("VerifyStatus :  ${verifyStatus}")
		verifyStatus
	}
	/**
	 *
	 * @param productList
	 * @return
	 */
	def productMapping(productCode){
		Set productSet =[]
		productCode.each(){ product ->
			String productName=product
			if(productName.contains(GroupSetupConstants.BASIC_LIFE_AD_D)||productName.contains(GroupSetupConstants.BASIC_DEPENDENT_LIFE) || productName.contains(GroupSetupConstants.SUPPLEMENTAL_LIFE_OADD) || productName.contains(GroupSetupConstants.SUPPLEMENTAL_DEPENDENT_LIFE)) {
				productSet.add(GroupSetupConstants.TERM)
			}else if(productName.contains(GroupSetupConstants.DENTAL_DHMO) || productName.contains(GroupSetupConstants.DENTAL_PPO)) {
				productSet.add(GroupSetupConstants.DENT)
			}else if(productName.contains(GroupSetupConstants.ACCIDENT)) {
				productSet.add(GroupSetupConstants.AH)
			}else if(productName.contains(GroupSetupConstants.SHORT_TERM_DISABILITY)) {
				productSet.add(GroupSetupConstants.DIST)
			}else if(productName.contains(GroupSetupConstants.VISION)) {
				productSet.add(GroupSetupConstants.VISN)
			}else if(productName.contains(GroupSetupConstants.CRITICAL_ILLNESS)) {
				productSet.add(GroupSetupConstants.CRIL)
			}else if(productName.contains(GroupSetupConstants.HOSPITAL_INDEMNITY)) {
				productSet.add(GroupSetupConstants.HI)
			}else if(productName.contains(GroupSetupConstants.LONG_TERM_DISABILITY)) {
				productSet.add(GroupSetupConstants.DILT)
			/*}else if(productName.contains(GroupSetupConstants.MET_LAW)) {
				productSet.add(GroupSetupConstants.MET_LAW)*/
			}else {
				logger.error("ProductMapping ::  Invalid Product--->${product}")
			}
		}
		productSet
	}
	/**
	 * This Method is used to construct json of Compensable code
	 * @param gbrResponseBody
	 * @return
	 */
	def constructJason(gbrResponseBody){
		List compensableCode=[]
		def item=gbrResponseBody
		int sequenceId=0
		item.each(){ compensable ->
			def compensableCodes=[:]
			String it = sequenceId++
			compensableCodes<<['producerName':compensable?.name?.personGiven1Name+" "+compensable?.name?.personLastName]
			compensableCodes<<['brokerCode':compensable?.brokerID]
			compensableCodes<<['isInformationInaccurate': GroupSetupConstants.FALSE]
			compensableCodes<<['isSelected': GroupSetupConstants.FALSE]
			compensableCodes<<['sequenceId': it]
			compensableCodes<<['organizationName' : compensable?.organizationName]  //Not Getting in Response
			def addresses=compensable?.Addresses
			boolean pflag = true
			boolean bflag = true
			addresses.each (){ addressGbr->
				def addressBusiness=[:]
				def addressPayment=[:]
				if(GroupSetupConstants.BUSINESS.equals(addressGbr?.addressTypeCode)){
					bflag=false
					addressBusiness<<["addressLine1":addressGbr?.address1Line]
					addressBusiness<<["addressLine2":addressGbr?.address2Line]
					addressBusiness<<["city":addressGbr?.cityName]
					addressBusiness<<["state":addressGbr?.extension?.stateCode]
					addressBusiness<<["zip":addressGbr?.extension?.zipDeliveryOfficeCode]
					compensableCodes<<['businessAddress':addressBusiness]
				}

				else if(GroupSetupConstants.PAYMENT.equals(addressGbr?.addressTypeCode)){
					pflag= false
					addressPayment<<['addressLine1':addressGbr?.address1Line]
					addressPayment<<['addressLine2':addressGbr?.address2Line]
					addressPayment<<['city':addressGbr?.cityName]
					addressPayment<<['state':addressGbr?.extension?.stateCode]
					addressPayment<<['zip':addressGbr?.extension?.zipDeliveryOfficeCode]
					compensableCodes<<['paymentAddress':addressPayment]
				}

			}
			if(pflag) {
				def paymentAddress=[:]
				compensableCodes<<['paymentAddress':paymentAddress]
			}
			if(bflag) {
				def addressBusiness =[:]
				compensableCodes<<['businessAddress':addressBusiness]
			}

			compensableCode.add(compensableCodes)
		}
		compensableCode
	}
	/**
	 * This Method is used to construct json of Verify Status
	 * @param brokerValidationResponse
	 * @return
	 */
	def constructStatus(brokerValidationResponse) {
		List status=[]
		def licenseNumber
		def finalStatus
		List sponsorship =[]
		def sponsorshipValue="false"
		brokerValidationResponse.each(){ output ->
			def isSponsorship
			def statusCodes = output?.resultInfo?.statusCode
			logger.info("VerifyBrokerLicense: statusCodes ${statusCodes}")
			if(statusCodes.toString().length()==5){
				String statusDesc = output?.resultInfo?.statusCodeDescription
				//def SmallBuissinessCenter = statusCode.toString().charAt(4)
				if(statusDesc.contains(GroupSetupConstants.SMALL_BUSINESS_CENTRE)){
					isSponsorship=GroupSetupConstants.TRUE
				}else {
					isSponsorship=GroupSetupConstants.FALSE
				}
			}
			licenseNumber = output?.producerLicense
			String appointment = statusCodes.toString().charAt(0)
			String liscense = statusCodes.toString().charAt(1)
			if (appointment == GroupSetupConstants.ZERO && liscense == GroupSetupConstants.ZERO) {
				statusCodes = GroupSetupConstants.ACTIVE
			}
			else if (appointment == GroupSetupConstants.FOUR || liscense == GroupSetupConstants.FOUR) {
				statusCodes = GroupSetupConstants.EXPIRED
			}
			else if (appointment == GroupSetupConstants.FIVE || liscense == GroupSetupConstants.FIVE) {
				statusCodes = GroupSetupConstants.NA
			}
			else if((appointment.matches("0") && liscense.matches("1|2|3")) || (appointment.matches("1") && liscense.matches("0|1|2|3")) || (appointment.matches("2") && liscense.matches("0|1|2|3")) || (appointment.matches("3") && liscense.matches("0|1|2|3")) ){
				statusCodes = GroupSetupConstants.NOT_ACTIVE
			}
			status.add(statusCodes)
			sponsorship.add(isSponsorship)
			logger.info("VerifyBrokerLicense: statusCodes${status}")
		}
		if(status !=null && status.every({ it.equals(GroupSetupConstants.ACTIVE) })){
			finalStatus=GroupSetupConstants.ACTIVE
		}
		else if(status !=null && (status.contains(GroupSetupConstants.EXPIRED) || status.contains(GroupSetupConstants.ACTIVE)) && !status.contains(GroupSetupConstants.NOT_ACTIVE)){
			finalStatus=GroupSetupConstants.EXPIRED
		}
		else if(status !=null && status.every({ it.equals(GroupSetupConstants.NOT_FOUND) })){
			finalStatus=GroupSetupConstants.NOT_FOUND
		}
		else if(status !=null && (status.contains(GroupSetupConstants.NOT_ACTIVE))){
			finalStatus=GroupSetupConstants.NOT_ACTIVE
		}
		if(sponsorship.contains(GroupSetupConstants.TRUE)){
			sponsorshipValue=GroupSetupConstants.TRUE
		}
		logger.info("VerifyBrokerLicense: final Status : ${finalStatus}")
		[finalStatus, licenseNumber, sponsorshipValue]
	}
	/**
	 * This Method is used for updating mongo with latest data from GBR and ERL
	 * @param compensableCodes
	 * @param entityService
	 * @param groupSetUpId
	 * @param writingProducerId
	 * @return
	 */
	def updateCompensableCode(compensableCodes,entityService,groupSetUpId,writingProducerId,isErl){
		def conpensableList= compensableCodes[0]
		def verifyStatus=compensableCodes[1]
		def status=verifyStatus[0]
		def lincenseNumber=verifyStatus[1]
		def sponsorship=verifyStatus[2]
		def licensingCompensableCodes
		def writingProducer
		try{
			EntityResult dbresult= entityService.get(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetUpId,[])
			def data=dbresult?.getData()
			def licensingCompensableCode=data?.licensingCompensableCode
			def writingProducerList=licensingCompensableCode?.writingProducers
			writingProducerList.each(){ producerValue ->
				String id=""+producerValue.writingProducerId
				String writingPId=writingProducerId
				if(id==writingPId){
					if(GroupSetupConstants.TRUE.equals(isErl)){
						producerValue.putAt(GroupSetupConstants.VERIFY_STATUS_CODE, status)
						producerValue.putAt(GroupSetupConstants.LICENSE_NUMBER, lincenseNumber)
						producerValue.putAt(GroupSetupConstants.SPONSORSHIP, sponsorship)
						writingProducer = producerValue
					}else{
						producerValue.putAt(GroupSetupConstants.COMPENSABLE_CODE,conpensableList)
						writingProducer = producerValue
					}
				}
			}
			licensingCompensableCodes=['licensingCompensableCode':licensingCompensableCode]
			entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetUpId, licensingCompensableCodes)
		}catch(AppDataException e){
			logger.error("Data not found ---> ${e.getMessage()}")
		}catch(any){
			logger.error("Error Occured in updateCompensableCode--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		if(writingProducer)
			writingProducer<<["isErl":isErl]
		writingProducer
	}
	/**
	 *
	 * @param governmentID
	 * @return
	 */
	def mockSSNVerifyStatus(governmentID){
		List verifyStatus=[]
		if(governmentID.equals("001367401") || governmentID.equals("002401682")){
			verifyStatus.add(GroupSetupConstants.ACTIVE);
			verifyStatus.add("26371");
			verifyStatus.add("");
		}else if(governmentID.equals("329879234")){
			verifyStatus.add(GroupSetupConstants.NOT_ACTIVE);
			verifyStatus.add("");
			verifyStatus.add("");
		}else if(governmentID.equals("022405385")){
			verifyStatus.add(GroupSetupConstants.EXPIRED);
			verifyStatus.add("");
			verifyStatus.add("");
		}
		else if(governmentID.equals("001308594")){
			verifyStatus.add(GroupSetupConstants.NOT_FOUND);
			verifyStatus.add("");
			verifyStatus.add("");
		}
		verifyStatus
	}
}
