package groovy.US

import org.springframework.http.HttpStatus
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.repo.GSSPRepository
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

/**
 * API to fetch all broker with basic info i.e name email, role type,broker code and online access
 * @author Vishal
 *
 */
class AgentsDetail implements Task{

	Logger logger = LoggerFactory.getLogger(DeclinedProducts.class)
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def gsspRepository = workFlow.getBeanFromContext(GroupSetupConstants.GSSP_REPO_SERVICE, GSSPRepository)
		def groupNumbers = workFlow.getRequestBody()
		def agentDetails=getAgentDetails(groupNumbers,gsspRepository)
		logger.info("Response --->"+agentDetails)
		workFlow.addResponseBody(new EntityResult(['GSAgentsInfo':agentDetails],true))
		workFlow.addResponseStatus(HttpStatus.OK)
	}
	/**
	 * 
	 * @param gsspRepository
	 * @param groupSetUpId
	 * @return
	 */
	def getGroupData(gsspRepository,groupSetUpId) {
		def response
		try{
			Query query = new Query()
			query.addCriteria(Criteria.where("groupNumber").is(groupSetUpId))
			response = gsspRepository.findByQuery("US", GroupSetupConstants.PER_COLLECTION_NAME, query)
		}catch(any){
			logger.error("Error Occured getData:: getData--->${any.getMessage()}")
			throw new GSSPException("40001")
		}
		response
	}

/**
 * Method is used to get all group requested buy user and extract basic info
 * @param groupNumbers
 * @param entityService
 * @return
 */
	def getAgentDetails(groupNumbers,entityService) {
		List grpNum =groupNumbers?.groupNumber;
		List gspList=new ArrayList()
		def agentsData
		def response=[] as List
		grpNum.each { groupNumber->
			def data=getGroupData(entityService,groupNumber)
			gspList.addAll(data)
		}
		gspList.each{ groupData->
			def licenseData=groupData?.licensingCompensableCode?.writingProducers
			def groupNumber=groupData?.groupNumber
			def companyName=groupData?.extension?.companyName
			def writingProducer=[] as List
			licenseData.each { licensOutput->
				if(!licensOutput?.roleType.equals("Employer"))
					writingProducer.add(licensOutput)
			}
			def onlineAccessData=groupData?.authorization?.grossSubmit?.onlineAccess
			agentsData=formateStructure(writingProducer,onlineAccessData,groupNumber,companyName)
			response.add(agentsData)
		}
		response	
	}
	/**
	 * Preparing Json Structure for response
	 * @param writingProducer
	 * @param onlineAccessData
	 * @param groupNumber
	 * @param companyName
	 * @return
	 */
	def formateStructure(writingProducer,onlineAccessData,groupNumber,companyName) {
		def isTpaOnlineAccess=onlineAccessData?.tpaHaveAccess
		def isGaOnlineAccess=onlineAccessData?.agentHaveAccess
		def isBrokerOnlineAccess=onlineAccessData?.brokerHaveAccess
		Map response=new HashMap()
		List agents=new ArrayList()
		writingProducer.each { output ->
			Map agentListMap=new HashMap()
			def firstName=output?.firstName
			def lastName=output?.lastName
			def roleType=output?.roleType
			def email=output?.email
			def brokers=output?.compensableCode[0]
			def brokerCode=brokers?.brokerCode
			def organizationName=brokers?.organizationName
			agentListMap.put("firstName", firstName)
			agentListMap.put("lastName", lastName)
			agentListMap.put("roleType", roleType)
			agentListMap.put("email", email)
			agentListMap.put("brokerCode", brokerCode)
			agentListMap.put("OrgName", organizationName)
			if(roleType.equalsIgnoreCase(GroupSetupConstants.ROLE_BROKER))
				agentListMap.put("haveOnlineAccess", isBrokerOnlineAccess)
			if(roleType.equalsIgnoreCase(GroupSetupConstants.ROLE_GA))
				agentListMap.put("haveOnlineAccess", isGaOnlineAccess)
			if(roleType.equalsIgnoreCase(GroupSetupConstants.ROLE_TPA))
				agentListMap.put("haveOnlineAccess", isTpaOnlineAccess)
			agents.add(agentListMap)
		}
		response.put("groupNumer", groupNumber)
		response.put("groupName", companyName)
		response.put("agentsList", agents)
		response
	}
}
