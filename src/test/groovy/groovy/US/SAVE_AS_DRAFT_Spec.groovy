package groovy.US

import org.springframework.context.ApplicationContext
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.http.HttpStatus

import spock.lang.Specification

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.framework.constants.RequestSegment
import com.metlife.service.entity.GSSPEntityService

class SAVE_AS_DRAFT_Spec extends Specification {

	def "saveDraftCreateSuccess"() {
		given:
			def domain = new WorkflowDomain()
			def requestBody =[  
			   "_id":"RFP1010_GROUP10",
			   "screen":"GS_CLIENT_INFO",
			   "basicInfo":[  
			      "groupNumber":"",
			      "eligibleLives":"",
			      "sicCodeDescription":"",
			      "companyName":"",
			      "isLegalGroupNameDifferent":"",
			      "legalGroupName":"",
			      "doingBusinessAs":"",
			      "customerOrganizationType":"",
			      "federalTaxId":""
				  ]
			]
	
	
			def entityResult = new EntityResult(requestBody)
			def emptyResult = []
			def entityService = [create:{String collectionName,Object obj1 ->entityResult}, listByCriteria:{String collectionName, Criteria criteria ->emptyResult}
			] as GSSPEntityService
			def context = [getBean: { String beanName, Class responseBodyType -> entityService }
			] as ApplicationContext
			domain.applicationContext = context
			domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup', save:'save',viewed:'false'], (RequestSegment.Body.name()):requestBody, tenantId:'SMD'])
		when:
			SAVE_AS_DRAFT.newInstance().execute(domain)
		then:
			def response = domain.getServiceResponse()
			assert response != null
			assert response.getStatus() == HttpStatus.OK
	}
	
	def "saveDraftUpdateSuccess"() {
		given:
			def domain = new WorkflowDomain()
			def requestBody =[
			   "_id":"RFP1010_GROUP10",
			   "screen":"GS_CLIENT_INFO",
			   "basicInfo":[
				  "groupNumber":"",
				  "eligibleLives":"",
				  "sicCodeDescription":"",
				  "companyName":"",
				  "isLegalGroupNameDifferent":"",
				  "legalGroupName":"",
				  "doingBusinessAs":"",
				  "customerOrganizationType":"",
				  "federalTaxId":""
				  ]
			]
	
	
			def entityResult = new EntityResult(requestBody)
			def emptyResult = [requestBody]
			def entityService = [listByCriteria:{String collectionName, Criteria criteria ->emptyResult} , updateById:{String collectionName, String id, Object data ->entityResult}
			] as GSSPEntityService
			def context = [getBean: { String beanName, Class responseBodyType -> entityService }
			] as ApplicationContext
			domain.applicationContext = context
			domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup', save:'save',viewed:'false'], (RequestSegment.Body.name()):requestBody, tenantId:'SMD'])
		when:
			SAVE_AS_DRAFT.newInstance().execute(domain)
		then:
			def response = domain.getServiceResponse()
			assert response != null
			assert response.getStatus() == HttpStatus.OK
	}
	
	def "saveDraftUpdateFailureForWrongScreenName"() {
		given:
			def domain = new WorkflowDomain()
			def requestBody =[
			   "_id":"RFP1010_GROUP10",
			   "screen":"abc",
			   "basicInfo":[
				  "groupNumber":"",
				  "eligibleLives":"",
				  "sicCodeDescription":"",
				  "companyName":"",
				  "isLegalGroupNameDifferent":"",
				  "legalGroupName":"",
				  "doingBusinessAs":"",
				  "customerOrganizationType":"",
				  "federalTaxId":""
				  ]
			]
	
	
			def entityResult = new EntityResult(requestBody)
			def emptyResult = [requestBody]
			def entityService = [listByCriteria:{String collectionName, Criteria criteria ->emptyResult} , updateById:{String collectionName, String id, Object data ->entityResult}
			] as GSSPEntityService
			def context = [getBean: { String beanName, Class responseBodyType -> entityService }
			] as ApplicationContext
			domain.applicationContext = context
			domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup', save:'save',viewed:'false'], (RequestSegment.Body.name()):requestBody, tenantId:'SMD'])
		when:
			SAVE_AS_DRAFT.newInstance().execute(domain)
		then:
			Exception e = thrown()
			assert e.getMessage() == 'INVALID_SCREEN'
	}
	
	def "saveDraftUpdateMongoFailure"() {
		given:
			def domain = new WorkflowDomain()
			def requestBody =[
			   "_id":"RFP1010_GROUP10",
			   "screen":"GS_CLIENT_INFO",
			   "basicInfo":[
				  "groupNumber":"",
				  "eligibleLives":"",
				  "sicCodeDescription":"",
				  "companyName":"",
				  "isLegalGroupNameDifferent":"",
				  "legalGroupName":"",
				  "doingBusinessAs":"",
				  "customerOrganizationType":"",
				  "federalTaxId":""
				  ]
			]
	
	
			def entityResult = new EntityResult(requestBody)
			def emptyResult = [requestBody]
			def entityService = [updateById:{String collectionName, String id, Object data ->entityResult}
			] as GSSPEntityService
			def context = [getBean: { String beanName, Class responseBodyType -> entityService }
			] as ApplicationContext
			domain.applicationContext = context
			domain.addFacts("request", [(RequestSegment.PathParams.name()):[version:'v1', contract:'tenants', tenantId:'SMD',groupSetup:'groupSetup', save:'save',viewed:'false'], (RequestSegment.Body.name()):requestBody, tenantId:'SMD'])
		when:
			SAVE_AS_DRAFT.newInstance().execute(domain)
		then:
			Exception e = thrown()
			assert e.getMessage() == '40001'
	}
}
