package groovy.US


import static java.util.UUID.randomUUID

import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task


class RetrieveScanStatus implements Task{
	Logger logger = LoggerFactory.getLogger(RetrieveScanStatus.class)
	@Override
	public Object execute(WorkflowDomain workFlow) {
		logger.info("RetrieveScanStatus groovy process execution starts")
		def registeredServiceInvoker = workFlow.getBeanFromContext("registeredServiceInvoker", RegisteredServiceInvoker)
		def requestPostHeaders = workFlow.getRequestHeader()
		def headersPostList =  workFlow.getEnvPropertyFromContext('gssp.headers')
		def fileContent = workFlow.getFact("content", String.class)
		requestPostHeaders << [
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('smdgssp.tenantid'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('smdgssp.serviceid')]
		def spiPostHeadersMap = getPostRequiredHeaders(headersPostList.tokenize(',') , requestPostHeaders)
		logger.info("spiPostHeadersMap : "+spiPostHeadersMap)
		def response=virusScanRestCall(registeredServiceInvoker,spiPostHeadersMap,fileContent)
		workFlow.addFacts("response",response)
	}
	
	
	/**
	 * This method is used to call the rest client of virusscan
	 * @param registeredServiceInvoker
	 * @param spiHeadersMap
	 * @param filename
	 * @param fileContent
	 * @return
	 */
	def virusScanRestCall(registeredServiceInvoker,spiHeadersMap,fileContent){
		def postURI = "/validationEngine/scanFile"
		def uriBuilder = UriComponentsBuilder.fromPath(postURI)
		def serviceUri = uriBuilder.build(false).toString()
		def response
		def result
		def errorMessage
		try {
			logger.info("serviceUri : "+serviceUri)
			UploadUtil util = new UploadUtil()
			util.setContent(fileContent)
			def request = registeredServiceInvoker.createRequest(util, spiHeadersMap)
			response = registeredServiceInvoker.postViaSPI(serviceUri, request, Map.class)
			logger.info("Response from rest Client validateFile:: "+response)
			result=response?.getBody()
			logger.info("response?.getBody()?.errors"+response?.getBody()?.errors)
					if(response?.getBody()?.errors) {
						  logger.error("Error during calling the validateFile")
						  throw new GSSPException("INVALID_FILE")
					}
			result
		} catch (Exception e) {
			logger.error( "Got exception while calling AntiVirus App"+e.getMessage())
			throw new GSSPException("ANTI_VIRUS_ERROR")
		}
	}
	
	
	/**
	 * Request Header for PostViaSPI
	 * @param headersList
	 * @param headerMap
	 * @return
	 */
	def getPostRequiredHeaders(List headersList, Map headerMap) {
		headerMap<<[X_GSSP_TRANSACTION_ID:randomUUID().toString()]
		def spiHeaders = [:]
		for (header in headersList) {
			if (headerMap[header]) {
				spiHeaders << [(header): headerMap[header]]
			}
		}
		spiHeaders
	}
}
