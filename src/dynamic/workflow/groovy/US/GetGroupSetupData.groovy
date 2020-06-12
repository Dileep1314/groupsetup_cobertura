package groovy.US

import java.time.Instant

import org.apache.log4j.MDC
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory

import groovy.time.TimeCategory
import groovy.time.TimeDuration
import net.minidev.json.parser.JSONParser

/**
 * 
 * @author MuskaanBatra
 *
 */
class GetGroupSetupData{
	Logger logger
	GroupSetupUtil utilObject
	def static final SERVICE_ID = "GGSD011"
	public GetGroupSetupData(){
		logger = LoggerFactory.getLogger(GetGroupSetupData)
		utilObject = new GroupSetupUtil()
	}

	
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param groupNumber
	 * @param profile
	 * @return
	 */

	def getConsolidateData(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, profile){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def endpoint = "${spiPrefix}/groupsetup/${groupNumber}/getgroupsetupdata"
		MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('IIB url for getting Group Set up data  :'+serviceUri)
		def response
		def consolidatedData
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("getGroupSetupData.json")
				consolidatedData=response?.item
			}
			else {
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling GetGroupsetupdata API : ${endpoint}")
				Date start = new Date()
				response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== GetGroupsetupdata API: : ${endpoint}, Response time : "+ elapseTime)
				logger.info("IIB Respose with body -> Group Set up data : "+response)
				consolidatedData = response?.getBody()?.item
			}
			logger.info "Sucessfully Retrieved Group Set up data IIB : "+consolidatedData
		} catch (e) {
			logger.error("Error retrieving Group Set up data from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp())
		Instant endTime = Instant.now()
		MDC.put("UI_MS_END_TIME", endTime.toString())
		consolidatedData
	
	}
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param groupNumber
	 * @param profile
	 * @return
	 */
	def getEsignature(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, String[] profile){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def memberID= "UNKNOWN" // This is hard coded value sent for majesco call
		def endpoint = "${spiPrefix}/groupsetup/${groupNumber}/${memberID}/getesign"
		MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('IIB url for getting signatureList  :'+serviceUri)
		def response
		def eSignatures=[:] as Map
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("getESign.json")?.items
				for(def eSign: response) {
					eSign = eSign?.item
					eSignatures=frameSignatureMap(eSignatures,eSign)
				}
			}
			else {
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling GetEsign API: ${endpoint} ")
				Date start = new Date()
				response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== GetEsign API: ${endpoint}, Response time : "+ elapseTime)
				logger.info("IIB Respose with body E signature : "+response)
				response = response?.getBody()?.items
				for(def eSign: response) {
					eSign = eSign?.item
					eSignatures=frameSignatureMap(eSignatures,eSign)
				}
			}
			logger.info "Sucessfully Retrieved signatureList data from IIB : " + eSignatures
		} catch (e) {
			logger.error("Error while retrieving signatureList from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		eSignatures
	}
	/**
	 *
	 * @param eSignatures
	 * @param eSign
	 * @return
	 */
	def frameSignatureMap(eSignatures,eSign){
		def purposeCode=eSign?.purpose
		def eSignList=[] as List
		if(eSignatures.getAt(purposeCode) !=null){
			eSignList=eSignatures.getAt(purposeCode)
		}
		eSignList.add(eSign)
		eSignatures.putAt(purposeCode, eSignList)
		eSignatures
		
	}
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param groupNumber
	 * @param proposalId
	 * @param profile
	 * @return
	 */
	def getRiskAssessmnet(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber,proposalId, profile){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def endpoint = "${spiPrefix}/groupsetup/${groupNumber}/${proposalId}/getriskassessment"
		MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('IIB url for getting Risk Assessment data :'+serviceUri)
		def response
		def riskAssessment
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("getRiskAssessment.json")
				riskAssessment=response?.item?.riskAssessment
			}
			else {
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling GetRiskassessment API: ${endpoint} ")
				Date start = new Date()
				response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== GetRiskassessment API: ${endpoint}, Response time : "+ elapseTime)
				logger.info("IIB Respose with body Risk Assessment :"+response)
				riskAssessment = response?.getBody()?.item
			}
			logger.info "Sucessfully Retrieved Risk Assessment data from IIB :  "+riskAssessment
		} catch (e) {
			logger.error("Error while retrieving Risk Assessment data from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		riskAssessment
	}
	
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param groupNumber
	 * @param profile
	 * @return
	 */
	def getNoClaim(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, profile){
		MDC.put(GroupSetupConstants.SUB_API_START, GroupSetupUtil.getDateAndTimeStamp());
		def endpoint = "${spiPrefix}/groupsetup/${groupNumber}/getnoclaims"
		MDC.put(GroupSetupConstants.SUB_API_NAME, endpoint);
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info('Final Request url for Get No Claims  :----->'+serviceUri)
		def response
		def noClaim
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				response = utilObject.getTestData("getNoClaims.json")
				noClaim=response?.item
			}
			else {
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== Calling GetNoClaims API: ${endpoint} ")
				Date start = new Date()
				response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], spiHeadersMap)
				Date stop = new Date()
				TimeDuration elapseTime = TimeCategory.minus(stop, start)
				logger.info("${SERVICE_ID} ----> ${groupNumber} ==== GetNoClaims API: ${endpoint}, Response time : "+ elapseTime)
				logger.info("IIB Respose with body No Claims : "+response)
				noClaim = response?.getBody()?.item
			}
			logger.info "Sucessfully Retrieved No Claims data from IIB : "+noClaim
		} catch (e) {
			logger.error("Error retrieving No Claims from SPI ${e.message}")
			throw new GSSPException("400013")
		}
		MDC.put(GroupSetupConstants.SUB_API_END, GroupSetupUtil.getDateAndTimeStamp());
		noClaim
	}
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param planCode
	 * @param profile
	 * @return
	 */
	def retreiveNonRatedProvisions(){
		def list =[] as List
		JSONParser parser = new JSONParser();
		def jsonEmptyPayloadObj = parser.parse(nonRatedProvision)
		def response = jsonEmptyPayloadObj.items
		for(def item: response){
			list.add(item?.item)
		}
		logger.info "Sucessfully Retrieved ProvisionList data response."
		return list
	}
	
	String nonRatedProvision='''{
	"items": [{
			"item": {
				"provisionName": "fullTimeHours",
				"provisionValue": "",
				"grouping": "CASEBASE",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "partTimeHours",
				"provisionValue": "",
				"grouping": "CASEBASE",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "earningDefinition",
				"provisionValue": "",
				"grouping": "BENEFITS",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "earningInclude",
				"provisionValue": "",
				"grouping": "BENEFITS",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "averagedOverPeriod",
				"provisionValue": "",
				"grouping": "BENEFITS",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "waiveWaitingPeriod",
				"provisionValue": "",
				"grouping": "BENEFITS",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "sameWatingPeriodforProduct",
				"provisionValue": "",
				"grouping": "",
				"qualifier": ""
			}
		},
		{
			"item": {
				"provisionName": "waitingTime",
				"provisionValue": "",
				"grouping": "BENEFITS",
				"qualifier": "*"
			}
		},
		{
			"item": {
				"provisionName": "period",
				"provisionValue": "",
				"grouping": "BENEFITS",
				"qualifier": "*"
			}
		}
	]
}'''
}
