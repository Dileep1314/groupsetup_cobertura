package groovy.US

import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.framework.constants.RequestSegment
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.service.entity.GSSPEntityService

import net.minidev.json.parser.JSONParser
import spock.lang.Specification

class GET_GS_DATA_Spec extends Specification{
	final Logger LOG = LoggerFactory.getLogger(this.class)
	def reponseBody=getTestData("DBDataSample.json")
	def domain = new WorkflowDomain()
	
	def "GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Empty"() {
		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q': ' modules== null'] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}

	def "GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Null"() {
		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q': null] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}
	
	//GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Success
	def "GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Success"() {
		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q': 'module==riskAssessment.healthRisks,clientInfo.basicInfo'] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}
	
	//GET_GS_DATA_SINGLE_PAGE_DATA_Success
	def "GET_GS_DATA_SINGLE_PAGE_DATA_Success"() {
		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q': 'module==riskAssessment.healthRisks'] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}
	 
	
	//GET_GS_DATA_FULL_Success
	def "GET_GS_DATA_FULL_Success"() {

		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupId,List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->
				entityService
			}
		] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', 		tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData',viewed:'false'],(RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):[:] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}

	//GET_GS_DATA_One_Module_Data_Success
	def "GET_GS_DATA_One_Module_Data_Success"() {
		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q': 'module==riskAssessment'] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}
	//GET_GS_DATA_MULTIPLE_MODULE_Success
	def "GET_GS_DATA_MULTIPLE_MODULE_Success"() {
		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService =[get: {def collectionName, def groupSetUpId,List<String> arr->entityResult }] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup','groupSetUpId':'12345_12345', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q':'module==riskAssessment,clientInfo,comissionAcknowledgement'], tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)
		then:
		def response = domain.getServiceResponse()
		assert response != null
		assert response.getStatus() == HttpStatus.OK
	}

	/**
	 * 
	 * @return
	 */
	
	//GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Failure
	def "GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Failure"() {

		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService =[get: {def collectionName, def groupSetUpId,List<String> arr->entityResult }] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup','groupSetUpId':'12345_12345', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q':'module==clientInfo.basicInfo,riskAssessment.healthRisks'], tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response.getProperties().getAt('data') == null
	}

	//GET_GS_DATA_MULTIPLE_MODULE_Failure
	def "GET_GS_DATA_MULTIPLE_MODULE_Failure"() {

		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService =[get: {def collectionName, def groupSetUpId,List<String> arr->entityResult }] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup','groupSetUpId':'12345_12345', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q':'module==riskAssessment1,clientInfo1'], tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response.getProperties().getAt('data') == null
	}
	//GET_GS_DATA_FULL_Failure
	def "GET_GS_DATA_FULL_Failure"()
	{

		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupId, List<String> array  ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->
				entityService
			}
		] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):[:] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response.getProperties().getAt('responseEntity') == null

	}
	//GET_GS_DATA_SINGLE_PAGE_DATA_Failure
	def "GET_GS_DATA_SINGLE_PAGE_DATA_Failure"() {

		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService =[get: {def collectionName, def groupSetUpId,List<String> arr->entityResult }] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup','groupSetUpId':'12345_12345', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q':'module==riskAssessment.clientInfo1'], tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response.getProperties().getAt('data') == null
	}
	//GET_GS_DATA_One_Module_Data_Failure
	def "GET_GS_DATA_One_Module_Data_Failure"() {

		given:
		def entityResult = new EntityResult(reponseBody)
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->entityResult}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q': 'module==clientInfo'] , tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		def response = domain.getServiceResponse()
		assert response.getProperties().getAt('responseEntity') == null
	}
	
	//GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Exception
	def "GET_GS_DATA_SINGLE_PAGE_DATA_FROM_MULTIPLE_MODULES_Exception"() {
		given:
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->null}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType -> null}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['groupId':112], tenantId:'SMD',module:"riskAssessment.healthRisks,clientInfo.basicInfo"])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		Exception e= thrown()
	}
	
	//GET_GS_DATA_SINGLE_PAGE_DATA_Exception
	def "GET_GS_DATA_SINGLE_PAGE_DATA_Exception"() {
		given:
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->null}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType -> null}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['groupId':112], tenantId:'SMD',module:"riskAssessment.healthRisks"])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		Exception e= thrown()
	}
	//GET_GS_DATA_FULL_Exception
	def "GET_GS_DATA_FULL_Exception"()
	{
		given:
		//def domain = new WorkflowDomain()
		def entityService = [get:{ String collectionName, String groupId, List<String> array ->
				null
			}
		] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->
				entityService
			}
		] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',groupSetUpId:'12345_12345',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):[:], tenantId:'SMD'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		Exception e= thrown()
	}

	//GET_GS_DATA_MULTIPLE_MODULE_Exception
	def "GET_GS_DATA_MULTIPLE_MODULE_Exception"() {
		given:
		def entityService =[get: {def collectionName, def groupSetUpId,List<String> arr->null }] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType ->entityService }] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants','groupSetUpId':'12345_12345', tenantId:'SMD',groupSetup:'groupSetup', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['q':'module==riskAssessment,clientInfo'], tenantId:'SMD','groupSetUpId':'12345_12345'])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		Exception e= thrown()
	}
	//GET_GS_DATA_One_Module_Data Exception
	def "GET_GS_DATA_One_Module_Data Exception"() {
		given:
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->null}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType -> null}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['groupId':112], tenantId:'SMD',module:"riskAssessment"])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		Exception e= thrown()
	}
	
	def "GET_GS_DATA_One_Module_Data Exception_empty"() {
		given:
		def entityService = [get:{String collectionName, String groupSetUpId, List<String> array ->[]}] as GSSPEntityService
		def context = [getBean: { String beanName, Class responseBodyType -> null}] as ApplicationContext
		domain.applicationContext = context
		domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup',getData:'getData', viewed:'false'], (RequestSegment.Body.name()):[:],(RequestSegment.RequestParams.name()):['groupId':112], tenantId:'SMD',module:"riskAssessment"])
		when:
		GetGroupSetupDetails.newInstance().execute(domain)

		then:
		Exception e= thrown()
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
			throw new  GSSPException(e);
		}
	}
	
}
