
package groovy.US

import java.time.Instant

import org.slf4j.MDC
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus
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
import com.metlife.service.entity.EntityService

import groovy.time.TimeCategory
import groovy.time.TimeDuration
import net.minidev.json.parser.JSONParser

/**
 *  Call this method while click on landing page start button.
 * @author Durgesh Kumar Gupta
 *
 */
class FrameConsolidateData implements Task{

	Logger logger = LoggerFactory.getLogger(FrameConsolidateData.class)
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	def static final COLLECTION_NAME = "GroupSetupData"
	GroupSetupUtil utilObject = new GroupSetupUtil()
	FramePartyTypeWritingProducerDetails frameObj = new FramePartyTypeWritingProducerDetails()
	def static final SERVICE_ID = "GSFCD002"

	@Override
	public Object execute(WorkflowDomain workFlow) {
		Date start1 = new Date()
		GroupSetupDataMapping dataMapping= new GroupSetupDataMapping()
		MergeNewBusinessAddCoverageRFPDetails mergedRFPSDetails = new MergeNewBusinessAddCoverageRFPDetails()
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestBody = workFlow.getRequestBody()
		def tenantId = requestPathParamsMap['tenantId']
		def selectProposal=requestBody['selectedProposal']
		def brokerData=requestBody['brokerData']
		def rfpId= selectProposal?.rfpId
		def proposalId=selectProposal?.uniqueId
		def groupNumber=selectProposal?.groupNumber
                logger.info("****************** FrameConsolidateData*****************")
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupNumber)
		logger.info("FrameConsolidateData : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("FrameConsolidateData : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def spiPrefix= workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		def cacheId=groupNumber+"_"+rfpId+"_"+proposalId
		def mappedData
		def isTempCacheExist = false
		def partyTypeDetails = selectProposal['partyTypeDetails']
		logger.info("****************** partyTypeDetails :::: "+partyTypeDetails)
		def mongoExistingCacheData = checkDataAvailability(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, cacheId)
		def mongoExistingPerData = checkDataAvailability(GroupSetupConstants.PER_COLLECTION_NAME, entityService, cacheId)
		if(mongoExistingCacheData)
			isTempCacheExist = true
//		def productList =  retreiveDataFromIIB(registeredServiceInvoker, spiPrefix,spiHeadersMap, rfpId, proposalId, profile)
		def tempData
		if(mongoExistingCacheData && !"".equalsIgnoreCase(mongoExistingCacheData?.extension?.module) 
			&& ("Pending Review".equalsIgnoreCase(mongoExistingCacheData?.extension?.actualStatus) ||"PendingReview".equalsIgnoreCase(mongoExistingCacheData?.extension?.actualStatus)) 
			&& (GroupSetupConstants.NEW_BUSINESS.equalsIgnoreCase(mongoExistingCacheData?.extension?.rfpType))){
			tempData=mongoExistingCacheData
			deleteCacheData(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,entityService,cacheId)
			mongoExistingCacheData=null
		}
		if(!(mongoExistingCacheData && mongoExistingPerData)) {
			def productList =  retreiveDataFromIIB(registeredServiceInvoker, spiPrefix,spiHeadersMap, rfpId, proposalId, profile)
			def nonRatedProvisionList = dataMapping.retreiveNonRatedProvisions()
			mappedData = createDataforCache(selectProposal,brokerData,productList,nonRatedProvisionList,cacheId)
			if(!mongoExistingPerData && !mongoExistingCacheData && partyTypeDetails)  // This block will execute for FramePartyTypeWritingProducerDetails when data not available in Cache & Permanent collection means frist request for Framing
			{
				def situsState = mappedData?.clientInfo?.basicInfo?.primaryAddress?.situsState
				//def effectiveDate = mappedData?.clientInfo?.basicInfo?.effectiveDate
                def effectiveDate = selectProposal['effectiveDate']
				logger.info("****************** Groupsetup data not present in Permanent & Cache :::: execting if condition")
				frameObj.updateDBWithPartyTypeWritingProducerDetails(workFlow, registeredServiceInvoker, mappedData, partyTypeDetails, productList, situsState, effectiveDate)
			} else if(mongoExistingPerData && !mongoExistingCacheData){
				logger.info("****************** Groupsetup data available in perminante collection :::: execting else condition")
				mappedData?.extension?.put("module", mongoExistingPerData?.extension?.module)
				mappedData?.extension?.put("kickOutIdentifer", mongoExistingPerData?.extension?.kickOutIdentifer)
				mappedData?.extension?.put("isKickOut", mongoExistingPerData?.extension?.isKickOut)
				updatePermanentDB(entityService,tempData,cacheId)
				mappedData = dataMapping.getSubmittedData(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, proposalId,profile, mappedData, tempData)
				//deleteCacheData(GroupSetupConstants.PER_COLLECTION_NAME,entityService,cacheId)
				//mongoExistingPerData = null
			}
		}else if(partyTypeDetails) // This block only for updating partyType details
		{
			logger.info("****************** Permanent & Cache both have Groupsetup data :::: execting elseif condition")
			try
			{
				def situsState = mongoExistingCacheData?.clientInfo?.basicInfo?.primaryAddress?.situsState
				//def effectiveDate = mongoExistingCacheData?.clientInfo?.basicInfo?.effectiveDate
                def effectiveDate = selectProposal['effectiveDate']
				def licensingCompensableCode = mongoExistingCacheData?.licensingCompensableCode
				logger.info("**************  licensingCompensableCode :: ${licensingCompensableCode}")
				boolean fetchPartyTypeDetails = gatpaWritingProducerexist(licensingCompensableCode)
				logger.info("**************  fetchPartyTypeDetails :: ${fetchPartyTypeDetails}")
				if(fetchPartyTypeDetails)
					prepopulateWritingProducerDetails(licensingCompensableCode, workFlow, registeredServiceInvoker, partyTypeDetails, entityService, cacheId, productList, situsState, effectiveDate)
			}
			catch (e){
				logger.error("Error while Framing PartyType details as WritingProducer in FrameConsolidateData "+e.message)
			}
		}

		if(mappedData){
			GroupSetupRequoteDataMapping requoteDataMapping = new GroupSetupRequoteDataMapping()
			mappedData= requoteDataMapping.updateDataForRequote(entityService,mappedData,groupNumber,rfpId)
		}
		/* US - 20320 */
		if(mongoExistingCacheData){
			updateEmployerRegisteredFlag(mongoExistingCacheData, gsspRepository, entityService, cacheId)
		}
        mongoExistingCacheData?.extension?.put("actualStatus", selectProposal?.actualStatus)// as part of prod fix
		updateMongoCache(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,brokerData, mongoExistingCacheData, mappedData, entityService, cacheId,selectProposal)
        mongoExistingPerData?.extension?.put("actualStatus", selectProposal?.actualStatus)// as part of prod fix
		updateMongoCache(GroupSetupConstants.PER_COLLECTION_NAME,brokerData,mongoExistingPerData, mappedData, entityService, cacheId,selectProposal)
		//'AddCoverage Maintenance changes -- Begin'
		logger.info("NEWBUSINESS rfp PartyTypeDetails merging with 'ADDPRODUCT' rfp PartyTypeDetails..")
		def currentDBRecord = checkDataAvailability(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, cacheId)
		logger.info("currentDBRecord==>: " + currentDBRecord)
		if(GroupSetupConstants.ADD_PRODUCT.equalsIgnoreCase(currentDBRecord?.extension?.rfpType)){
			mergedRFPSDetails.getMergedRfpsDetails(cacheId, currentDBRecord, gsspRepository, entityService, isTempCacheExist)
			logger.info("NEWBUSINESS rfp PartyTypeDetails merging with 'ADDPRODUCT' rfp PartyTypeDetails..")
		}
		//'AddCoverage Maintenance changes --  Ends'
		//updateMongoCache(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,brokerData, mongoExistingCacheData, mappedData, entityService, cacheId,selectProposal)
		//updateMongoCache(GroupSetupConstants.PER_COLLECTION_NAME,brokerData,mongoExistingPerData, mappedData, entityService, cacheId,selectProposal)
		workFlow.addResponseBody(new EntityResult(['Status': 'Success'], true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop1 = new Date()
		TimeDuration elapseTime1 = TimeCategory.minus(stop1, start1)
		logger.info("${SERVICE_ID} ----> ${cacheId} === MS api elapseTime : " + elapseTime1)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	private updateEmployerRegisteredFlag(mongoExistingCacheData, GSSPRepository gsspRepository, EntityService entityService, String cacheId) {
		try{
			def writingProducers=mongoExistingCacheData?.licensingCompensableCode?.writingProducers
			for(def wp:writingProducers)
			{
				if(GroupSetupConstants.ROLE_EMPLOYER.equalsIgnoreCase(wp?.roleType) && !GroupSetupConstants.TRUE.equalsIgnoreCase(wp?.isRegistered))
				{
					Query query = new Query()
					query.addCriteria(Criteria.where("user.name.personGiven1Name").is(wp?.firstName).
							and("user.name.personLastName").is(wp?.lastName).
							and("user.electronicContactPoints.electronicContactPointValue").is(wp?.email))
					def result = gsspRepository.findByQuery("US", GroupSetupConstants.COLLECTION_CUSTOMERS, query)
					if(result!=null && !(result.isEmpty()))
						wp.putAt("isRegistered", GroupSetupConstants.TRUE)
				}
			}
			mongoExistingCacheData?.licensingCompensableCode?.putAt("writingProducers", writingProducers)
			entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, cacheId, mongoExistingCacheData)
			entityService.updateById(GroupSetupConstants.PER_COLLECTION_NAME, cacheId, mongoExistingCacheData)
		}catch(any){
			logger.error("Error while updating isRegisterd flag in Employer Writing producer details ---> "+any)
		}
	}

	def  gatpaWritingProducerexist(licensingCompensableCode) {
		boolean fetchPartyTypeDetails = true
		def writingProducers = licensingCompensableCode?.writingProducers
		writingProducers.each{ wproducer ->
			def roleType = wproducer?.roleType
			if(roleType == "General Agent" || roleType == "Third Party Administrator")
				fetchPartyTypeDetails = false
		}
		fetchPartyTypeDetails
	}

	def prepopulateWritingProducerDetails(licensingCompensableCode, WorkflowDomain workFlow, RegisteredServiceInvoker registeredServiceInvoker, partyTypeDetails, EntityService entityService, String cacheId, productList, situsState, effectiveDate)
	{
		def licencingMap = [:] as Map
		licencingMap.put("licensingCompensableCode", licensingCompensableCode)
		frameObj.updateDBWithPartyTypeWritingProducerDetails(workFlow, registeredServiceInvoker, licencingMap, partyTypeDetails, productList, situsState, effectiveDate)
		logger.info("**************  updatedLicenseData :: ${licencingMap}")
		entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, cacheId, licencingMap)
		entityService.updateById(GroupSetupConstants.PER_COLLECTION_NAME, cacheId, licencingMap)
	}

	def deleteCacheData(collection,entityService,cacheId){
		MDC.put("DELET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try{
			entityService?.deleteById(collection,cacheId)
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(any){
			logger.error("Error while getting Clients by group set up ID ---> "+any)
			throw new GSSPException("40001")
		}
		MDC.put("DELET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}

	def  updateMongoCache(collectionName,brokerData, existingData, data, entityservice,cacheId,selectProposal) {
		MDC.put(collectionName+"_CREATE_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try {
			if(!existingData) {
				data.putAt("_id", cacheId)
				entityservice.create(collectionName, data)
			}else if(brokerData?.emailId)
			{
				def updateLicencing=checkExistingBroker(existingData,brokerData)
				logger.info("Updated data of Licensing if data present in DB"+updateLicencing)
				entityservice.updateById(collectionName,cacheId, updateLicencing)
			}
			//As Part Of Prod status fix
             if(existingData){
				//def result=checkDataAvailability(collectionName, entityservice, cacheId)
               logger.info("existingData>>>>>>>>>>>>>>>> "+existingData)
				def extension=existingData?.extension
               if(extension){
                extension<< ["actualStatus" : selectProposal?.actualStatus]
				def newExtension =[:] as Map
				newExtension.put("extension", extension)
                logger.info("newExtension>>>>>>>>>>>>>> "+newExtension)
				entityservice.updateById(collectionName,cacheId, newExtension)
               }
			}
		}catch(e){
			logger.error("Error Occured while updateMongoCache---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		MDC.put(collectionName+"_CREATE_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}
	/**
	 * This is method to Update licencing module for broker prepopulation if data is already present in mongo
	 * @param existingData
	 * @param brokerData
	 * @return
	 */
	def checkExistingBroker(existingData,brokerData) {
		def existingLincencingList=existingData?.licensingCompensableCode?.writingProducers
		def writingProducerList=[]
		def licencingMap=[:]
		def writingProducerMap=[:]
		boolean isBroker=false
		def emailIdList=[]
		def emailBroker=brokerData.emailId
		for(def writingProducer : existingLincencingList) {
			emailIdList.add(writingProducer.email)
		}
		if(!(emailIdList.contains(emailBroker))) {
			Date date = new Date()
			long timeMilli = date.getTime()
			existingLincencingList<<["firstName":brokerData.firstName ,"lastName":brokerData.lastName ,"email":brokerData.emailId,
				"roleType" :brokerData.roleType,"licenseNumber" : "","nationalInsuranceProducerRegistry" : "","nationalProducerNumber" : "","principleOfficer" : "",
				"taxId" : "","tpaFeeRemittance" : "","brokerName":"","comissionSplitValue": "","isVerifyStatusCode": "","sponsership": "","compensableCode": [],
				"tpaFeeRemittance": "","writingProducerId": timeMilli]
		}
		writingProducerMap.put("writingProducers", existingLincencingList)
		licencingMap.put("licensingCompensableCode",writingProducerMap)
		licencingMap

	}
	/**
	 *
	 * @param requestBody
	 * @param productList
	 * @param cacheId
	 * @return
	 */
	def createDataforCache(requestBody,brokerData,productList,nonRatedProvisionList,cacheId){

		def jsonEmptyPayloadObj = [:]
		boolean isOffline= false
		JSONParser parser = new JSONParser();
		jsonEmptyPayloadObj = parser.parse(consolidateResponse)
		jsonEmptyPayloadObj.put("_id",cacheId)
		jsonEmptyPayloadObj.put("groupNumber", requestBody['groupNumber'])
		jsonEmptyPayloadObj.put("rfpId", requestBody['rfpId'])
		Map extension =jsonEmptyPayloadObj.getAt("extension")
		extension.put("rfpId", requestBody['rfpId'])
		extension.put("rfpType", requestBody['rfpType']) //rfpType field added for addCoverage amendment changes
		extension.put("groupNumber", requestBody['groupNumber'])
		extension.put("companyName", requestBody['companyName'])
		extension.put("companyID", requestBody['companyID'])
		extension.put("groupSetUpStatus", requestBody['groupSetUpStatus'])
		extension.put("uniqueId", requestBody['uniqueId'])
		extension.put("eligibleLives", requestBody['eligibleLives'])
		extension.put("sicCode", requestBody['sicCode'])
		extension.put("sicDescription", requestBody['sicDescription'])
		extension.put("numberOfLocations", requestBody['numberOfLocations'])
		extension.put("numberOfProducts", requestBody['numberOfProducts'])
		extension.put("accountExecutive", requestBody['accountExecutive'])
		extension.put("actualStatus", requestBody['actualStatus'])
		extension.put("partyTypeDetails", requestBody['partyTypeDetails'])
		extension.put("agentDetails", requestBody['agentDetails'])
		def newElements = requestBody['extention']
		def yearStarted = newElements?.creditInfo?.yearStarted
		extension.put("isUSCitizen", newElements?.isUSCitizen)
		extension.put("entryDate", newElements?.entryDate)
		extension.put("secondarySIC", newElements?.secondarySIC)
		extension.put("createdDate", newElements?.createdDate)
		extension.put("yearStarted", (yearStarted) ? yearStarted.toString():"")
		extension.put("isERDirect", newElements?.isERDirect)
		extension.put("dhmoStates", newElements?.dhmoStates)
		extension.put("rfpSoldDate", newElements?.rfpSoldDate)
		extension.put("isOffline", isOffline)
		extension.put("isOfflineSubmitted", false)
		extension << ['isKickOut':GroupSetupConstants.FALSE]
		jsonEmptyPayloadObj.put("extension", extension)
		Map clientInfo =jsonEmptyPayloadObj.getAt("clientInfo")

		Map basicInfo= clientInfo?.basicInfo
		basicInfo.put("primaryAddress",requestBody['primaryAddress'])
		basicInfo.put("effectiveDate",requestBody['effectiveDate'])
		clientInfo.put("basicInfo", basicInfo)
		jsonEmptyPayloadObj.putAt("clientInfo", clientInfo)
		jsonEmptyPayloadObj.put("extension", extension)
		if(brokerData?.emailId) {
			Map licensing =jsonEmptyPayloadObj.getAt("licensingCompensableCode")
			def mappedData=prepopulateClients(brokerData,licensing)
			logger.info("Updated Licencing module first time---> "+mappedData)
			jsonEmptyPayloadObj.putAt("licensingCompensableCode", mappedData)
		}
		logger.info("Consolidate Payload ---> "+jsonEmptyPayloadObj)
		def groupStructPayload = jsonEmptyPayloadObj.getAt("groupStructure")
		def groupStructData = frameExistingClassList(groupStructPayload,productList,nonRatedProvisionList)
		jsonEmptyPayloadObj.putAt("groupStructure", groupStructData)
		extension.put("products",productList)
		return jsonEmptyPayloadObj
	}

	/**
	 * Getting sold proposals from mongodb.
	 * @param entityService
	 * @param brokerId
	 * @return
	 */
	def checkDataAvailability(collectionName, entityService, String id) {
		MDC.put("GET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def result = null
		try{
			EntityResult entResult = entityService?.get(collectionName, id,[])
			result = entResult.getData()
			logger.info("result: ${result}")
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(any){
			logger.error("Error while getting checkDataAvailability by Id ---> "+any)
			throw new GSSPException("40001")
		}
		MDC.put("GET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		result
	}
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param rfpId
	 * @param proposalId
	 * @return
	 */
	def retreiveDataFromIIB(registeredServiceInvoker, spiPrefix, spiHeadersMap, rfpId, proposalId, String[] profile){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def endpoint = "${spiPrefix}/rfp/${rfpId}/proposal/${proposalId}/productslist"
		MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('Final Request url for Get ProductList  :----->'+serviceUri)
		def response
		def productsList =[] as List
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("productlist.json")?.items
				for(def product: response) {
					productsList.add(product?.item)
				}
			}
			else {
				logger.info("${SERVICE_ID} ----> ${rfpId} ----> ${proposalId} ==== Calling Productslist API ")
				Date start = new Date()
				response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${rfpId} ----> ${proposalId} ==== Productslist API Response time : "+ elapseTime)
				logger.info("IIB Respose with body --> "+response)
				response = response?.getBody()?.items
				for(def product: response) {
					product = product?.item
					def rfpType = product?.rfpType
//					String productName = translateProductCode(product?.productCode)
					translateProvisions(product)
					sortingClassList(product)
					addDependentCoverage(product)
					if(rfpType.equals(GroupSetupConstants.ADD_PRODUCT))
						product.putAt('rfpType', rfpType+"_"+rfpId)
//					product.putAt('productCode', productName)
					productsList.add(product)
				}
				if(productsList.isEmpty())
				{
					logger.error("productsList from SPI ${productsList}")
					throw new GSSPException("400013")
				}
			}
		} catch (e) {
			logger.error("Error retrieving productlist from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		logger.info("IIB Respose with product List ---> "+productsList)
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		productsList
	}

	def sortingClassList(product) {
		List classList = product?.classList
		classList.sort{ a,b ->
			a?.classCoreID <=> b?.classCoreID
		}
		product << ['classList' : classList]
	}

	def translateProvisions(product) {
		def provisions = product?.provisions
		provisions.each{ provision ->
			def provisionName = provision?.provisionName
			def provisionValue = provision?.provisionValue
			switch(provisionName){
				case 'FUNDINGSHARINGAMOUNT' :
					provisionName = 'employerContributionToEmployeePremium'
					break
				case 'EMPLOYEEFUNDPRETAXDOLLAR' :
					provisionName = 'isPreTaxEmployeeContribution'
					if(provisionValue == 'Y')
						provisionValue = 'Yes'
					else if(provisionValue == 'N')
						provisionValue = 'No'
					break
				case 'PORTABILITY' :
					provisionName = 'portability'
					break
				case 'GROSSUP' :
					provisionName = 'grossUp'
					break
				case 'RATEGUARANTEE' :
					provisionName = 'rateGurantee'
					break
				case 'TIERTYPE' :
					provisionName = 'tierType'
					break
				case 'BROKERCOMMISSIONTYPE' :
					provisionName = 'commissionType'
					break
				case 'BROKERCOMMISSIONPCT' :
					provisionName = 'commissionRate'
					break
				case 'COVERAGETYPE' :
					provisionName = 'coverageType'
					break
				case 'SECTION125':
					provisionName = 'section125'
					if(provisionValue == 'Y')
						provisionValue = 'Yes'
					else if(provisionValue == 'N')
						provisionValue = 'No'
					break
				case 'SECTION125PPO':
					provisionName = 'section125ppo'
					if(provisionValue == 'Y')
						provisionValue = 'Yes'
					else if(provisionValue == 'N')
						provisionValue = 'No'
					break
				case 'RENEWALUWLEADDAYS':
					provisionName = 'renewalNotificationPeriod'
					break
				default :
					logger.info "Invalid provision name....${provisionName}"
					break
			}
			provision << ['provisionName':provisionName]
			provision << ['provisionValue':provisionValue]
		}
		product << ['provisions' : provisions]
	}
	
	/**
	 * 
	 * @param product
	 * @return
	 */
	def addDependentCoverage(product)
	{
		def notApplicableProducts=['LGL', 'STD', 'LTD'] as List
		def productCode = product?.productCode
		if(!notApplicableProducts.contains(productCode))
		{
			List provisions = product?.provisions
			product?.classList.each { classObject -> 
				def classID = classObject?.classID
				def dependentCoverage = [:] as Map
				dependentCoverage << ['provisionName':"employerContributionToDependentPremium"]
				dependentCoverage << ['provisionValue':"0"]
				dependentCoverage << ['classID':classID]
				provisions.add(dependentCoverage)
			}
			product << ['provisions' : provisions]
		}
	}
	
	/**
	 *
	 * @param groupStructPayload
	 * @param productList
	 * @param nonRatedProvisionList
	 * @return
	 */
	def frameExistingClassList(groupStructPayload,productList,nonRatedProvisionList){

		def classMap=[:] as Map
		def classDefinition=groupStructPayload.getAt("classDefinition")
		def prodClassMap=[:] as Map
		def rfpTypeCodeMap = [:] as Map
		for(def product: productList){
			for(def cls: product?.classList){
				def products= [] as List
				String classId=cls?.classID
				String classDef=cls?.classDesc
				if(prodClassMap.getAt(classId)!=null){
					products=prodClassMap.getAt(cls?.classID)
				}
				products.add(product?.productCode)
				prodClassMap.putAt(classId, products)
				def rfpTypeCode = product?.rfpType
				rfpTypeCodeMap.putAt(classId, rfpTypeCode)
				classMap.putAt(classId, cls)
			}
		}
		def classList = classMap.keySet()
		logger.info " classList ->"+ classList
		for(def classId: classList){
			def existingClass =[:] as Map
			def product=prodClassMap.getAt(classId)
			def rfpTypeData = rfpTypeCodeMap.getAt(classId)
			def classDescMap = [:] as Map
			def classObj = classMap.get(classId)
			classDescMap.put("jobTitle",  [] as List)
			classDescMap.put("customDescription", "")
			existingClass.put("mode", "update")
			existingClass.put("existingClassId", classObj?.classCoreID)
			existingClass.put("existingClassName", classId)
			existingClass.put("existingClassDescription", classObj?.classDesc)
			existingClass.put("newClassName", "")
			existingClass.put("newClassDescription", "")
			existingClass.put("rfpType", rfpTypeData)
            Map includeExistingProduct=new HashMap()
			includeExistingProduct.put("value", "")
			includeExistingProduct.put("mirrorClassProducts", "")
			includeExistingProduct.put("mirrorClassProductDetails", "")
			includeExistingProduct.put("selectedMirrorClass", "")
			existingClass.put("includeExistingProduct",includeExistingProduct)
			existingClass.put("classDescription", classDescMap)
			existingClass.put("products", product)
			def productDetails =[] as List
			String classDesciption=classObj?.classDesc
			for(def nrp : nonRatedProvisionList){
				HashMap NonRatedProvMap =new HashMap()
				NonRatedProvMap.put("products", [] as List)
				NonRatedProvMap.put("provisionName", nrp.getAt("provisionName"))
				NonRatedProvMap.put("provisionValue", nrp.getAt("provisionValue"))
				NonRatedProvMap.put("grouping", nrp.getAt("grouping"))
				NonRatedProvMap.put("qualifier", nrp.getAt("qualifier"))
				if(classDesciption.contains("Part") && (nrp.getAt("provisionName").equals("fullTimeHours"))) {
					NonRatedProvMap.clear()
				}else if(classDesciption.contains("Full") && (nrp.getAt("provisionName").equals("partTimeHours"))){
					NonRatedProvMap.clear()
				}
				if(!NonRatedProvMap.isEmpty())
					productDetails.add(NonRatedProvMap)
			}
			existingClass.put("productDetails", productDetails)
			addParttimeFullTimeDetails(existingClass, classObj?.classDesc)
			classDefinition.add(existingClass)
		}
		groupStructPayload.putAt("classDefinition", classDefinition)
		return groupStructPayload
	}
	/**
	 * This is method to Update licencing module for broker prepopulation if data is not present in mongo
	 * @param brokerData
	 * @param licensing
	 * @return
	 */
	def prepopulateClients(brokerData,licensing){
		Date date = new Date()
		long timeMilli = date.getTime()
		licensing.writingProducers << ["firstName":brokerData.firstName ,"lastName":brokerData.lastName ,"email":brokerData.emailId,
			"roleType" :brokerData.roleType,"licenseNumber" : "","nationalInsuranceProducerRegistry" : "","nationalProducerNumber" : "","principleOfficer" : "",
			"taxId" : "","tpaFeeRemittance" : "","brokerName":"","comissionSplitValue": "","isVerifyStatusCode": "","sponsership": "","compensableCode": [],
			"tpaFeeRemittance": "","writingProducerId": timeMilli]
		licensing
	}

	/**
	 * 
	 * @param existingClass
	 * @param classDesc
	 * @return
	 */
	def addParttimeFullTimeDetails(existingClass, classDesc)
	{
		if(classDesc && (classDesc == "All Active Part Time Employees" || classDesc == "All Active Part-Time Employees" || classDesc =="All active Part time Employees"))
		{
			existingClass.put("workItem", "parttime")
			existingClass.put("orgin", "RFP")
		}else{
			existingClass.put("workItem", "fulltime")
			existingClass.put("orgin", "")
		}
	}
	//This is GroupSetup consolidate response structure with empty values
	String consolidateResponse = '''{
	"rfpId":"",
	"groupNumber":"",
	"licensingCompensableCode": {
		"writingProducers": []
	},
	"clientInfo": {
		"basicInfo": {
			"isLegalGroupNameDifferent": "",
			"legalGroupName": "",
			"doingBusinessAs": "",
			"customerOrganizationType": "",
			"federalTaxId": "",
			"effectiveDate": "",
			"primaryAddress": {
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": "",
				"situsState": ""
			},
			"isSeparateCorrespondenceAddress": "",
			"correspondenceAddress": {
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": ""
			}
		},
		"contributions": {
			"products": [

			]
		},
		"erisa": {
      		"policyCoveredUnderSection125":  [
        		{
          			"productCode": "",
          			"provisionName": "section125",
          			"provisionValue": ""
       			}
      		],
      		"isERISALanguageIncludedInCertificate": "",
      		"planYearEnds": "",
      		"fiscalYearMonth": "",
      		"effectiveDate": "",
      		"calculatedYear": "",
			"isRASpresent":""
    }
  },
	"riskAssessment": {
		"pregnantEmployeeDetails": {
			"questions": [{
					"question": "isPregnantEmployee",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "1",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "numberOfPregnantEmployee",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "2",
					"listOfAllowedResponses": [
						"any"
					]
				}
			]
		},
		"healthRisk": {
			"questions": [{
					"question": "anySignificantHealthRisks",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "3",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "cardioVascularDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "4",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "strokeOrCirculatoryDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "5",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "cancerOrTumor",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "6",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "leukemiaOrBloodDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "7",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "CopdOrLungDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "8",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "stomachOrLiverDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "9",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "neurologicalDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "10",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "chronicfatigueSyndrome",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "11",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "multiplesclerosis",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "12",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "mentalDisorder",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "13",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "AIDSOrHIV",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "14",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "other",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "15",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "otherText",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "16",
					"listOfAllowedResponses": [
						"any"
					]
				}
			]
		},
		"disabledEmployees": {
			"questions": [{
					"question": "anyDisabledEmployee",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "17",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				},
				{
					"question": "noOfDisabledEmployee",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "18",
					"listOfAllowedResponses": [
						"any"
					]
				},
				{
					"question": "haveWaiverOfPremium",
					"answer": "",
					"isMandatory": "",
					"sequenceNo": "19",
					"listOfAllowedResponses": [
						"Yes",
						"No"
					]
				}
			],
			"disableEmployeeDetails": []
		},
		"riskAssesmentAcknowledgement": []
	},
	"comissionAcknowledgement": [{
		"experienceNumber": "",
		"effectiveDate": "",
		"situsState": "",
		"comissionRate": [],
		"brokerDetails": {
			"brokerId": "",
			"brokerCode": "",
			"brokerEmailId": ""
		},
		"payeeDetails": {
			"payeName": "",
			"payeeBrokerCode": "",
			"payeeAddress": {
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": ""
			}
		},
		"eConsent": {},
		"eSign": {},
		"isCommissionScaleConsent": {}
	}],
	"renewalNotificationPeriod": [],
	"groupStructure": {
		"newClassAdded": "",
		"classDefinition": [],
		"locations": [{
			"isPrimaryAddress": "",
            "isPrimaryAddressUpdated": "",
			"isTaxIdSameAsPrimary": "",
			"locationName": "",
			"taxId": "",
			"addressLine1": "",
			"addressLine2": "",
			"city": "",
			"state": "",
			"zipCode": "",
			"situsState": "",
			"activeParticipants": "",
			"numberOfParticipants": ""
		}],
		"billing": {
			"thirdPartyInvolved": "",
			"billingAddress": [{
				"isPrimaryAddress": "",
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": ""
			}]
		},
		"departments": [],
		"contact": [{
			"firstName": "",
			"lastName": "",
			"email": "",
			"workPhone": "",
			"cellPhone": "",
			"fax": "",
			"requireOnlineAccess": "",
			"isExecutiveSameForAllRole": "",
			"roleTypes": [{
				"roleType": "",
				"roleUniqueId": "",
				"roleValue": ""
			}]
		}],
		"buildCaseStructure": [{
			"location": {
				"isPrimaryAddress": "",
            	"isPrimaryAddressUpdated": "",
				"isTaxIdSameAsPrimary": "",
				"locationName": "",
				"taxId": "",
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": "",
				"situsState": "",
				"activeParticipants": "",
				"numberOfParticipants": ""
			},
			"entityAddress": {
				"isPrimaryAddress": "",
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": ""
			},
			"departments": [{
				"departmentDescription": "",
				"departmentCode": ""
			}],
			"contacts": [{
				"firstName": "",
				"lastName": "",
				"email": "",
				"workPhone": "",
				"cellPhone": "",
				"fax": "",
				"requireOnlineAccess": "",
				"isExecutiveSameForAllRole": "",
				"roleTypes": [{
					"roleUniqueId": "",
					"roleValue": "",
					"roleType": ""
				}]
			}]
		}],
		"subGroup": [{
			"subGroupNumber": "",
			"buildCaseStructure": {
				"location": {
					"isPrimaryAddress": "",
            		"isPrimaryAddressUpdated": "",
					"isTaxIdSameAsPrimary": "",
					"locationName": "",
					"taxId": "",
					"addressLine1": "",
					"addressLine2": "",
					"city": "",
					"state": "",
					"zipCode": "",
					"situsState": "",
					"activeParticipants": "",
					"numberOfParticipants": ""
				},
				"entityAddress": {
					"isPrimaryAddress": "",
					"addressLine1": "",
					"addressLine2": "",
					"city": "",
					"state": "",
					"zipCode": ""
				},
				"departments": [{
					"departmentDescription": "",
					"departmentCode": ""
				}],
				"contacts": [{
					"firstName": "",
					"lastName": "",
					"email": "",
					"workPhone": "",
					"cellPhone": "",
					"fax": "",
					"requireOnlineAccess": "",
					"isExecutiveSameForAllRole": "",
					"roleTypes": [{
						"roleUniqueId": "",
						"roleValue": "",
						"roleType": ""
					}]
				}]
			},
			"assignClassLocation": [{
				"classId": ""
			}]
		}],
		"productList": [{
			"productName": ""
		}]
	},
	"billing": {
    "deliveryMethod": "",
    "isStatementOnMail":"",
    "billType": "",
    "premiumPayment": "",
    "premiumAmount": "",
    "paymentMode": "",
    "paymentMethod": "",
    "acknowledgementIsChecked": "",
    "type": "",
    "purpose": "",
    "name": "",
    "acceptedBy": "",
    "date": "",
    "time": "",
    "state": "",
    "relation": "",
    "globalVersion": "",
    "isConsented": "",
    "isConsentWaivedOff": "",
    "wetInkIndicator": "",
    "city": "",
    "country": "",
    "stateVersion": "",
    "transactionReference": ""
  },
	"authorization": {
		"grossSubmit": {
			"grammLeachBilley": {
				"isChecked": "",
				"type": "1",
				"purpose": "1003",
				"name": "",
				"acceptedBy": "",
				"date": "",
				"time": "",
				"state": "",
				"relation": "",
				"globalVersion": "",
				"isConsented": "",
				"isConsentWaivedOff": "",
				"wetInkIndicator": "",
				"city": "",
				"country": "",
				"stateVersion": "",
				"transactionReference": ""
			},
			"ICNotice": {
				"ICNoticeChecked": "",
				"isChecked": "",
				"type": "1",
				"purpose": "1004",
				"name": "",
				"acceptedBy": "",
				"date": "",
				"time": "",
				"state": "",
				"relation": "",
				"globalVersion": "",
				"isConsented": "",
				"isConsentWaivedOff": "",
				"wetInkIndicator": "",
				"city": "",
				"country": "",
				"stateVersion": "",
				"transactionReference": ""
			},
			"grossUpLetter": {
				"LTDorSTD": "",
				"isChecked": "",
				"date": "",
				"time": "",
				"type": "1",
				"purpose": "1005",
				"name": "",
				"acceptedBy": "",
				"state": "",
				"relation": "",
				"globalVersion": "",
				"isConsented": "",
				"isConsentWaivedOff": "",
				"wetInkIndicator": "",
				"city": "",
				"country": "",
				"stateVersion": "",
				"transactionReference": ""
			},
			"onlineAccess": {
				"agentHaveAccess": "",
				"brokerHaveAccess": "",
				"tpaHaveAccess": "",
				"documentDeliveryPreference": "",
				"emailId": "",
				"address": {
					"addressLine1": "",
					"addressLine2": "",
					"city": "",
					"state": "",
					"zip": ""
				}
			}
		},
		"HIPAA": {
			"hipaaQuestions": [
               {
               		"question": "PHIAnsweredRfpType",
					"answer": ""
               },
               {
					"question": "PHIInformationAccess",
					"answer": ""
				},
				{
					"question": "claimAccess",
					"answer": "wantClaimAccessAsSampleHipaa/wantClaimAccessAsOwn"
				},
				{
					"question": "includePrivacyOfficer",
					"answer": ""
				},
				{
					"question": "includePrivacyOfficer",
					"answer": ""
				},
				{
					"question": "includeParticipantsRights",
					"answer": ""
				},
				{
					"question": "includePrivicyIssues",
					"answer": ""
				}
			],
			"authorizationRequest": {
				"employeeTitle": [{
					"title": "",
					"otherIdentifier": "",
					"date": ""
				}],
				"fileDetails": [{
					"documentId": "",
					"content": "This is a document",
					"format": "BASE64",
					"fileName": "abc",
					"fileExtention": "jpg"
				}],
				"eConsent": {
					"isChecked": "",
					"type": "1",
					"purpose": "1006",
					"name": "",
					"acceptedBy": "",
					"date": "",
					"time": "",
					"state": "",
					"relation": "",
					"globalVersion": "",
					"isConsented": "",
					"isConsentWaivedOff": "",
					"wetInkIndicator": "",
					"city": "",
					"country": "",
					"stateVersion": "",
					"transactionReference": ""
				},
				"eSign": {
					"isChecked": "",
					"type": "2",
					"purpose": "2004",
					"name": "",
					"acceptedBy": "",
					"date": "",
					"time": "",
					"state": "",
					"relation": "",
					"globalVersion": "",
					"isConsented": "",
					"isConsentWaivedOff": "",
					"wetInkIndicator": "",
					"city": "",
					"country": "",
					"stateVersion": "",
					"transactionReference": ""
				}
			}
		},
		"disabilityTaxation": {
			"issueDisability": [{
				"productCode": "",
				"provisionName": "",
				"provisionValue": "",
				"grouping": "",
				"qualifier": ""
				}
			],
			"payrollVendor": "",
			"termConditionChecked": "",
			"type": "1",
			"purpose": "1007",
			"name": "",
			"acceptedBy": "",
			"date": "",
			"time": "",
			"state": "",
			"relation": "",
			"globalVersion": "",
			"isConsented": "",
			"isConsentWaivedOff": "",
			"wetInkIndicator": "",
			"city": "",
			"country": "",
			"stateVersion": "",
			"transactionReference": ""
		},
		"portabilityTrust": {
			"isChecked": "",
			"type": "1",
			"purpose": "1008",
			"name": "",
			"acceptedBy": "",
			"date": "",
			"time": "",
			"state": "",
			"relation": "",
			"globalVersion": "",
			"isConsented": "",
			"isConsentWaivedOff": "",
			"wetInkIndicator": "",
			"city": "",
			"country": "",
			"stateVersion": "",
			"transactionReference": ""
		},
		"noClaims": {
			"claimsIncurred": "",
			"termConditionChecked": "",
			"claimInformation": [],
			"type": "1",
			"purpose": "1009",
			"name": "",
			"acceptedBy": "",
			"date": "",
			"time": "",
			"state": "",
			"relation": "",
			"globalVersion": "",
			"isConsented": "",
			"isConsentWaivedOff": "",
			"wetInkIndicator": "",
			"city": "",
			"country": "",
			"stateVersion": "",
			"transactionReference": ""
		}
	},
	"masterApp": {
		"item": {
			"groupNumber": "",
			"documentId": "",
			"eligibleLives": "",
			"OrganizationLegalName": "",
			"federalTaxId": "",
			"contact": [{
				"firstName": "",
				"lastName": ""
			}],
			"addressGlobal": {
				"addressLine1": "",
				"addressLine2": "",
				"city": "",
				"state": "",
				"zipCode": "",
				"policySitus": ""
			},
			"effectiveDateGlobal": "",
			"forms": [{
				"formName": "",
				"employeesMembersCoverage": [{
					"nameCode": ""
				}],
				"dependentMembersCoverage": [{
					"nameCode": ""
				}],
				"stateFooter": "",
				"fraudWarning": "",
				"partTimeEmployeesMembersCoverage": [{
					"nameCode": ""
				}],
				"partTimeDependentMembersCoverage": [{
					"nameCode": ""
				}]
			}],
			"monthlyGLOBAL": "",
			"quarterlyGLOBAL": "",
			"annuallyGLOBAL": "",
			"otherGLOBAL": "",
			"otherDetailsGLOBAL": "",
			"advancedPaymentGLOBAL": "",
			"city": "",
			"state": "",
			"date": "",
			"time": "",
			"nameOfApplicant": "",
			"titleOfAuthorizedSignature": "",
			"accountExecutive": {
				"firstName": "",
				"lastName": "",
				"printTypeCode": "",
				"licenseNumber": ""

			},
			"initialApplication": "",
			"rateGurantee": "",
			"customerType": "",
			"multiSiteLocationCovered": [{
				"locationName": ""
			}],
			"hospitalIndemnityGroupComprehensiveYes": "",
			"hospitalIndemnityGroupComprehensiveNo": "",
			"hospitalIndemnitySupplementGroupComprehensiveYes": "",
			"hospitalIndemnitySupplementGroupComprehensiveNo": "",
			"accidentGroupComprehensiveYes": "",
			"accidentGroupComprehensivNo": "",
			"accidentSupplementGroupComprehensiveYes": "",
			"accidentSupplementGroupComprehensivNo": ""
		},
		"links": [{
			"link": ""
		}],
		"metadata": {
			"count": 0,
			"limit": 0,
			"offset": 0
		}
	},
	"masterAppSignature": {
		"type": "2",
		"purpose": "2005",
		"name": "",
		"acceptedBy": "",
		"date": "",
		"time": "",
		"state": "",
		"relation": "",
		"globalVersion": "",
		"isConsented": "",
		"isConsentWaivedOff": "",
		"wetInkIndicator": "",
		"city": "",
		"country": "",
		"stateVersion": "",
		"transactionReference": ""
	},
	"extension": {
		"rfpId": "",
		"groupNumber": "",
		"companyName": "",
		"companyID": "",
		"groupSetUpStatus": "",
		"actualStatus": "",
		"module": "",
		"requote": "",
		"isOffline": "",
		"uniqueId": "",
		"eligibleLives": "",
		"sicCode": "",
		"sicDescription": "",
		"numberOfLocations": "",
		"numberOfProducts": "",
		"accountExecutive": {
			"firstName": "",
			"lastName": ""
		},
		"products": [

		],
		"isUSCitizen": true,
        "entryDate": "",
        "secondarySIC": "",
        "createdDate": "",
        "yearStarted": "",
        "isERDirect": true,
        "dhmoStates": ""
	}
}'''

	def updatePermanentDB(EntityService entityService,tempData,cacheId) {

		try 
		{
			if(tempData)
				entityService.updateById(GroupSetupConstants.PER_COLLECTION_NAME,cacheId,tempData)
			//EntityResult entResult = entityService?.get(GroupSetupConstants.PER_COLLECTION_NAME,, cacheId,[])
			//result = entResult.getData()
			//logger.info("Update Permanant Data after kickout ---> "+result)
		}
		catch(e) {
			logger.error("Error Occured while updateMongoCache---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		// result
	}


}

