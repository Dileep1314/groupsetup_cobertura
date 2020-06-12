package groovy.US

import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.framework.constants.RequestSegment

import net.minidev.json.parser.JSONParser
import spock.lang.Specification
import groovy.US.GenerateCompensableCode

class GenerateCompensable_Spec extends Specification{
	def "GroupSetupGenerateCompensable_SpecSpecSuccess"() {
		given:
		def domain = new WorkflowDomain()
		def responseEntity = new ResponseEntity(getTestData("GenerateCompensableCodeRequest.json"),HttpStatus.OK)
		def registeredServiceInvoker =['createRequest': {Map<String, Object> request,Map<String, Object> spiHeader-> null }, postViaSPI: {String uri, HttpEntity request, Class clazz -> responseEntity }]as RegisteredServiceInvoker
		def context = [getBean: { String beanName, Class responseBodyType -> registeredServiceInvoker }, getEnvironment: {[getProperty: {String value ->  "string"}] as Environment}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD', groupSetup:'groupSetup', compensablecode:'compensablecode', viewed:'false'], (RequestSegment.Body.name()):getTestData("GenerateCompensableCodeRequest.json"), (RequestSegment.Header.name()):[:], (RequestSegment.RequestParams.name()):[:], tenantId:'SMD'])

		when:
		GenerateCompensableCode.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}
	def "GroupSetupGenerateCompensable_SpecFailure"(){
		
		given:
		def domain = new WorkflowDomain()
		def responseEntity = new ResponseEntity(getTestData("GenerateCompensableCodeRequest.json"),HttpStatus.OK)
		def registeredServiceInvoker =['createRequest': {Map<String, Object> request,Map<String, Object> spiHeader-> null }, postViaSPI: {String uri, HttpEntity request, Class clazz -> responseEntity }]as RegisteredServiceInvoker
		def context = [getBean: { String beanName, Class responseBodyType -> registeredServiceInvoker }, getEnvironment: {[getProperty: {String value ->  "string"}] as Environment}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD', groupSetup:'groupSetup', compensablecode:'compensablecode', viewed:'false'], (RequestSegment.Body.name()):[null], (RequestSegment.Header.name()):[:], (RequestSegment.RequestParams.name()):[:], tenantId:'SMD'])
		when:
		GenerateCompensableCode.newInstance().execute(domain)
		then:
		Exception e = thrown()

	}
	
	def "GroupGenerateCompensable_SpecException"(){
		
		given:
		def domain = new WorkflowDomain()
		def responseEntity = new ResponseEntity(getTestData("GenerateCompensableCodeRequest.json"),HttpStatus.OK)
		def registeredServiceInvoker =['createRequest': {Map<String, Object> request,Map<String, Object> spiHeader-> null }, postViaSPI: {String uri, HttpEntity request, Class clazz -> responseEntity }]as RegisteredServiceInvoker
		def context = [getBean: { String beanName, Class responseBodyType -> registeredServiceInvoker }, getEnvironment: {[getProperty: {String value ->  "string"}] as Environment}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD', groupSetup:'groupSetup', brokers:'brokers',ssn:'ssn',validation:'validation', viewed:'false'], (RequestSegment.Body.name()):[null], (RequestSegment.Header.name()):[:], (RequestSegment.RequestParams.name()):[:], tenantId:'SMD'])
		when:
		GenerateCompensableCode.newInstance().execute(domain)
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
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
}
