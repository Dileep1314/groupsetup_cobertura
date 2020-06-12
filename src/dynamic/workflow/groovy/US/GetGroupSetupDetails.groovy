package groovy.US

import com.metlife.gssp.repo.GSSPRepository
import org.slf4j.MDC
import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService
import java.time.Instant
/**
 * Generic Class for fetch data from Mongo DB.
 * @author Durgesh Kumar Gupta
 *
 */
class GetGroupSetupDetails implements Task{


    Logger logger = LoggerFactory.getLogger(GetGroupSetupDetails)
	GroupSetupUtil util = new GroupSetupUtil()

    @Override
    Object execute(WorkflowDomain workFlow) {
        logger.info "GetGroupSetupDetails :: execute() :: Inside GS get data service"
        def entityService = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_ENTITY_SERVICE, EntityService)
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
        def groupId = workFlow.getRequestPathParams().get(GroupSetupConstants.GROUPSETUP_ID)
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(groupId.split('_')[0])
		logger.info("GetGroupSetupDetails : secValidationList: {" + secValidationList +"}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GetGroupSetupDetails : secValidationResponse: {" + secValidationResponse +"}")
		//Sec-code changes -- End
		def profile = workFlow.applicationContext.environment.activeProfiles
        def module
        def requestParamsMap = workFlow.getRequestParams()
        logger.info "GetGroupSetupDetails :: execute() :: Request Param Map :: ${requestParamsMap}"

        if (requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q)) {
            requestParamsMap?.get(GroupSetupConstants.REQUEST_PARAM_Q)?.tokenize(GroupSetupConstants.SEMI_COLON).each( { queryParam ->
                def (key, value) = queryParam.tokenize(GroupSetupConstants.DOUBLE_EQUAL)
                if(key) {
                    switch(key){
                        case GroupSetupConstants.MODULE :
                            module = value
                            break
                    }
                }
            })
        }
        def response = getGSDraftById entityService, groupId, module, GroupSetupConstants.COLLECTION_GROUP_SETUP_DATA
        if(response){
			//Duplicate Contacts fix - Removing Old / deleted contacts from SubGroup 
			removeDeletedContactsAndLocations(response)
			workFlow.addResponseBody(new EntityResult([(GroupSetupConstants.DATA): response],true))
        }
		else {
			logger.error("Group Setup Cache is empty")
			workFlow.addResponseBody(new EntityResult([(GroupSetupConstants.DATA): ""],true))
		}
        logger.info "GetGroupSetupDetails :: execute() :: Response to UI :: [response:$response]"
        
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
        workFlow.addResponseStatus(HttpStatus.OK)
    }

	def removeDeletedContactsAndLocations(response) 
	{
		def groupStructure = response?.groupStructure
		def subGroup = groupStructure?.subGroup
		def buildCaseStructure = groupStructure?.buildCaseStructure
		subGroup = util.removeDeletedContantsAndLocationsFromSubGroup(subGroup)
		logger.info("Sub group details after removing deleted contacts : subGroup :: ${subGroup}")
		removeDeletedContantsFromBuilCaseStructure(buildCaseStructure)
		logger.info("Build Case Structure details after removing deleted contacts : buildCaseStructure :: ${buildCaseStructure}")
		if(groupStructure && buildCaseStructure && subGroup)
		{
			logger.info("Updating SubGroup & Build case structure data  :: ::::")
			groupStructure.putAt("buildCaseStructure", buildCaseStructure)
			groupStructure.putAt("subGroup", subGroup)
			response.putAt("groupStructure", groupStructure)
		}
		response
	}
    /**
     * This method is use to fetch the data from mongo db based on module passed by UI
     * @param entityService
     * @param groupId
     * @param modules
     * @param collectionName
     * @return
     */
    def getGSDraftById(entityService, groupId, modules,collectionName) {
		MDC.put("GET_"+GroupSetupConstants.DB_OP_START, GroupSetupUtil.getDateAndTimeStamp())
        def result=null
        try{
            logger.info  "GetGroupSetupDetails :: getGSDraftById() :: collection Name :: ${collectionName}, entityService:${entityService}, groupId:${groupId}"
            EntityResult entResult = entityService?.get(collectionName, groupId,[])

            if(modules){
                String module=new String(""+modules)
                boolean flag =true
                if(module.contains(",")){
                    result=multipleModules(entResult.getData(),module)
                    flag=false
                }
                else if(module.contains(".") && flag){
                    result=["$modules":internalModules(entResult.getData(),module)]
                }else{
                    result=["$modules":entResult.getData()[module]]
                }
            }else{
                result=entResult.getData()
            }

            logger.info "GetGroupSetupDetails :: getGSDraftById :: result: ${result}"
        }catch(AppDataException e){
            logger.error("Data not found ---> ${e.getMessage()}")
        }catch(any){
            logger.error("Error getting draft Group Set UP Data  ${any.getMessage()}")
            throw new GSSPException("40001")
        }
		MDC.put("GET_"+GroupSetupConstants.DB_OP_END, GroupSetupUtil.getDateAndTimeStamp())
        result
    }
    /**
     * This Method handling the multiple module passed by UI
     * @param entResult
     * @param modules
     * @return
     */
    def multipleModules(entResult, modules){
        def result =[:]
        String[] module=modules.split(",")
        for(String mod:module){
            if(mod.contains(".")){
                def value=internalModules(entResult,mod)
                result<<["$mod":value]
            }else{
                result<<["$mod":entResult[mod]]
            }
        }
        result
    }

    def internalModules(entResult, modules ){
        String[] module=modules.split("\\.")
        if(module.size()<=1){
            entResult[module[0]]
        }else{
            internalModules(entResult[module[0]],module[1])
        }
    }
	
	/**
	 * Deleting contacts while sending to UI or IIB
	 * @param subGroups
	 * @return
	 */
	def removeDeletedContantsFromBuilCaseStructure(buildCaseStructure)
	{
		try{
			buildCaseStructure.each { bCaseStructure ->
				def contactsList = [] as List
				List contacts = bCaseStructure?.contacts
				contacts.each { contact ->
					def isDeletedContact = contact?.isDeleted
					if(isDeletedContact != "true")
					{
						def newContact = [:] as Map
						newContact.putAt("firstName", contact?.firstName)
						newContact.putAt("lastName", contact?.lastName)
						newContact.putAt("email", contact?.email)
						newContact.putAt("workPhone", contact?.workPhone)
						newContact.putAt("cellPhone", contact?.cellPhone)
						newContact.putAt("fax", contact?.fax)
						newContact.putAt("requireOnlineAccess", contact?.requireOnlineAccess)
						newContact.putAt("isExecutiveSameForAllRole", contact?.isExecutiveSameForAllRole)
						newContact.putAt("roleTypes", contact?.roleTypes)
						contactsList.add(newContact)
					}
				}
				bCaseStructure.putAt("contacts", contactsList)
			}
		}catch(any){
			logger.error("Error while deleting duplicate contacts subgroups data. ---> "+any)
		}
		buildCaseStructure
	}
}
