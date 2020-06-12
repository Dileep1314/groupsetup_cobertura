package groovy.US


import org.slf4j.MDC;
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.http.HttpEntity
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Map
import java.time.temporal.ChronoField
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

import groovy.json.JsonOutput
import groovy.time.TimeCategory
import groovy.time.TimeDuration
/**
 * 
 * This class is used for storing/updating user input data to mongo db
 *
 */
class SaveGroupSetupDraftDetails implements Task{Logger logger = LoggerFactory.getLogger(SaveGroupSetupDraftDetails)
	GetGroupSetupData getGSData = new GetGroupSetupData()
	GroupSetupUtil utilObject = new GroupSetupUtil()
	def static final SERVICE_ID = "SGSDD006"
	
	@Override
	Object execute(WorkflowDomain workFlow) {
		Date start = new Date()
		def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def spiPrefix= workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def profile = workFlow.applicationContext.environment.activeProfiles
		def groupSetUpId = requestPathParamsMap[GroupSetupConstants.GROUPSETUP_ID]
		logger.info("${SERVICE_ID} ----> ${groupSetUpId} ==== SaveGroupSetupDraftDetails Start ")
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupSetUpId.split('_')[0])
		logger.info("SaveGroupSetupDraftDetails : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("SaveGroupSetupDraftDetails : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		def tenantId= requestPathParamsMap[GroupSetupConstants.TENANT_ID]
		def requestBody = workFlow.getRequestBody()
		logger.info("SAVE DRAFT DATA GROUP DATA LOCALLY ${requestBody}")
		def deletedbrokerageCode = requestBody.get(GroupSetupConstants.DELETE_BKC_CODE)
		requestBody.remove(GroupSetupConstants.DELETE_BKC_CODE)
		logger.info("${SERVICE_ID} ----> ${groupSetUpId} ==== SaveGroupSetupDraftDetails Request body : ${requestBody} ")
		def result = saveGroupSetUpData(workFlow, requestBody, entityService, groupSetUpId,gsspRepository, tenantId, registeredServiceInvoker, getGSData, profile, spiHeadersMap, spiPrefix, deletedbrokerageCode)
		workFlow.addResponseBody(new EntityResult([(GroupSetupConstants.STATUS) : result],true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		Date stop = new Date()
		TimeDuration elapseTime = TimeCategory.minus(stop, start)
		logger.info("${SERVICE_ID} ----> ${groupSetUpId} ==== SaveGroupSetupDraftDetails end. elapseTime : "+ elapseTime)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	/**
	 * This is used for save/update GroupSetup data
	 * @param requestBody
	 * @param entityService
	 * @param groupSetUpId
	 * @param moduleName
	 * @return
	 */
	private saveGroupSetUpData(workFlow, Map requestBody,entityService, groupSetUpId,gsspRepository,tenantId, registeredServiceInvoker, getGSData, String[] profile, spiHeadersMap, spiPrefix, deletedbrokerageCode)
	{
		def result
		String action = requestBody.get(GroupSetupConstants.ACTION)
		if(action)
		{
			switch(action){
				case GroupSetupConstants.SOLD_CASE_STATUS_UPDATE :
					result = updateSoldProposalStatus requestBody, entityService, groupSetUpId
					break
				case GroupSetupConstants.OFFLINE_SUBMIT :
					result = updateOffLineSubmitStatus entityService, groupSetUpId
					break
				default:
					logger.info "Invalid action....${action}"
					break
			}
		}else{
			deleteGATPABrokerageInCore(registeredServiceInvoker, spiHeadersMap, spiPrefix, tenantId, profile, deletedbrokerageCode, groupSetUpId)
			requestBody = updatingRequestBody(entityService, groupSetUpId, requestBody)
			result = saveAsDraft(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,requestBody,gsspRepository,groupSetUpId,tenantId)
			saveAsDraft(GroupSetupConstants.PER_COLLECTION_NAME,requestBody, gsspRepository,groupSetUpId,tenantId)
			if(requestBody.get("billing")){ //groupsetup get call on billing to apply Duplicate Contacts fix
				new SubmitGroupSetupDetails().updateLocationAndContactDetails(workFlow, registeredServiceInvoker, entityService, gsspRepository, groupSetUpId, spiPrefix,  profile, tenantId)
			}else if (requestBody.get("licensingCompensableCode")){
				def maintenenceServiceVip = workFlow.getEnvPropertyFromContext(GroupSetupConstants.MAINTENANCEVIP)
				updateAgentInfoToCore(requestBody, groupSetUpId, registeredServiceInvoker, tenantId, profile, gsspRepository)
				updateUserMaintenanceId(requestBody, groupSetUpId, entityService, registeredServiceInvoker, tenantId, profile, gsspRepository, maintenenceServiceVip)
			}
		}
		result
	}
	
	//Duplicate Contacts fix - Replacing db subgroup data while updating
	private updatingRequestBody(entityService, groupSetUpId, Map requestBody) {
		if(requestBody.get("groupStructure"))
		{
			logger.info(" **** Overriding subgroup data with mongodb data")
			def mongoExistingCacheData = getGroupSetupData(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId)
			def dbSubGroup = mongoExistingCacheData?.groupStructure?.subGroup
			logger.info(" **** dbSubGroup ::: ${dbSubGroup}")
			def reqGroupStructure = requestBody?.groupStructure
			logger.info(" **** Before updating subgroup data reqGroupStructure ::: ${reqGroupStructure}")
			reqGroupStructure.putAt("subGroup", dbSubGroup)
			logger.info(" **** After updated subgroup data reqGroupStructure ::: ${reqGroupStructure}")
			requestBody.putAt("groupStructure", reqGroupStructure)
			logger.info(" **** Final requestBody After updated subgroup data  ::: ${requestBody}")
		}else if(requestBody.get("comissionAcknowledgement")) {
				def mongoExistingCacheData = getGroupSetupData(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId)
				def tempExtension = mongoExistingCacheData?.extension
				 tempExtension<<["isCommissionDone":true]
				 requestBody<<["extension":tempExtension]
			}
		requestBody
	}
	
	def deleteGATPABrokerageInCore(registeredServiceInvoker, spiHeadersMap, spiPrefix, tenantId, profile, deletedbrokerageCode, groupSetUpId){
		try{
			logger.info("Brokerage Code to deleting in core  : ${deletedbrokerageCode}")
			if(deletedbrokerageCode){
				def deleteAgentReqPayload = prepareDeleteAgentReqPayload(deletedbrokerageCode, groupSetUpId)
				def response = deleteAgentInfo(registeredServiceInvoker, tenantId, spiPrefix, spiHeadersMap, deleteAgentReqPayload, profile, groupSetUpId)
				if(response && response != "true")
					throw new GSSPException("400013")
			}
		}catch(any){
			logger.error("Error while deleting GA/TPA details in core :  ${any}")
			throw new GSSPException("400013")
		}
	}
	
	private updateAgentInfoToCore(Map requestBody, groupSetUpId, registeredServiceInvoker, tenantId, String[] profile, gsspRepository) {
		try{
			def licensingCompensableCode = requestBody.get("licensingCompensableCode")
			def writingProducers = licensingCompensableCode?.writingProducers
			logger.info("Before updating isCoreUpdated field in writingproducers list : ${writingProducers}")
			def updateAgentInfoReqPayload = prepareAgentInfoReqPayload(writingProducers, groupSetUpId)
			def response = deleteUpdateAgentInfo(registeredServiceInvoker, tenantId, updateAgentInfoReqPayload, profile, groupSetUpId)
			if(response && response == "true")
			{
				updateWritingProducers(writingProducers)
				logger.info("After updating isCoreUpdated field in writingproducers list : ${writingProducers}")
				licensingCompensableCode << ['writingProducers' : writingProducers]
				requestBody.put("licensingCompensableCode", licensingCompensableCode)
				saveAsDraft(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,requestBody,gsspRepository,groupSetUpId,tenantId)
				saveAsDraft(GroupSetupConstants.PER_COLLECTION_NAME,requestBody, gsspRepository,groupSetUpId,tenantId)
			}
		}catch(any){
			logger.error("Error while updating GA/TPA details in core  ${any}")
		}
	}
	
	def updateUserMaintenanceId(requestBody, groupSetUpId, entityService, registeredServiceInvoker, tenantId, profile, gsspRepository, maintenenceServiceVip)
	{
		try{
			def mongoExistingCacheData = getGroupSetupData(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId)
			def licensingCompensableCode = requestBody.get("licensingCompensableCode")
			def writingProducers = licensingCompensableCode?.writingProducers
			def umIdWPList = [] as List
			for(def wp : writingProducers)
			{
				if(GroupSetupConstants.ROLE_EMPLOYER.equalsIgnoreCase(wp?.roleType) && !(GroupSetupConstants.TRUE.equalsIgnoreCase(wp?.isRegistered)) && !(wp?.umId)){
					def umId = callMaintenance(mongoExistingCacheData, tenantId, registeredServiceInvoker, maintenenceServiceVip, wp, profile)
					if(umId)
						wp.putAt("umId", umId)
				}
			}
			logger.info("updated employer User Maintenance id in writing producers :  ${requestBody}")
			saveAsDraft(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, requestBody, gsspRepository, groupSetUpId, tenantId)
		}catch(any){
			logger.error("Error while updating employer User Maintenance id in writing producers :  ${any}")
		}
	}

	def updateWritingProducers(writingProducers) {
		writingProducers.each { writingProducer ->
			def roleType = writingProducer?.roleType
			def isCoreUpdated = writingProducer?.isCoreUpdated
			if(writingProducer?.isVerifyStatusCode == "Active" && (roleType == "General Agent" || roleType == "Third Party Administrator") && isCoreUpdated == "false")
				writingProducer << ['isCoreUpdated': GroupSetupConstants.TRUE]
		}
	}

	def updateLocationdetails (registeredServiceInvoker, getGSData, String[] profile, spiHeadersMap, entityService, groupSetUpId, spiPrefix, gsspRepository)
	{
		try{
			def groupNumber = groupSetUpId.split('_')[0]
			def gropSetUpData = getGSData.getConsolidateData(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, profile)
			logger.info("GropSetUpData From IIB....${gropSetUpData}")
			//def gropSetUpData= utilObject.getTestData("getSubGroup.json")
			if(gropSetUpData)
			{
				def subGroup=gropSetUpData?.groupStructure?.subGroup
				def subGroupList=[]
				def newBuildcasestructure=[]
				subGroup.each{ output->
					def subGroupMap=[:]
					subGroupMap<<["subGroupNumber":output?.subGroupNumber]
					subGroupMap<<["buildCaseStructure":output?.buildCaseStructure]
					subGroupMap<<["assignClassLocation":output?.assignClassLocation]
					subGroupList.add(subGroupMap)
					newBuildcasestructure.add(output?.buildCaseStructure)

				}
				logger.info("subGroupList From IIB....${subGroupList}")
				def updatedGroustructure=[:]
				def mongoExistingCacheData = getById GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId
				def groupStructureData = mongoExistingCacheData?.groupStructure
				def locations = mongoExistingCacheData?.groupStructure?.locations
				def newLocationName=updateLocationName(locations, subGroupList)
				//def buildCaseStructures = mongoExistingCacheData?.groupStructure?.buildCaseStructure
				updateBuildCaseStructure(newLocationName,newBuildcasestructure)
				//updateBuildCaseStructure(newLocationName,buildCaseStructures)
				def newContacts = mongoExistingCacheData?.groupStructure?.contact
				groupStructureData << ["subGroup" :  subGroupList]
				groupStructureData << ["locations" :  locations]
				groupStructureData << ["buildCaseStructure" :  newBuildcasestructure]
				updatedGroustructure << ["groupStructure":groupStructureData]
				logger.info("UpdatedGroustructure data..... ${updatedGroustructure}")
				logger.info("Updated locations..... ${locations}")
				saveAsDraft(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, updatedGroustructure, gsspRepository, groupSetUpId, 'US')
			}
		}catch(any){
			logger.error("Exception Occured while updating location details --->${any.getMessage()}")
			// throw new GSSPException("40001")
		}
	}
	def updateBuildCaseStructure(newLocationName,buildCaseStructures) {
		buildCaseStructures.each { buildcaseStructure ->
			def isPrimaryAddress=buildcaseStructure?.location?.isPrimaryAddress
			if(isPrimaryAddress.equalsIgnoreCase("Yes") || isPrimaryAddress=="Yes") {
				buildcaseStructure.location.putAt("locationName",newLocationName)
			}
		}

	}
	/**
	 * 
	 * @param locations
	 * @param subGroupList
	 * @return
	 */
	def updateLocationName(locations, subGroupList)
	{
		def newLocationName
		subGroupList.each{ subGroup ->
			def locationName = subGroup?.buildCaseStructure?.location?.locationName
			def isPrimaryAddress = subGroup?.buildCaseStructure?.location?.isPrimaryAddress
			if(isPrimaryAddress.equalsIgnoreCase("Yes") || isPrimaryAddress=="Yes") {
				locations.eachWithIndex { location,index ->
					if(index==0) {
						location << ["locationName" : locationName]
						newLocationName=locationName
					}
				}

			}
		}
		newLocationName
	}

	/**
	 * Method is using for insert data into mongo collection
	 * @param collectionName
	 * @param data
	 * @param entityService
	 * @param groupSetUpId
	 * @return
	 */
	def  saveAsDraft(collectionName, data, gsspRepository, groupSetUpId,tenantId) {
		MDC.put(collectionName+"_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try{
			def modifiedData= modifyData(data)
			data << ['_id':groupSetUpId]
			def payloads=[] as List
			payloads.add(data)
			gsspRepository.upsert(tenantId,collectionName,payloads )
			logger.info("RECORD UPDATED SUCESSFULLY IN LOCAL DB WITH DRAFT DATA "+payloads)
		}catch(any){
			logger.error("Error Occured in saveAsDraft--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		MDC.put(collectionName+"_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		GroupSetupConstants.OK
	}

	/**
	 * This method is used only for if any specific condition will come before saving.
	 * 
	 * @param data
	 * @return
	 */

	def modifyData(data){
		def clientInfo=data['clientInfo']
		if(clientInfo){
			def erisa=clientInfo['erisa']
			if(erisa){
				def section125Provision=clientInfo['erisa'].policyCoveredUnderSection125
				def effectiveDate=clientInfo['basicInfo'].effectiveDate
				def planYearEnds=clientInfo['erisa'].planYearEnds
				def fiscalYearMonth=clientInfo['erisa'].fiscalYearMonth
				def calcuatedYear=calculatePlanYear planYearEnds,effectiveDate,fiscalYearMonth
				def updatedProvisionValue=updateErisaProvisionValue section125Provision
				clientInfo['erisa'].policyCoveredUnderSection125=updatedProvisionValue
				clientInfo['erisa'].calculatedYear=calcuatedYear
				data['clientInfo'].erisa=clientInfo['erisa']
			}
		}
		data
	}

	def updateErisaProvisionValue(section125Provision){
		def updatedProvision=[] as List
		for(def provision: section125Provision){
			if("Yes".equalsIgnoreCase(provision?.provisionValue)){
				provision?.provisionValue="Y"
			}else if("No".equalsIgnoreCase(provision?.provisionValue)){
				provision?.provisionValue="N"
			}
			updatedProvision.add(provision)
		}

		updatedProvision
	}
	/**
	 *
	 * @param yearName
	 * @param groupDate
	 * @param monthName
	 * @return
	 */

	static def calculatePlanYear(def yearName,def groupDate,def monthName){
		def planYearEnds
		def MM
		def DD
		if(yearName.equals("Calendar Year")){
			planYearEnds="12/31/9999";
		}else
		if(yearName.equals("Fiscal Year") && monthName!=null &&
		monthName!=''){
			MM=getPreviousMonth(monthName)
			DD=getMonthLastDay(9999,MM)
			planYearEnds=MM+"/"+DD+"/9999"
		}else
		if(yearName.equals("Policy Year") && groupDate!=null && groupDate!=''){
			def effectiveMonth=groupDate.split("/").getAt(0);//expecting effective date in MM/DD/YYYY format
			MM=Integer.parseInt(effectiveMonth)-1;
			if(MM==0)MM=12;
			DD=getMonthLastDay(9999,MM)
			planYearEnds=MM+"/"+DD+"/9999"
		}
	}


	/**
	 *
	 * @param monthName
	 * @return
	 */
	static def getPreviousMonth(def monthName){
		DateTimeFormatter parser = DateTimeFormatter.ofPattern("MMM")
				.withLocale(Locale.ENGLISH);
		TemporalAccessor accessor = parser.parse(monthName);
		int month=accessor.get(ChronoField.MONTH_OF_YEAR);
		if(month==0){
			month=month+12;
		}else if(month==1){
			month=month+11;
		}else{
			month=month-1;
		}
		return month;
	}

	/**
	 *
	 * @param year
	 * @param month
	 * @return
	 */
	static def getMonthLastDay(int year, int month){
		GregorianCalendar calendar = new GregorianCalendar();
		int monthInt = month - 1;
		calendar.set(year, monthInt, 1);
		int dayInt = calendar.getActualMaximum(GregorianCalendar.DAY_OF_MONTH);
		return Integer.toString(dayInt);
	}


	/**
	 * This method for updating sold proposal status after Begin GroupSetup.
	 * @param collectionName
	 * @param data
	 * @param entityService
	 * @return
	 */
	def updateSoldProposalStatus(data, entityService, groupSetUpId)
	{
		def brokerId = data[GroupSetupConstants.BROKER_ID]
		updateStatusInGSSoldProposalClients(groupSetUpId, entityService, brokerId)
		updateStatusInGroupSetupData(entityService, groupSetUpId)
		GroupSetupConstants.OK
	}

	/**
	 * This method update groupSetUpStatus in GSSoldProposalClients collection.
	 * @param groupSetUpId
	 * @param entityService
	 * @param brokerId
	 * @return
	 */
	private updateStatusInGSSoldProposalClients(groupSetUpId, entityService, brokerId)
	{
		def keys = groupSetUpId.split(GroupSetupConstants.UNDERSCORE)
		def soldCasesResponse = getById GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, entityService, brokerId
		List proposalList = soldCasesResponse?.proposals
		def proposal = proposalList.find{it -> if(it.groupNumber.contentEquals(keys[0]) &&
			it.rfpId.contentEquals(keys[1]) && it.uniqueId.contentEquals(keys[2]))
				it.putAt(GroupSetupConstants.GROUPSETUP_STATUS, GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS)
		}
		soldCasesResponse << [(GroupSetupConstants.PROPOSALS):proposalList]
		entityService.updateById(GroupSetupConstants.COLLECTION_GS_SOLD_PROPOSAL_CLIENTS, brokerId, soldCasesResponse)
	}

	/**
	 * This method update groupSetUpStatus in GroupSetupData collection.
	 * @param entityService
	 * @param groupSetUpId
	 * @return
	 */
	private updateStatusInGroupSetupData(entityService, groupSetUpId)
	{
		def groupDataRes = getById GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId
		def extension = groupDataRes?.extension
		extension << [(GroupSetupConstants.GROUPSETUP_STATUS):GroupSetupConstants.STATUS_APPLICATION_IN_PROGRESS]
		groupDataRes << ['extension':extension]
		entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetUpId, groupDataRes)
	}

	/**
	 * This method update Offline Submit status in GroupSetupData collection.
	 * @param entityService
	 * @param groupSetUpId
	 * @return
	 */
	private updateOffLineSubmitStatus(entityService, groupSetUpId)
	{
		def groupDataRes = getById GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, entityService, groupSetUpId
		def extension = groupDataRes?.extension
		extension << ['isOfflineSubmitted':true]
		groupDataRes << ['extension':extension]
		entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, groupSetUpId, groupDataRes)
		GroupSetupConstants.OK
	}

	/**
	 * Get data by collection id
	 * @param collectionName
	 * @param entityService
	 * @param groupId
	 * @return
	 */
	def  getById(collectionName, entityService, Id) {
		EntityResult entResult
		try{
			logger.info("SaveGroupSetupDraftDetails :: Get Record By ID :: ${Id}")
			entResult = entityService?.get(collectionName, Id,[])
		}catch(any){
			logger.error("Error Occured while getting data from mongo :: ${any.getMessage()}")
			throw new GSSPException("40001")
		}
		entResult?.getData()
	}
	
	def prepareAgentInfoReqPayload(writingProducers, groupSetUpId)
	{
		def isUpdate = false
		def requestPayload = [:] as Map
		def add = [:] as Map
		def delete = [:] as Map
		def contractGroupAgents = [] as List
		def groupSetUpIds = groupSetUpId.split('_')
		def groupNumber = groupSetUpIds[0]
		def rfpId = groupSetUpIds[1]
		writingProducers.each { writingProducer ->
			
			def roleType = writingProducer?.roleType
			def isCoreUpdated = writingProducer?.isCoreUpdated
			if(writingProducer?.isVerifyStatusCode == "Active" && (roleType == "General Agent" || roleType == "Third Party Administrator") && isCoreUpdated == "false")
			{
				def agentNumber
				def contractGroupAgent = [:] as Map
				def compensableCode = writingProducer?.compensableCode
				agentNumber = compensableCode[0]?.brokerCode
				if(roleType == "General Agent"){
					contractGroupAgent << ["agentTypeCT" : "GA"]
					delete << ["gaID" : agentNumber]
					delete << ["tpaID" : ""]
					isUpdate = true
					
				}else if (roleType == "Third Party Administrator")
				{
					contractGroupAgent << ["agentTypeCT" : "TPA"]
					delete << ["tpaID" : agentNumber]
					delete << ["gaID" : ""]
					isUpdate = true
				}
				contractGroupAgent << ["agentNumber" : agentNumber]
				contractGroupAgent << ["rfpID" : rfpId]
				contractGroupAgent << ["universalProducerID" : ""]
				contractGroupAgent << ["remittanceMethod" : ""]
				contractGroupAgents.add(contractGroupAgent)
			}
		}
		if(isUpdate)
		{
			add << ["contractGroupNumber" : groupNumber]
			add << ["contractGroupAgents" : contractGroupAgents]
			delete << ["rfpID" : rfpId]
			delete << ["brokerID" : ""]
			requestPayload << ["add" : add]
			requestPayload << ["delete" : delete]
		}
		logger.info("Delete of update AgentInfoReqPayload : ${requestPayload}")
		requestPayload
	}
	
	def prepareDeleteAgentReqPayload(deletedbrokerageCode, groupSetUpId){
		
		def requestPayload = [:] as Map
		def delete = [:] as Map
		def rfpId = groupSetUpId.split('_')[1]
		delete << ["gaID" : ""]
		delete << ["tpaID" : ""]
		delete << ["rfpID" : rfpId]
		delete << ["brokerID" : deletedbrokerageCode]
		requestPayload << ["delete" : delete]
		logger.info("Delete Agent ReqPayload : ${requestPayload}")
		requestPayload
	}
	
	/**
	 * Getting groupsetup data from mongodb.
	 * @param entityService
	 * @param brokerId
	 * @return
	 */
	def getGroupSetupData(collectionName, entityService, String id) {
		def result = null
		try{
			EntityResult entResult = entityService?.get(collectionName, id,[])
			result = entResult.getData()
			logger.info("result: ${result}")
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(any){
			logger.error("Error while getting groupsetup data by Id ---> "+any)
			throw new GSSPException("40001")
		}
		result
	}
	
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param metrefId
	 * @param tenantId
	 * @param workflow
	 * @return
	 */
	
	def deleteAgentInfo(registeredServiceInvoker, tenantId, spiPrefix, spiHeadersMap, deleteAgentReqPayload,  String[] profile, groupSetUpId) {
		def updateDeleteAgentDetailsUri = "${spiPrefix}/brokerage/updateDelete"
		def result
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				if(deleteAgentReqPayload)
					result = "true"
				else
					result = "false"
			}else if(deleteAgentReqPayload){
				logger.info("${SERVICE_ID} ----> ${groupSetUpId} --> ${updateDeleteAgentDetailsUri} ==== Calling UW IIB UpdateAgents API ")
				Date start = new Date()
				HttpEntity<String> request = registeredServiceInvoker?.createRequest(deleteAgentReqPayload, spiHeadersMap)
				def response = registeredServiceInvoker?.postViaSPI(updateDeleteAgentDetailsUri, request, Map.class)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> UW IIB UpdateAgents API for ${groupSetUpId} --> ${updateDeleteAgentDetailsUri}, elapseTime : " + elapseTime)
				logger.info("deleteAgentInfo: updateAgentsThroughIIB() spiResponseContent: ${response}")
				response = response?.getBody()
				result = response?.result
				logger.info("GS deleteAgentInfo service result ::: " + result)
			}
		} catch(e) {
			logger.error("Error while executing deleteAgentInfo service :"+e.getMessage())
			throw new GSSPException('400013')
		}
		result
	}

	
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param metrefId
	 * @param tenantId
	 * @param workflow
	 * @return
	 */

	def deleteUpdateAgentInfo(registeredServiceInvoker, tenantId, updateAgentInfoReqPayload, String[] profile, groupSetUpId) {
		def updateAgentDetailsUri = "/v1/tenants/${tenantId}/rfp/sold/updateAgents"
		def result 
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				if(updateAgentInfoReqPayload)
					result = "true"
				else
					result = "false"
			}else if(updateAgentInfoReqPayload){
				def Jsonutput = JsonOutput.toJson(updateAgentInfoReqPayload);
				logger.info("deleteUpdateAgentInfo RequestBody To UW Data : "+Jsonutput +", updateAgentDetailsUri :"+updateAgentDetailsUri)
				logger.info("${SERVICE_ID} ----> ${groupSetUpId} ==== Calling UpdateAgents API ")
				Date start = new Date()
				def response = registeredServiceInvoker.post("metlife-smd-gssp-groups-service","${updateAgentDetailsUri}",new HttpEntity(updateAgentInfoReqPayload), Map.class)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> Get UpdateAgents api for ${groupSetUpId}, elapseTime : " + elapseTime)
				response = response.getBody()
				logger.info("Response From UW DELETE_UPDATE_AGENTINFO service::: "+response)
				result = response?.result
				logger.info("UW DELETE_UPDATE_AGENTINFO service result ::: " + result)
			}
		} catch(e) {
			logger.error("Error while executing deleteUpdateAgentInfo service :"+e.getMessage())
			//throw new GSSPException('10057')
		}
		result
	}
	
	def callMaintenance(groupSetupData, tenantId, registeredServiceInvoker, maintenenceServiceVip, empwritingProducer, profile) {
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def maintenanceUri = "/v1/tenants/$tenantId/registration/organizationUser?sourceSystemCode=GSEMPR"
		logger.info("callMaintenance  maintenanceUri:: "+maintenanceUri)
		def maintenanceData = createRequestPayload(groupSetupData, empwritingProducer, tenantId)
		def Jsonutput = JsonOutput.toJson(maintenanceData);
		logger.info("maintenanceData rquest Payload : "+Jsonutput)
		def umId 
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				def response = utilObject.getTestData("employerUMID.json")
//				umId = "SMDUSERUS0010909"
				umId = response?.userInforesponse?.items[0]?.number
			}else{
				def response = registeredServiceInvoker.post(maintenenceServiceVip,"${maintenanceUri}",new HttpEntity(maintenanceData), Map.class)
				logger.info("Orgmaintenance response : "+response)
				if(response && response?.getBody()){
					umId = response?.getBody()?.userInforesponse?.items[0]?.number
				}
				logger.info("uaser maintenace id for the user ${empwritingProducer?.email} : "+umId)
			}
		} catch (e){
			logger.error(" Exception while calling Enrollment service, createGroups - ${maintenanceUri} - ${e.getMessage()}")
			//throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		umId
	}
	
	def createRequestPayload(groupSetupData, empwritingProducer, tenantId){
		def request =[:] as Map
		def contacts =[] as List
		def groupNumber = groupSetupData?.extension?.groupNumber
		request.put("number", groupNumber)
		request.put("statusCode", "101")
		request.put("statusEffectiveDate", groupSetupData?.clientInfo?.basicInfo?.effectiveDate)
		request.put("typeCode", "107")
		request.put("name", groupSetupData?.extension?.companyName)
		request.put("contacts", contacts)
		def locations = getLocationList(groupSetupData?.clientInfo?.basicInfo?.primaryAddress)
		request.put("locations", locations)
		def items = getItemsList(empwritingProducer, locations, tenantId, groupNumber)
		def users =['items':items]
		request.put("users", users)
		return request
	}
	
	def getItemsList(empwritingProducer, locations, tenantId, groupNumber){
		def items = [] as List
		def ncontact = [:] as Map
		def item =[:] as Map
		ncontact.put("organizationUserIdentifier", "")
		ncontact.put("statusCode", '101')
		ncontact.put("statusEffectiveDate", '')
		def name =[:] as Map
		name.put('title', '')
		name.put('suffix', '')
		name.put('firstName', empwritingProducer?.firstName)
		name.put('lastName', empwritingProducer?.lastName)
		name.put('middleName', '')
		ncontact.put('name', name)
		def emails =[] as List
		def email =[:]
		email.put("address", empwritingProducer?.email)
		email.put("typeCode", "Business")
		email.put("isPrimary", "1")
		emails.add(email)
		ncontact.put("emails", emails)
		def phoneNumbers = [] as List
		ncontact.put("phoneNumbers", phoneNumbers)
		def extension = [:] as Map
		extension.put('brokerKey','')
		extension.put('partyKey','')
		extension.put('employerKey',"")
		extension.put('userPersonaTypeCode','30002')
		extension.put('userRoleTypeCode', "40005")
		extension.put('organizationNumber',groupNumber)
		extension.put('tenantId',tenantId)
		extension.put('associatedOrgLocations',locations)
		ncontact.put("extension", extension)
		item.put('item', ncontact)
		items.add(item)
		return items
	}
	
	def getLocationList(location){
		def newLocation = [] as List
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
		nlocation.put('displayName', "Primary Address")
		nlocation.put('typeCode', 'home')
		def taxIds = [] as List
		nlocation.put('taxIds', taxIds)
		newLocation.add(nlocation)
		return newLocation
	}
}
