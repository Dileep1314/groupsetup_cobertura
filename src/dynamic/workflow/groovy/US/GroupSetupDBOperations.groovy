package groovy.US

import org.springframework.data.mongodb.core.query.Criteria

import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory

class GroupSetupDBOperations {
	Logger logger = LoggerFactory.getLogger(GroupSetupDBOperations)
	def  getById(collectionName, service, id) {
		try{
			logger.info("GroupSetupDBOperations Get Record By Id :" + id)
			Criteria criteria = Criteria.where("_id").is(id)
			service.listByCriteria(collectionName, criteria)
		}catch(e){
			logger.error("Error Occured while getting data from mongo ---> "+e.getMessage())
			throw new GSSPException("40001")
		}
	}
	def  create(collectionName, data, service) {
		try{
			logger.info("GroupSetupDBOperations Creating New Document")
			service.create(collectionName, data)
		}catch(e){
			logger.error("Error Occured while creating document---> "+e.getMessage())
			throw new GSSPException("40001")
		}
	}
	def  saveAsDraft(collectionName, data, service, id) {
		try{
			def record = getById(collectionName, service, id)
			if(record) {
				service.updateById(collectionName, id, data)
			}else {
				create(collectionName, data, service)
			}
		}catch(e){
			logger.error("Error Occured in saveAsDraft---> "+e.getMessage())
			throw new GSSPException("40001")
		}
	}
	def  delete(collectionName, service, id) {
		try{
			logger.info("GroupSetupDBOperations Deleting Record -->"+id)
			service.deleteById(collectionName, id)
		}catch(e){
			logger.error("Error Occured while deleting document ---> "+e.getMessage())
			throw new GSSPException("40003")
		}
	}
}