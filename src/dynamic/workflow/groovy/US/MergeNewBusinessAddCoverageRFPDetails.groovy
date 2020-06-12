package groovy.US

import com.metlife.gssp.logging.Logger
import java.time.Instant
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService
import net.minidev.json.parser.JSONParser


/**
 * This class MergeNewBusinessAddCoverageRFPDetails is used to merge different types of RFP Data.
 * @Param groupNumber
 * @author Vijayaprakash Prathipati
 */

class MergeNewBusinessAddCoverageRFPDetails {
	Logger logger = LoggerFactory.getLogger(MergeNewBusinessAddCoverageRFPDetails.class)

	def getMergedRfpsDetails(cacheId, currentDBRecord, gsspRepository, entityService, isTempCacheExist){
		logger.info("getMergedRfpsPartyTypeDetails..")
		def groupNumber = cacheId.split('_')[0]
		def dbRecordsList = getRfpsDBRecordsList(gsspRepository, groupNumber)
		logger.info("getMergedRfpsPartyTypeDetails == dbRecordsList.size ==>:  "+dbRecordsList.size)
		if(!isTempCacheExist){
			currentDBRecord = mergingClassDefinitionDetails(currentDBRecord, dbRecordsList)
			addLocationsFromNewBusiness(currentDBRecord, dbRecordsList)
			currentDBRecord = getMergedPartyTypeDetails(currentDBRecord, dbRecordsList)
		}
		logger.info("Updated Locations in buildcase structure --> "+currentDBRecord?.groupStructure?.buildCaseStructure)
		currentDBRecord = getMergedProductsDetails(currentDBRecord, dbRecordsList)
		logger.info("getMergedRfpsPartyTypeDetails == currentDBRecord with merging productDetails ==>: "+currentDBRecord)
		entityService.updateById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, cacheId, currentDBRecord)
		entityService.updateById(GroupSetupConstants.PER_COLLECTION_NAME, cacheId, currentDBRecord)
		return currentDBRecord
	}

	/**
	 * Method to fetch all groupnumber from User_Key collection
	 * @param gsspRepository
	 * @return
	 */
	def getRfpsDBRecordsList(gsspRepository, groupNumber){
		logger.info("getRfpsDBRecordsList..")
		//def recordsList=null;
		def recordsList = [] as List
		try{
			Query query = new Query()
			/* query.fields().include("groupNumber")
			 query.fields().exclude("_id") */
			query.addCriteria(Criteria.where("groupNumber").is(groupNumber))
			recordsList = gsspRepository.findByQuery("US", GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA, query)
		}catch(Exception e) {
			e.printStackTrace()
		}
	}
	/**
	 * getMergedPartyTypeDetails
	 * @param dbRecordsList,currentDBRecord
	 * @return
	 */
	def getMergedPartyTypeDetails(currentDBRecord, dbRecordsList) {
		logger.info("getMergedPartyTypeDetails ... partyTypeDetailsList..")
		def partyTypeDetailsList = [] as List
		for(def rfp : dbRecordsList) {
			def rfpType = rfp?.extension?.rfpType
			def rfpId = rfp?.extension?.rfpId
			def partyTypeDetails = rfp?.extension?.partyTypeDetails
			for(def partyType : partyTypeDetails) {
				def addRfpType
				if(GroupSetupConstants.ADD_PRODUCT.equalsIgnoreCase(rfpType))
					addRfpType = rfpType+"_"+rfpId
				else 
					addRfpType = GroupSetupConstants.NEW_BUSINESS
				partyType.put("rfpType", addRfpType)
				partyTypeDetailsList.add(partyType)
			}
		}
		logger.info("getMergedRfpsPartyTypeDetails == partyTypeDetailsList ==>:  "+partyTypeDetailsList)
		Map extension = currentDBRecord.getAt("extension")
		extension.putAt("partyTypeDetails", partyTypeDetailsList)
		currentDBRecord.putAt("extension", extension)
		logger.info("== modified currentDBRecord with partyTypeDetailsList ==>:  "+currentDBRecord)
		return currentDBRecord

	}

	/**
	 * getMergedProductsDetails
	 * @param dbRecordsList,currentDBRecord
	 * @return
	 */
	def getMergedProductsDetails(currentDBRecord, dbRecordsList) {
		logger.info("getMergedProductsDetails ... productDetails..")
		def productsObjList = [] as List
		def productCodesList = [] as List
		for(def rfp : dbRecordsList) {
			def products = rfp?.extension?.products
			for(def product : products) {
				def productCode = product?.productCode
				if(!productCodesList.contains(productCode)){
					productsObjList.add(product)
					productCodesList.add(productCode)
				}
			}
		}
		logger.info("getMergedProductsDetails == productsDetailsList ==>:  "+productsObjList)
		Map extension = currentDBRecord.getAt("extension")
		extension?.putAt("products",productsObjList)
		currentDBRecord.putAt("extension", extension)
		logger.info("== modified currentDBRecord with productDetails ==>:  "+currentDBRecord)
		return currentDBRecord
	}


	// Copying locations from NewBusiness & adding in AddProduct.
	def addLocationsFromNewBusiness(currentDBRecord, dbRecordsList)
	{
		def buildCaseStructure = [] as List
		def subGroup = [] as List
		def assignClassLocation = [] as List
		logger.info("Before updated buildCaseStructure --> "+currentDBRecord?.groupStructure?.buildCaseStructure)
		try{
			logger.info("groupSetupDataList --> "+dbRecordsList)
			for(def groupsetupData : dbRecordsList){
				logger.info("rfpType --> "+groupsetupData?.extension?.rfpType)
				if(groupsetupData?.extension?.rfpType.equalsIgnoreCase(GroupSetupConstants.NEW_BUSINESS)){
					logger.info("buildCaseStructure --> "+groupsetupData?.groupStructure?.buildCaseStructure)
					buildCaseStructure.addAll(groupsetupData?.groupStructure?.buildCaseStructure)
					groupsetupData?.groupStructure?.subGroup.each { subGroupObj ->
						def isDeleted = subGroupObj?.isDeleted
						if(isDeleted != "true")
						{
							/*def tempSubGroup = [:] as Map
							tempSubGroup.putAt("assignClassLocation", assignClassLocation)
							tempSubGroup.putAt("buildCaseStructure", subGroupObj?.buildCaseStructure)
							tempSubGroup.putAt("subGroupNumber", subGroupObj?.subGroupNumber)
							tempSubGroup.putAt("isDeleted", isDeleted)*/
							subGroupObj.putAt("assignClassLocation", assignClassLocation)
							subGroup.addAll(subGroupObj)
						}
					}
				}
			}
			currentDBRecord?.groupStructure.putAt("buildCaseStructure", buildCaseStructure)
			currentDBRecord?.groupStructure.putAt("subGroup", subGroup)
			logger.info("After updateing buildCaseStructure and SubGroup in Groupstructure ---> "+currentDBRecord?.groupStructure)
		}
		catch(any){
			logger.error("Error while updating locations in buildCaseStructure :  ${any.getMessage()}")
		}
	}
	
	/**
	 * Merging Classes from NewBusiness, previous Amendments into Current Amendment.
	 * @param currentDBRecord
	 * @param dbRecordsList
	 * @param rfpdata
	 * @return
	 */
	def mergingClassDefinitionDetails(currentDBRecord, dbRecordsList) {
		def classDefinitionList = [] as List
		def newBusinessClassesAlone = [] as List
		List currClassDef = currentDBRecord?.groupStructure?.classDefinition
		for(def groupsetupData : dbRecordsList) {
			if(groupsetupData?.extension?.rfpType.equalsIgnoreCase(GroupSetupConstants.ADD_PRODUCT))
				continue

			def existingClassDef = groupsetupData?.groupStructure?.classDefinition
			existingClassDef.each { existingClassDefOutput->
				def isSameAsExistingClass = false
				currClassDef.each{ currClassDeOoutput->
					def exitExistingClassId = existingClassDefOutput?.existingClassId
					def currExistingClassId = currClassDeOoutput?.existingClassId
					if(currExistingClassId.equalsIgnoreCase(exitExistingClassId) || exitExistingClassId == currExistingClassId) {
						isSameAsExistingClass = true
						def nbwaiveWaitingPeriodValue = ""
						def existProductDetails = existingClassDefOutput?.productDetails
						def nbclassDescriptionObj = existingClassDefOutput?.classDescription
						def nbnewClassDescription = existingClassDefOutput?.newClassDescription
						// All flags only required if user selected class based on UW Que and rftype will be Newbusiness
						currClassDeOoutput << ["isSameAsExistingClass":"Yes"] 
						currClassDeOoutput << ["isSameWaitingPeriod":""]
						currClassDeOoutput << ["classDescription": nbclassDescriptionObj]
						currClassDeOoutput << ["newClassDescription": nbnewClassDescription]
						currClassDeOoutput << ["rfpType":GroupSetupConstants.NEW_BUSINESS]
						existProductDetails.each { outExistProductDetails ->
							def provisionName = outExistProductDetails?.provisionName
							def provisionValue = outExistProductDetails?.provisionValue
							if(provisionName.equalsIgnoreCase("sameWatingPeriodforProduct")) {
								currClassDeOoutput<<["sameWaitingPeriodValue":provisionValue]
							}
							else if(provisionName.equalsIgnoreCase("waitingTime")) {
								currClassDeOoutput<<["waitingTimeValue":provisionValue]
							}else if(provisionName.equalsIgnoreCase("period")) {
								currClassDeOoutput<<["periodValue":provisionValue]
							}else if(provisionName.equalsIgnoreCase("waiveWaitingPeriod")){
								nbwaiveWaitingPeriodValue = provisionValue
							}
						}
						currClassDeOoutput?.productDetails.each { outCurrProductDetails ->
							if(outCurrProductDetails?.provisionName.equalsIgnoreCase("waiveWaitingPeriod"))
								outCurrProductDetails << ["provisionValue": nbwaiveWaitingPeriodValue]
						}
					}
				}
				if(!isSameAsExistingClass){
					existingClassDefOutput << ["rfpType":GroupSetupConstants.NEW_BUSINESS]
					newBusinessClassesAlone.add(existingClassDefOutput)
				}
			}
		}
		currClassDef.addAll(newBusinessClassesAlone)
		logger.info("After merging classDefinition details --> classDefinitionList : ${currClassDef}")
		currentDBRecord?.groupStructure.putAt("classDefinition", currClassDef)
		return currentDBRecord
	}

}
