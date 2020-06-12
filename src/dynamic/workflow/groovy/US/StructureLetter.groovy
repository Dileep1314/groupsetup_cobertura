package groovy.US

import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.MDC
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
import com.mongodb.MongoException

import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

import groovy.time.TimeCategory
import groovy.time.TimeDuration
import net.minidev.json.parser.JSONParser


/**
 * This Class for 'StructureLetter' Details when user clicks on structure history hyperlink
 * GS structure letter page.
 * @author Vijayaprakash Prathipati/Vishal
 * CR-305 Development Changes
 */
class StructureLetter implements Task {
	Logger logger = LoggerFactory.getLogger(StructureLetter.class)
	StructureHistory stHistory=new StructureHistory()
	def static final X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	GroupSetupUtil utilObject = new GroupSetupUtil()
	GetGroupSetupData getGSData= new GetGroupSetupData()
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def spiPrefix= workFlow.getEnvPropertyFromContext(GroupSetupConstants.SPI_PREFIX)
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestBody = workFlow.getRequestBody()
		def tenantId = requestPathParamsMap['tenantId']
		def structureLetter=requestBody['structureLetter']
		def rfpId= structureLetter?.rfpId
		def proposalId=structureLetter?.uniqueId
		def groupNumber=structureLetter?.groupNumber
		def effDate=structureLetter?.structureEffectiveDate

		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupNumber)
		logger.info("StructureLetter : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("StructureLetter : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End

		def profile = workFlow.applicationContext.environment.activeProfiles
		def spiHeadersMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.GET_METHOD)
		def spiHeadersPostMap = utilObject.buildSPICallHeaders(workFlow, GroupSetupConstants.POST_METHOD)
		def groupSetUpCurrentMongoDBRecord=getRfpsDBRecordsList(gsspRepository, groupNumber,effDate,tenantId)
		def cacheId=groupNumber+"_"+rfpId+"_"+proposalId
		def groupSetUpDataFromIIB = getGSData.getConsolidateData(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, profile)
		logger.info("StructureLetter == groupSetUpDataFromIIB From IIB....${groupSetUpDataFromIIB}")
		def structureLtterDetails =[] as List
		def structureLtterDetail= framingStructureLtterDetails(registeredServiceInvoker,spiPrefix,profile,spiHeadersPostMap,groupSetUpDataFromIIB, structureLetter, groupSetUpCurrentMongoDBRecord)
		logger.info("StructureLetter == StructureLtterDetails....${structureLtterDetail}")
		structureLtterDetails.add(structureLtterDetail)
		def response=[:] as Map
		response.putAt("structureLtterDetails",structureLtterDetails)
		workFlow.addResponseBody(new EntityResult(response, true))
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	/**
	 * framing new StructureLetter Details with groupSetupData and structureLetter.
	 * @param groupSetupData
	 * @param structureLetter
	 * @return
	 */
	def framingStructureLtterDetails(registeredServiceInvoker,spiPrefix,profile,spiHeadersPostMap,groupSetUpDataFromIIB, structureLetter, groupSetUpCurrentMongoDBRecord) {
		logger.info("framingStructureLtterDetails == structureLetter.. ")
		try {
			if(groupSetUpDataFromIIB && structureLetter) {
				def grpStructureMap = [:]
				def subGrpDataList = [] as List
				def classDefDataList = [] as List
				def dhmoStates = groupSetUpCurrentMongoDBRecord?.extension?.dhmoStates
				logger.info("framingStructureLtterDetails == dhmoStates ==>:  "+ dhmoStates)
				def groupName = groupSetUpDataFromIIB?.groupName
				def groupNumber = groupSetUpDataFromIIB?.groupNumber
				def subGroup=groupSetUpDataFromIIB?.groupStructure?.subGroup
				def classDefinition=groupSetUpDataFromIIB?.groupStructure?.classDefinition
				def stHistoryEffDate = structureLetter?.structureEffectiveDate
				logger.info("framingStructureLtterDetails == stHistoryEffDate ==>:  "+ stHistoryEffDate)

				subGroup.each{ subgrp ->
					def subGroupStatus=subgrp?.subGroupStatus
					if(subGroupStatus !=null && !subGroupStatus.equalsIgnoreCase("Terminated") && !subGroupStatus.equalsIgnoreCase("NotTakenUp") && !subGroupStatus.equalsIgnoreCase("Not Taken Up") )
						subGrpDataList.add(subgrp)
				}
				logger.info("framingStructureLtterDetails == subGrpDataList ==>:  "+ subGrpDataList)

				classDefinition.each{ clsdef ->
					String classStatus=clsdef.classStatus
					if(classStatus !=null && !classStatus.equalsIgnoreCase("Terminated") && !classStatus.equalsIgnoreCase("NotTakenUp") && !classStatus.equalsIgnoreCase("Not Taken Up")) {
						classDefDataList.add(clsdef)
					}
				}
				logger.info("framingStructureLtterDetails == classDefDataList ==>:  "+ classDefDataList)
				grpStructureMap<<["groupNumber":groupNumber]
				grpStructureMap<<["groupName":groupName]
				grpStructureMap<<["dhmoStates":dhmoStates]

				def structureHistoryDetails = stHistory.getStructureHistoryDetailsFromIIB(registeredServiceInvoker,spiPrefix,spiHeadersPostMap,groupNumber,profile)
				logger.info("framingStructureLtterDetails == structureHistoryDetails ==>:  "+ structureHistoryDetails)
				def effectiveDate
				if(structureHistoryDetails) {
				  effectiveDate=checkLatestStHistory(structureHistoryDetails)
				}
				if(effectiveDate!=null)
					grpStructureMap<<["structureEffectiveDate":effectiveDate]
				else
					grpStructureMap<<["structureEffectiveDate":stHistoryEffDate]
				def structureList
				def gspStucture=[:] as Map
				gspStucture<<["subGroup":subGrpDataList]
				def newClassDefDataList=frameClassDefinition(classDefDataList,"")
				def classDefList=addDependentProduct(newClassDefDataList)
				gspStucture<<["classDefinition":classDefList]
				structureList=gspStucture
				grpStructureMap<<["groupStructure":structureList]
				logger.info("framingStructureLtterDetails == grpStructureMap ==>:  "+ grpStructureMap)
				grpStructureMap
			}
		}catch(any){
			logger.error("Exception Occured while framing structureLetter details --->${any.getMessage()}")
		}
	}

	def checkLatestStHistory(structureHistoryDetails) {
		List<Date> dates=new ArrayList<Date>();
		SimpleDateFormat fmt=new SimpleDateFormat("MM/dd/yyyy")
		def structEffDate
		try {
			if(structureHistoryDetails !=null) {
				structureHistoryDetails.each{ historyInput->
					String stEffDate=historyInput?.structureEffectiveDate
					Date date=fmt.parse(stEffDate)
					dates.add(date)
				}
				structEffDate=fmt.format(Collections.max(dates))
				logger.info("Latest Effective Date checkLatestStHistory "+structEffDate)
			}
		}catch(ParseException e) {
			logger.error("Latest Effective Date checkLatestStHistory "+e.getMessage())

		}
		structEffDate
	}



	/**
	 * Adding rider product
	 * @param clasdef
	 * @return
	 */
	def addDependentProduct(clasdef){
		try {
			clasdef.each { output->
				def products=output?.products
				Set productSet=new HashSet()
				HashSet exitingClassProduct=new HashSet()
				exitingClassProduct=products
				def productDetails=output?.productDetails
				products.each { classDefProduct->
					if(classDefProduct =="BSCL" || classDefProduct =="OPTL") {
						productDetails.each { productDetailsOutput ->
							def provisionProduct=productDetailsOutput?.products
							def coverageTypeCT=productDetailsOutput?.coverageTypeCT
							if(coverageTypeCT !="*") {
								def productList=[] as List
								productList.add(coverageTypeCT)
								productDetailsOutput.putAt("products",productList)
								productSet.add(coverageTypeCT)
							}
						}
					}
				}
				exitingClassProduct.addAll(productSet)
				output<<["products":exitingClassProduct]
			}
			logger.info("frame classDef"+clasdef)

		}catch(any) {
			logger.error("Error while rider product to existing product"+any.getMessage())

		}
		clasdef
	}

	/**
	 *
	 * @param groupSetUpData
	 * @param mongoExistingPerData
	 * @return
	 */
	def frameClassDefinition(groupSetUpData,mongoExistingPerData){
		def clsDef= groupSetUpData
		def classDefinition= [] as List
		for(def classDef: clsDef){
			def productList= classDef?.products
			def classDescription=classDef?.classDescription
			def existingClassId =classDef?.billClassID
			ArrayList jobs=classDescription?.jobTitle
			if(!jobs.empty){
				classDef?.newClassDescription="Selected Employees"
			}
			HashSet newProvisionList=new HashSet()
			HashSet productsSet=new HashSet()
			productList.each{output ->
				def productDetails=classDef?.productDetails
				for(def productDetail : productDetails) {
					def productStatus=productDetail?.productStatus
					def productName=productDetail?.productName
					if(productStatus.equalsIgnoreCase("Terminated") || productStatus.equalsIgnoreCase("NotTakenUp") || productStatus.equalsIgnoreCase("Not Taken Up")) {
						logger.info("Terminated product "+productName)
					}
					else{
						def responseValue
						if(productName.equalsIgnoreCase(output))
						    responseValue=frameGroupStructureProvisons(productDetail)
						if(responseValue !=null) {
							newProvisionList.addAll(responseValue)
						}
						productsSet.addAll(productName)
					}
				}

			}
			def censusClassName
			String existingClass=classDef.existingClassDescription
			if(!existingClass.isEmpty()) {
				int firstIndex=existingClass.lastIndexOf("Time");
				int lastIndex=existingClass.length();
				censusClassName=(String) existingClass.subSequence(firstIndex+4 , lastIndex);
			}
			classDef.putAt("products", productsSet)
			classDef.putAt("classDescription", classDescription)
			classDef.putAt("censusFileName", censusClassName)
			classDef.putAt("existingClassId", existingClassId)
			classDef.putAt("productDetails",newProvisionList)
			classDefinition.add(classDef)
		}
		logger.info("classDefinition from Majesco "+classDefinition)
		classDefinition
	}
	/**
	 *
	 * @param productList
	 * @param productDetails
	 * @return
	 */
	def frameGroupStructureProvisons(productDetails){
		def productDetailsFramed=[] as List
		def framResponse =[:] as Map
		List provisionsList=new ArrayList()
		provisionsList=["FUTUREEMPLOYEEWAITINGPERIOD", "FUTUREEMPLOYEEWAITINGPERIODUNIT", "FUNDINGSHARINGAMOUNT", "COVERAGEBASIS", "MAXBENEFITAMOUNT", "COVERAGEAMOUNT", "INCREMENTAMOUNT", "BENEFITAMOUNT"]
		def existingProvision= [] as List
		def provisionList=productDetails?.provisions
		def choice=productDetails?.choice
		def productName=productDetails?.productName
		def planDescription=productDetails?.planDescription
		def productStatus=productDetails?.productStatus
			provisionList.each{ provision->
				def provisionName=provision?.provisionName
				if(provisionName && provisionsList.contains(provisionName)){
					def productList=[] as List
					productList.add(productName)
					provision.putAt("products",productList)
					provision.putAt("choices",choice)
					def provisionValue=provision?.provisionValue
					if(provisionName.equalsIgnoreCase("FUTUREEMPLOYEEWAITINGPERIOD")) {
						provision<<["provisionName":"PRESENTEMPLOYEEWAITINGPERIOD"]
						provision<<["provisionValue":provisionValue]
					}
					if(provisionName.equalsIgnoreCase("FUTUREEMPLOYEEWAITINGPERIODUNIT")) {
						provision<<["provisionName":"PRESENTEMPLOYEEWAITINGPERIODUNIT"]
						provision<<["provisionValue":provisionValue]
					}
					productDetailsFramed.add(provision)
				}
			}
		productDetailsFramed
	}
	/**
	 * Method to fetch all groupnumber from User_Key collection
	 * @param gsspRepository
	 * @return
	 */
	def getRfpsDBRecordsList(gsspRepository, groupNumber,effDate,tenantId){
		logger.info("getRfpsDBRecordsList..")
		List <Map<String,Object>> recordsList = new ArrayList()
		def response
		try{
			Query query = new Query();
			query.addCriteria(Criteria.where("groupNumber").is(groupNumber));
			query.addCriteria(Criteria.where("clientInfo.basicInfo.effectiveDate").is(effDate))
			recordsList = gsspRepository.findByQuery(tenantId, GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, query)
			response=recordsList[0]
		}catch(AppDataException e) {
			logger.info(" error while getting data from db in getRfpsDBRecordsList.."+ e.getMessage())
		}
		response
	}
}
