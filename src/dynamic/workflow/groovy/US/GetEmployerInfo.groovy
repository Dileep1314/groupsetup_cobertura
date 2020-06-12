/**
 * 
 */
package groovy.US

import java.time.Instant

import org.slf4j.MDC
import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService
import net.minidev.json.parser.JSONParser

/**
 * @author NarsiChereddy
 *
 */
class GetEmployerInfo implements Task {

	Logger logger = LoggerFactory.getLogger(GetEmployerInfo.class)
	def static final COLLECTION_NAME = "customers"

	@Override
	Object execute(WorkflowDomain workFlow) {
		logger.info("GetEmployerInfo groovy process execution starts")
		def jsonEmptyPayloadObj = [:]
		def entityService = workFlow.getBeanFromContext("GSSPEntityService", EntityService)
		def gsspRepository = workFlow.getBeanFromContext("GSSPRepository", GSSPRepository)
		def profile = workFlow.applicationContext.environment.activeProfiles
		def requestPathParamsMap = workFlow.getRequestPathParams()
		def requestParamsMap = workFlow.getRequestParams()
		def tenantId = requestPathParamsMap['tenantId']
		def metrefId = requestPathParamsMap['metrefId']
		logger.info "metrefId:" +metrefId
		//Sec-code changes -- Begin
		def secValidationList = [] as List
		secValidationList.add(metrefId)
		logger.info("GetEmployerInfo : secValidationList: {" + secValidationList + "}")
		ValidationUtil secValidationUtil = new ValidationUtil();
		def secValidationResponse = secValidationUtil.validateUser(workFlow, secValidationList)
		logger.info("GetEmployerInfo : secValidationResponse: {" + secValidationResponse + "}")
		//Sec-code changes -- End
		try
		{
			def listByCriteria = entityService.findById(tenantId, COLLECTION_NAME, metrefId, [])
			logger.info "response for customers Info : " +listByCriteria
			def customerName = listByCriteria?.user?.name
			def electronicContact=listByCriteria?.user?.electronicContactPoints
			def emailId;
			electronicContact.each { outPut->
				def typeCode=outPut?.electronicContactPointTypeCode
				if(typeCode.equals("EMAIL")){
					emailId=outPut?.electronicContactPointValue
				}
			}
 			jsonEmptyPayloadObj = new JSONParser().parse(employerProfile)
			Map name = jsonEmptyPayloadObj?.CustomerProfileResponse?.name
			Map email = jsonEmptyPayloadObj?.CustomerProfileResponse?.emails[0]
			email.put("address",emailId)
			email.put("isPrimary",true)
			email.put("typeCode","")
			def  emailList=[]as List
			emailList.add(email);
			name.put("firstName", customerName?.personGiven1Name)
			name.put("lastName", customerName?.personLastName)
			jsonEmptyPayloadObj?.CustomerProfileResponse?.put("name", name)
			jsonEmptyPayloadObj?.CustomerProfileResponse?.put("emails", emailList)
			workFlow.addResponseBody(new EntityResult(['item': jsonEmptyPayloadObj], true))
		}catch(any)
		{
			logger.error "Exception while getting from collection" + any.getMessage()
			workFlow.addResponseBody(new EntityResult(['item': jsonEmptyPayloadObj], true))
		}
		MDC.put(GroupSetupConstants.END_TIME, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		if(profile[0]?.toString().equalsIgnoreCase(GroupSetupConstants.PERF)) {
			GroupSetupUtil.savePerfMetrics(gsspRepository, GroupSetupConstants.GROUP_SETUP_PERF_METRICS, MDC, null)
		}
		workFlow.addResponseStatus(HttpStatus.OK)
		logger.info("GetEmployerInfo groovy process execution ends")
		
	}

	String employerProfile = '''{
			"CustomerProfileResponse" : {
				"group" : {
					"id" : "",
					"name" : ""
				},
				"role" : {
					"roleType" : "employer",
					"description" : "employer"
				},
				"additionalRoles" : {
					"roleType" : "",
					"description" : ""
				},
				"name" : {
					"suffix" : null,
					"firstName" : "",
					"middleName" : "",
					"lastName" : ""
				},
				"birthDate" : null,
				"governmentIssuedIds" : [
					{
						"typeCode" : "",
						"value" : ""
					}
				],
				"emails" : [
					{
						"address" : "",
						"typeCode" : "",
						"isPrimary" : true
					}
				],
				"phoneNumbers" : [
					{
						"countryCode" : "",
						"isPrimary" : true,
						"number" : "",
						"typeCode" : "",
						"usecode" : ""
					}
				],
				"isValidCustomer" : "Y",
				"smdKey" : ""
			},
			"employerId" : ""
		}
		}'''
}
