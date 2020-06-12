package groovy.US


import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder
import static java.util.UUID.randomUUID
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.TokenManagementService

import net.minidev.json.JSONObject

/**
 * The class is used to update employer profile
 * When employer want to perform Billing/Claims/Benefit Administrative roles addition to Executive contact role
 * Which are being defined in group structure in the process of group creation.
 * @author MohanaChalla/Vishal
 *
 */

class UpdateEmployerProfile implements Task {
	Logger logger = LoggerFactory.getLogger(UpdateEmployerProfile.class)
	@Override
	public Object execute(WorkflowDomain workFlow) {
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def spiPrefix = workFlow.getEnvPropertyFromContext('spi.ibsePrefix')
		def userName =workFlow.getEnvPropertyFromContext('eipuser.userName')
		def password =workFlow.getEnvPropertyFromContext('eipuser.pwd').substring(1)
		def metrefId = workFlow.getFact("metRefId", String.class)
		def clientId = workFlow.getFact("clientId", String.class)
		def tokenService = workFlow.getBeanFromContext("tokenManagementService",
				TokenManagementService.class)
		def requestHeaders = workFlow.getRequestHeader()
		def headersList =  workFlow.getEnvPropertyFromContext('gssp.headers')
		requestHeaders << ['X-IBM-Client-Id' : workFlow.getEnvPropertyFromContext('apmc.clientId') ,
			'Authorization' : tokenService.getToken(),
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('apmc.tenantId'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('apmc.serviceId')]
		def spiHeadersMap = getRequiredHeaders(headersList.tokenize(',') , requestHeaders)
		def header =[:]
		header =["x-spi-service-id":workFlow.getEnvPropertyFromContext('apmc.serviceId')]
		header +=["x-gssp-tenantid":workFlow.getEnvPropertyFromContext('apmc.tenantId')]
		header +=["X-IBM-Client-Id":workFlow.getEnvPropertyFromContext('apmc.clientId')]
		header +=["Authorization":tokenService.getToken()]
		header +=["UserId":userName]
		header +=["Password":password]
		def profileResponse = getEdpmProfile(registeredServiceInvoker,spiPrefix,metrefId,spiHeadersMap)
		def identityResponse=addAdditionalIdentity(profileResponse,clientId,registeredServiceInvoker, spiPrefix, header)
		workFlow.addResponseStatus(HttpStatus.OK)
	}

	/**
	 * 
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param metRefId
	 * @param header
	 * @return
	 */
	def getEdpmProfile(registeredServiceInvoker,spiPrefix,metRefId, header){
		logger.info "getEdpmProfile header ..."+header+" spiPrefix"+spiPrefix
		def uri = "${spiPrefix}/users/${metRefId}/profile"
		def uriBuilder = UriComponentsBuilder.fromPath(uri)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info "getEdpmProfile serviceUri ..."+serviceUri
		def spiResponse

		try {
			def response = registeredServiceInvoker.getViaSPI(serviceUri, Map.class, [:], header)
			logger.info "getEdpmProfile response ..."+response
			spiResponse=response?.getBody()
			logger.info "getEdpmProfile response with getBody..."+spiResponse
		} catch (e) {
			logger.error "Error while creating consent.."+e
		}
		spiResponse
	}
	/**
	 * 	
	 * @param profileResponse
	 * @param clientID
	 * @return
	 */
	def buildAdditionalIdentityProfile(profileResponse,clientID) {
		def aids = profileResponse.getAt("item").getAt("alternateUserIDs")
		def partyId = aids[0].getAt("alternateUserID")
		JSONObject root = new JSONObject();
		root.put("partyID", partyId);
		root.put("userIDTypeCode", "SSOG");
		root.put("partySystemKeyID", clientID);
		root.toJSONString()
		root
	}
	/**
	 * This method is used for constructing the header
	 * @param headersList
	 * @param headerMap
	 * @return
	 */
	static def getRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[X_GSSP_TRANSACTION_ID:randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): [headerMap[header]]]
			}
		}
		spiHeaders
	}
	/**
	 * addAdditionalIdentity
	 * @param profileResponse
	 * @param clientId
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param header
	 * @return
	 */
	def addAdditionalIdentity(profileResponse,clientId,registeredServiceInvoker, spiPrefix, header) {
		logger.info "addAdditionalIdentity header ..."+header+" spiPrefix"+spiPrefix
		def spiUri = "${spiPrefix}/users/profile/identity"
		def uriBuilder = UriComponentsBuilder.fromPath(spiUri)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info "addAdditionalIdentity serviceUri ..."+serviceUri
		def additionalIdentityPayload = buildAdditionalIdentityProfile(profileResponse,clientId)
		logger.info "addAdditionalIdentity additionalIdentityPayload ..."+additionalIdentityPayload
		def spiResponse
		try {
			def httpEntityRequest = registeredServiceInvoker.createRequest(additionalIdentityPayload, header)
			def response = registeredServiceInvoker.postViaSPI(serviceUri, httpEntityRequest, Map.class)
			logger.info "addAdditionalIdentity response ..."+response
			spiResponse=response?.getBody()
			logger.info "addAdditionalIdentity response with getBody..."+spiResponse
		} catch (Exception ex) {
			logger.error "Error while creating consent.."+ex
		}
		spiResponse
	}
}
