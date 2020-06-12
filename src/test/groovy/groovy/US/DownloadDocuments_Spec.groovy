package groovy.US

import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import java.util.Map
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpEntity

import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.framework.constants.RequestSegment
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.service.TokenManagementService

import spock.lang.Specification
import net.minidev.json.parser.JSONParser
/**
 * 
 * @author Durgesh Kumar Gupta
 *
 */
class DownloadDocuments_Spec extends Specification{
	def "Download_Success"() {
		given:
		def domain = new WorkflowDomain()
		def searchResponse= new ResponseEntity(getTestData("searchDocument.json"),HttpStatus.OK)
		def getResponse= new ResponseEntity(new ByteArrayResource(new File(System.getProperty("user.dir")+"/src/test/data/document.json").getBytes()),HttpStatus.OK)
		def registeredServiceInvoker =['createRequest': {Map<String, Object> requestBody,Map<String, Object> spiHeader-> null }, 
			'postViaSPI': {String serviceEndPoint, HttpEntity request, Class clazz -> searchResponse },
			'getViaSPI': {java.lang.String serviceEndPoint, java.lang.Class obj, java.util.Map map, java.util.Map<String, Object> spiHeader -> getResponse }]as RegisteredServiceInvoker
		def tokenManagementService= ['getToken':{String value ->  "string"}] as TokenManagementService
		def context = [getBean: {  String beanName, Class responseBodyType -> 
							if(beanName == 'tokenManagementService')
								tokenManagementService
							else if(beanName == 'registeredServiceInvoker')
								registeredServiceInvoker }, getEnvironment: {[getProperty: {String value ->  "string"}] as Environment}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD', groupSetup:'groupSetup',  viewed:'false'], (RequestSegment.Body.name()):getTestData("dmfRequest.json"),
			 (RequestSegment.Header.name()):[:], (RequestSegment.RequestParams.name()):['q':'userId==asdasd'], tenantId:'SMD','userId':'asdasd'])
		when:
		DownloadDocuments.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}
	
	def "id_not_found"() {
		given:
		def domain = new WorkflowDomain()
		def searchResponse= new ResponseEntity("{\"item\":[]}",HttpStatus.OK)
		def getResponse= new ResponseEntity(new ByteArrayResource(new File(System.getProperty("user.dir")+"/src/test/data/document.json").getBytes()),HttpStatus.OK)
		def registeredServiceInvoker =['createRequest': {Map<String, Object> requestBody,Map<String, Object> spiHeader-> null },
			'postViaSPI': {String serviceEndPoint, HttpEntity request, Class clazz -> searchResponse },
			'getViaSPI': {java.lang.String serviceEndPoint, java.lang.Class obj, java.util.Map map, java.util.Map<String, Object> spiHeader -> getResponse }]as RegisteredServiceInvoker
		def tokenManagementService= ['getToken':{String value ->  "string"}] as TokenManagementService
		def context = [getBean: {  String beanName, Class responseBodyType ->
							if(beanName == 'tokenManagementService')
								tokenManagementService
							else if(beanName == 'registeredServiceInvoker')
								registeredServiceInvoker }, getEnvironment: {[getProperty: {String value ->  "string"}] as Environment}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD', groupSetup:'groupSetup',  viewed:'false'], (RequestSegment.Body.name()):getTestData("dmfRequest.json"),
			 (RequestSegment.Header.name()):[:], (RequestSegment.RequestParams.name()):['q':'userId==asdasd'], tenantId:'SMD','userId':'asdasd'])
		when:
		DownloadDocuments.newInstance().execute(domain)

		then:
		Exception e = thrown()
	}
	def "Document_Not_Found"() {
		given:
		def domain = new WorkflowDomain()
		def searchResponse= new ResponseEntity(getTestData("searchDocument.json"),HttpStatus.OK)
		def getResponse= new ResponseEntity(new ByteArrayResource(new File(System.getProperty("user.dir")+"/src/test/data/document.json").getBytes()),HttpStatus.OK)
		def registeredServiceInvoker =['createRequest': {Map<String, Object> requestBody,Map<String, Object> spiHeader-> null },
			'postViaSPI': {String serviceEndPoint, HttpEntity request, Class clazz -> searchResponse },
			'getViaSPI': {java.lang.String serviceEndPoint, java.lang.Class obj, java.util.Map map, java.util.Map<String, Object> spiHeader -> "" }]as RegisteredServiceInvoker
		def tokenManagementService= ['getToken':{String value ->  "string"}] as TokenManagementService
		def context = [getBean: {  String beanName, Class responseBodyType ->
							if(beanName == 'tokenManagementService')
								tokenManagementService
							else if(beanName == 'registeredServiceInvoker')
								registeredServiceInvoker }, getEnvironment: {[getProperty: {String value ->  "string"}] as Environment}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD', groupSetup:'groupSetup',  viewed:'false'], (RequestSegment.Body.name()):getTestData("dmfRequest.json"),
			 (RequestSegment.Header.name()):[:], (RequestSegment.RequestParams.name()):['q':'userId==asdasd'], tenantId:'SMD','userId':'asdasd'])
		when:
		DownloadDocuments.newInstance().execute(domain)

		then:
		Exception e = thrown()
	}

		private Map<String, Object> getTestData(String fileName) {
		JSONParser parser = new JSONParser();
		Map<String, Object> jsonObj = null;
		try {
			String workingDir = System.getProperty("user.dir");
			Object obj = parser.parse(new FileReader(workingDir +
					"/src/test/data/"+fileName));
			jsonObj = (HashMap<String, Object>) obj;
			return jsonObj;
		} catch (Exception e) {
			throw new Exception(e);
		}
	}
}

