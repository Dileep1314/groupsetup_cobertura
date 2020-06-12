package groovy.US

import org.apache.log4j.MDC
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService
/**
 * This class for mapping data of groups after requote as proposal id would be changed 
 * therefore, creating new group structure
 * @author MuskaanBatra
 *
 */
class GroupSetupRequoteDataMapping{
	
	Logger logger
	public GroupSetupRequoteDataMapping(){
		logger = LoggerFactory.getLogger(GroupSetupRequoteDataMapping)
	}
	
	/**
	 * 
	 * @param entityService
	 * @param mappedData
	 * @param groupNumber
	 * @param rfpId
	 * @return
	 */
	def updateDataForRequote(entityService,mappedData, groupNumber, rfpId){
		MDC.put("REQUOTE_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def existingData=checkDataAvailability(GroupSetupConstants.PER_COLLECTION_NAME, entityService, groupNumber, rfpId)
		def data=[:] as Map
		for(def existing: existingData){
			data = existing
		}
		def requoteType= data?.extension?.requote
		if(data && requoteType){
			mappedData.putAt("licensingCompensableCode", data?.licensingCompensableCode)
			if(GroupSetupConstants.PROVISIONCHANGE.equalsIgnoreCase(requoteType)){
				mappedData.putAt("clientInfo", data?.clientInfo)
			}
			def groupSetupId= data?._id
			deleteExistingData(entityService,groupSetupId)
		}
		MDC.put("REQUOTE_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		mappedData
	}
	
	/**
	 * 
	 * @param entityService
	 * @param groupSetupId
	 * @return
	 */
	def deleteExistingData(entityService,groupSetupId){
		MDC.put("REQUOTE_DELET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		try{
			entityService?.deleteById(GroupSetupConstants.PER_COLLECTION_NAME,groupSetupId)
			entityService?.deleteById(GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA,groupSetupId)
			}catch(AppDataException e){
			logger.error("Record not found --> "+e.getMessage())
		}catch(Exception e){
			logger.error("Error while getting Clients by group set up ID ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		MDC.put("REQUOTE_DELET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
	}
	
	/**
	 * 
	 * @param collectionName
	 * @param entityService
	 * @param id
	 * @param rfpId
	 * @return
	 */
	
	def checkDataAvailability(collectionName, entityService, String id, String rfpId) {
		MDC.put("REQUOTE_CHECK"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
		def result = null
		try{
			Criteria criteria = Criteria.where("groupNumber").is(id).and("rfpId").is(rfpId)
			 result=entityService.listByCriteria(collectionName, criteria)
			
			logger.info("checkDataAvailability result: ${result}")
		}catch(AppDataException e){
			logger.error("Record not found ---> "+e.getMessage())
		}catch(Exception e){
			logger.error("Error while getting checkDataAvailability by Id ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
		MDC.put("REQUOTE_CHECK"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
		result
	}
	
}