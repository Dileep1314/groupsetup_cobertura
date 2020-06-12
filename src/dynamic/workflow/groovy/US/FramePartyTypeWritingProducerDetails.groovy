
package groovy.US


import java.util.concurrent.CompletableFuture

import org.springframework.web.util.UriComponentsBuilder

import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.service.TokenManagementService

/**
 * 
 * @author mchalla0
 *
 */

class FramePartyTypeWritingProducerDetails{

	Logger logger = LoggerFactory.getLogger(FramePartyTypeWritingProducerDetails.class)
	GroupSetupUtil utilObject = new GroupSetupUtil()
	GenerateCompensableCode generateCompensableCode = new GenerateCompensableCode()

	def updateDBWithPartyTypeWritingProducerDetails(workFlow, registeredServiceInvoker, mappedData, partyTypeDetails, productList, situsState, effectiveDate)
	{
		def headers
		def erlHeaders
		def productCodeList
		def tpaBrokerageId
		def writingProducerDetailsList=[]
		def profile = workFlow.applicationContext.environment.activeProfiles
		def prefix = workFlow.getEnvPropertyFromContext("spi.thirdPartyPrefix")
		def tokenService = workFlow.getBeanFromContext(GroupSetupConstants.TOKENMANAGEMENTSERVICE,TokenManagementService.class)
		def token = tokenService.getToken()
		try
		{
			headers = prepareContractServiceSPIHeaders(workFlow, token)
			erlHeaders = prepareERLServiceSPIHeaders(workFlow, token)
			productCodeList = getProductCodes(productList)
			logger.info("****** ERL Request product codes  :::: productCodeList:: ${productCodeList}")
			Map brokeageIdsMap = extractGATPABrokerageIDs(partyTypeDetails)

			Collection<CompletableFuture<Map<String, Object>>> proposalFutures = new ArrayList<>(brokeageIdsMap.size())
			brokeageIdsMap.each(){ brokeageIdEntry ->
				proposalFutures.add(CompletableFuture.supplyAsync({
					->
					def writingProducerDetails
					def partyType = brokeageIdEntry.getKey()
					def brokerCodeId = brokeageIdEntry.getValue()
					def writingProducer = contractServiceCall(brokeageIdEntry, registeredServiceInvoker, headers, prefix, partyType, brokerCodeId, profile)
					if(writingProducer){
						def writingProducerID = writingProducer?.writingProducerID
						def partyTypeRefId = writingProducer?.id
						writingProducerDetails = retrieveBrokerDetailsByID(registeredServiceInvoker, headers, writingProducerID, prefix, profile)
						if(writingProducerDetails?.name?.personGiven1Name) {
							def erlRequestBody = prepareERLRequestBody(writingProducerDetails?.governmentIDs, situsState, effectiveDate)
							def verifyStatus = getVerifyStatus (workFlow, registeredServiceInvoker, erlHeaders, erlRequestBody, productCodeList)
							writingProducerDetails = frameWritingProducer(writingProducerDetails, partyType, writingProducerID, partyTypeRefId, verifyStatus)
							writingProducerDetailsList.add(writingProducerDetails)
						}
					}
					writingProducerDetails
				}))
				CompletableFuture.allOf(proposalFutures.toArray(new CompletableFuture<?>[proposalFutures.size()]))
						.join()
			}
			logger.info("****** GATPAwritingProducerDetailsList  :::: GATPAwritingProducerDetailsList:: ${writingProducerDetailsList}")
			frameLicensingCompensableModule(writingProducerDetailsList,mappedData)
		}catch (e) {
			logger.error("Error while Framing PartyType details as WritingProducer "+e.message)
		}
	}


	def frameWritingProducer(brokerDetails, partyType, writingProducerID, partyTypeRefId, verifyStatus)
	{
		logger.info("****** Actual partyType  :::: ${partyType}, and verifyStatus : ${verifyStatus}")
		partyType = (partyType == 'Agency') ? "General Agent" : "Third Party Administrator"
		logger.info("****** Modified partyType  :::: partyType :: ${partyType}")
		Date date = new Date()
		long timeMilli = date.getTime()
		def writingProducer = [:] as Map
		def compensableCodeList = []
//		def SSN = getSSN(brokerDetails?.governmentIDs)
		if(verifyStatus == "Active")
			compensableCodeList = getCompensableCode(writingProducerID, partyTypeRefId)
		def email = getEmailId(brokerDetails?.electronicContactPoints)
		writingProducer = [
			"firstName":brokerDetails?.name?.personGiven1Name,
			"lastName":brokerDetails?.name?.personLastName,
			"email":(email) ? email : "",
			"roleType" :partyType,
			"licenseNumber" : "",
			"nationalInsuranceProducerRegistry" : "",
			"nationalProducerNumber" : "",
			"principleOfficer" : "",
			"taxId" : "",
			"tpaFeeRemittance" : "",
			"brokerName":"",
			"comissionSplitValue": "",
			"isVerifyStatusCode": verifyStatus,
			"sponsership": "",
			"compensableCode": compensableCodeList,
			"tpaFeeRemittance": "",
			"writingProducerId": timeMilli,
			"isOnlineAccess" : "No",
			"isSelected" : "true",
			"isInformationInaccurate" : "false",
			'isCoreUpdated': GroupSetupConstants.FALSE
		]
		logger.info("Framed writing producer details  :::: WritingProducer:: ${writingProducer}")
		writingProducer
	}

	def getSSN(governmentIDsList)
	{
		def SSN
		governmentIDsList.each{ governmentDetails ->
			if("SSN".equalsIgnoreCase(governmentDetails?.governmentIDTypeCode))
				SSN = governmentDetails?.governmentID
		}
		SSN
	}

	def getCompensableCode(writingProducerID, partyTypeRefId)
	{
		def compensableCodeList = []
		def compensableCode = [:] as Map
		compensableCode << [
			"producerName" : "",
			"brokerCode" : writingProducerID,
			"isInformationInaccurate" : false,
			"isSelected" : "true",
			"sequenceId" : "0",
			"organizationName" : "",
			"businessAddress" : "",
			"paymentAddress" : "",
			"partyTypeRefId" : partyTypeRefId
		]
		compensableCodeList.add(compensableCode)
		compensableCodeList
	}

	def getEmailId(electronicContactPointsList)
	{
		def isEmailFound = false
		def emailId
		electronicContactPointsList.each{ electronicContactPoint ->
			def electronicContactPointValue = electronicContactPoint?.electronicContactPointValue
			if(!isEmailFound && electronicContactPointValue != null && !electronicContactPointValue.equals(""))
			{
				emailId = electronicContactPointValue
				isEmailFound = true
			}
		}
		emailId
	}

	/**
	 *
	 * @param brokerageID
	 * @param registeredServiceInvoker
	 * @param headers
	 * @param spiprefix
	 * @return
	 */

	def contractServiceCall(brokerageIDEntry, registeredServiceInvoker, headers, spiprefix, partyType, brokerCodeId, String[] profile) {
		partyType = (partyType == 'Agency') ? "GA" : "TPA"
		def endpoint = "${spiprefix}/SmallMarketDigital/v1/broker/findByPayeeBrokerCode"
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		uriBuilder.queryParam("bkcId", brokerCodeId)
		uriBuilder.queryParam("partyType", partyType)
		def serviceUri = uriBuilder.build(false).toString()
		logger.info("serviceUri:: "+serviceUri)
		def data
		def writingProducer
		boolean isException = false
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				data = utilObject.getTestData("brokerageDetails.json")?.items
			}else{
				def response = registeredServiceInvoker?.getViaSPI(serviceUri, Map.class, [:], headers)
				logger.info("Contract Service Call Response:::"+response)
				data = response?.getBody()?.items
			}
			logger.info("Contract Service Call Response data:::"+data)
			if(data)
				writingProducer = data[0]
		} catch (e) {
			logger.error("Error while contract service call "+e.message)
			//throw new GSSPException("CONTRACT_SERVICE_CALL_ERROR")
		}
		return writingProducer
	}
	/**
	 *
	 * @param registeredServiceInvoker
	 * @param spiHeadersMap
	 * @param queryMap
	 * @param brokerID
	 * @param spiThirdPartyPrefix
	 * @return
	 */
	def retrieveBrokerDetailsByID(registeredServiceInvoker, headers, brokerID, spiThirdPartyPrefix, String[] profile){
		def endpoint = "${spiThirdPartyPrefix}/channel/gvwb/intermediaryServices/api/v1/brokers/${brokerID}"
		def uriBuilder = UriComponentsBuilder.fromPath(endpoint)
		uriBuilder.queryParam("limit", "5")
		uriBuilder.queryParam("lineofBusinessCode", "INST")
		uriBuilder.queryParam("sourceSystemCode", "MGI")
		uriBuilder.queryParam("sourceGroupName", "14")
		uriBuilder.queryParam("transactionSubTypeCode", "C")
		uriBuilder.queryParam("offset", "1")
		def serviceUri = uriBuilder.build(false).toString()
		logger.info("findByBrokerID serviceUri::: "+serviceUri)
		def response
		def data=null
		try {
			if(profile[0]?.equals(GroupSetupConstants.LOCAL)){
				data = utilObject.getTestData("brokerDetails.json")
			}else{
				response = registeredServiceInvoker?.getViaSPI(serviceUri, Map.class, [:], headers)
				data = response?.getBody()
			}
			logger.info("findByBrokerID SPI Response:: "+data)
		} catch (e) {
			logger.info("Error while searching broker details by id "+e.message)
			//throw new GSSPException("SEARCH_BROKER_BYID_ERROR")
		}

		return data
	}

	/*String frameQueryMap() {
	 return "?limit=5&lineofBusinessCode=INST&sourceSystemCode=MGI&sourceGroupName=14&transactionSubTypeCode=C&offset=1"
	 }*/
	/**
	 *
	 * @param workFlow
	 * @param tokenService
	 * @return
	 */

	def prepareContractServiceSPIHeaders(workFlow, token){
		def headerList =new ArrayList();
		def header =[:]
		header +=["X-IBM-Client-Id":[workFlow.getEnvPropertyFromContext('apmc.clientId')]]
		header +=["X-Gssp-Tracking-Id":['NA']]
		header +=["Content-Type":['application/json']]
		header +=["Password":['allow_mgi']]
		header +=["UserId":['MGI']]
		header +=["x-spi-service-id":[workFlow.getEnvPropertyFromContext('apmc.serviceId')]]
		header +=["x-gssp-tenantid":[workFlow.getEnvPropertyFromContext('apmc.tenantId')]]
		headerList =new ArrayList();
		headerList.add(token)
		//	headerList.add('Bearer eyJlbmMiOiJBMTI4Q0JDLUhTMjU2IiwiYWxnIjoiQTI1NktXIn0.oqcRkTgwTLY66Lb_0mZARaOhlrbDAoxSPqc0FQKz8Y3vq9ITWLLnvA.grLDtEhTOn8gaXX-94gIbA.npcfYi7u3NgXZOwjRbcj8Om_07omXIVQFzBbWx3iyQdsfNbi-H1ocWcPbEJtO8jW0F8cqXQrXEJCg4T-F-699J9LR8m341oaC4vsovAFU-_UrHz1l6GuHX_G_H8yEJwNYoPVZmE-xRMyh-ntHowJuDUcoMZEOol5zlJXTz6jQT6Dqi9BlZsq-8QDXJ0mrB-lsTlGp1l2EF7JTEAERh6rlajPxH-BXW9qGJoBkww40o6sUkJEasROZss9CBQzo3SmPYO4ft2SOeoek1ZETJDn0DqfDl9nXTBsOhyuVMN4dAWMpndrdHU6QPiQx3B0JH8Q-z1qGfN2fJD-YlVFEBXCeyo1lks_sTyWAWYb-z2lf7z3w76bc2VPR_-RKuGcRgOCv-8XQqusIUSg2twK4Z-EAuCKdxGYaDMeihK1dHniJ7NduV7BLc7bMWyMzTo-y32WqKOlOGdkx84WSOVDt5JwjUSNaxaYGWSQl_8vU6-sGC91sX1cPavxkMvg1El2uVjoex535CUVvRbqf3oyf8JfVgKiriH3Kwk0A5BevE-Hdr4aiQ68-_6zLqCe2HTZxNon671FAva3_S6O_sck1smt7g30efSgj35F4YiESHdam62dTIA2KNlqqUt8_THWVL0TScZToVsLplri3___jZqYDij7g4CyYHEtkl18bblcZBU5pVw5sDAhJ4eVCWVVuhZ598Sdps0iPSZdJRJ0HGucE0GrQWFBL0M-sS9eZDCqxnejK9r6s9bkTR3ndpDy1U_jhZx-MSjqEEHQ8q6NswkXWoCrQ-innPgYVtaFtgwTnPzC--p7jfeunsVkmjV-FTmeItb6MxEz88qaiEI891YqhpY0Pidvi0xsNIN7M-O37uwEHY4ObIMCipbsirNdIHlxAGht90iVmZYGY0K71aCdVlLc6ffGTfA7VVGUq-niuLU8mnxJVSk2Gg6xkAm-IshZG0Q0jQGYTn3QOHuSkizzX6WfIjSOmIDLltuDcCdctIofFX4lIo1cgwpdqPVYWcvctp1t1zKiB1ec3-gkIaX6H6X2erAN4nN75XVZs51UtTy5gTkKekwWpe9RUGUQvQhGyXidAjDsJooHa2Lev-3xagFbyTXP3GKOnwbB5qVPiJHU9soC0nNBupI2GQkmsRMSlxgVaAljYQBrYLjdbvrIufRtldrnReVvWcdk4FxPoX50iXrFry0H4ErAvdhE-D0O8ffYveIlmCe0KBz2bAxU3rmXkZNeyHEIwH_-Hln64foaXoWfFukVOJhvtUpvH91pcLAxO_dd4kBhyD_0GX_nysqFrzoztlWQ7doqOCH-Cg4WfapfIA-bF9yWUtVKP2G_FJNAf40Zqu4RbzBeZMxDBaj1jx9Fj_o1Qu2Cq95oeqDS7jA8fN2txyhCMogrVWYyqfadjm8pPqXFWScl-k-NX3QhufkM29qcPateALrE9yFfLlL8wkGECYIl_fSZfxRWCERGb62V8zorZlm6E931WY2BNq90FeKzuNFjr0rTvsdmwChkG1BXBu4W5OhPJh5kTlgvPicROlbAW-clEcnW5s9t2nK3VXKLhUOmhzkRIZ2tPxmI6s-S7ew_HNv4G6n7sPkLs4UTMfWKwr17cY8q6Njpv8-EfF5nRn1TI3rPgGxhOuVpIdRWn3ogbktcKF7STMamdB2gKgc6eJbd7-pr24EwGOhdgZEhMF-wavyr1S8gTQecONUGT26GGhRLty_dz_hiACVu6c8v4vLbrXYhgc50IdlTsT6dCcla48aYmYBa30gD7TejQG0_ZvOfdmZVBdmZqI2P5-16VjPvwB3dXZ10gy_m7if6qUktVkv_4tYreU-0pob-boJuxAc5qjLoxpz3GeeE_EfqjjX8lODqSrTNJE3ieG0NbVdjSbU5619lMdY5Wbg_6pw2VOXkfIx58YuCZsHbrqHoK4FTdv83Z7etzjFiNpCMDJe1mX9WJU5YIGjbY6Gd6Nc5KuTo5TG-I-RKdqdtKBnzypGE7Zk_kRKgxC7mdulABSx6Y6QABg8bLEuLlMB7um4VIV5zPOQTrV0DSmr6S5K2UbMOxBFgfaTRwC9u1vYFWqes3ZG7s8AAJRdjbIo8MwoLNcPD3Y3vZEW-PbljOmaQQG9yQdcivEeNEgbaOveFNBCwlDLOON5ajWjaJgNjUJieg7HCs_9J0A0klVEhK2F2J7PkzvpWlZNfFyQXz4rAgebLmm5WCcc15LOUpU5ruGIIrJmdKMy_mDFu_rKH07qTeSVSNaktL2_Z3Bmdpi66hYb_2dHy5fKjO8TRHe3cxHhB-mR5OeK-YF2jJfCFZzqaRjGiAnCaX39e0zpv0a4pJBUlz9l9Ezg1PX_cRmrlyLmOQ6R3ctbUfSaUoeMqxn5LIy4jlzRGUnKM8cXRyTdmjUzZkdbxCB4r2yO_4zz5CuyBYr5e97JvzXpwWQeMCJxno9Ea-dHi5hAxwsYIACL4XwOwoLLnOvIwA6Gxmmq5Z_BV4A6ve5usdV5JL8RezcrjwFoolSC6vEB4-ix0yGmcsDMfAnc6cfMg1S4QQFdTxoKhUBeEhg8wGv5Wd2Jk7CNEso7Lpp6kQM3OPlsmArFwqOPIC9-jKY6rk8TZ3QiRJDEQSsJyTMHAkAgFYWz3R2909xJLC4n6zRibDOh91bXpS33NOxKAgng2gaUuQvAkbptdLaCZ6WHCH1NVDAc4a4r-D--WRQUTGBewSRb4eR8ALwqA2eXhDE21x6eQ4gc5tUS464GdShuEybv7wprRdfgD8kvGTwhANjVDwdcdTH9QPK0SXdisZjTyzhrsbL39KVb0ZPK638Q_tRLJadgKZHCgNcg7ni7qwTO3A-KaLB96aEyyGJcogx0E690Av3ma2XpkPuOko02ktcrzFVjvWE4Qf9WRCicekmLjB2s550ujUQF5dq-PmNcpt4rR7LmJY23DKwfoBNgHaT29XY-Tjyekf0943rtZO1n1eWyoTHhkxy4mQ9sHSZogAwne_gGJA3pIKQZtDIdK92J5ghAjBrUe7GiKKQaoKShhtF7kaUksonoxJQGqmG_Qxa8lbOUN__LJAKQ-qk2dPYRfHC-XwHh22D78lcyQjzu3tkPO6x70KbFY5glkqbNal0l8EU8Tio_1n945VMLcSpCo8lA8C48hDYw8GZE3cFHJUuAFSaMjutzqz2l8MTA.NyMmFQ-m0gFpJiwUd0LvfA')
		header +=["Authorization":headerList]
		return header
	}
	
	def prepareERLServiceSPIHeaders(workFlow, token){
		def headerErl =[:]
		headerErl << [
			'X-IBM-Client-Id': workFlow.getEnvPropertyFromContext('apmc.clientId'),
			'Authorization':token,
			'x-gssp-tenantid': workFlow.getEnvPropertyFromContext('apmc.tenantId'),
			'x-spi-service-id': workFlow.getEnvPropertyFromContext('apmc.serviceId')]
		headerErl
	}
	
	def prepareERLRequestBody(governmentIDsList, situsState, effectiveDate)
	{
		def erlRequestBody = [:]
		def ssn = getSSN(governmentIDsList)
		erlRequestBody << [
			'governmentID' : ssn,
			'governmentIDTypeCode' : 'S',
			'policycarrierCode': GroupSetupConstants.POLICY_CARRIER_CODE_MLI,
			'applicationRegionCode':situsState,	
			'applicationSignDate':effectiveDate,
			'applicationStatusCode': 'COMP',
			'relationshipRoleCode': '37'
			]
		erlRequestBody
	}
	
	def getProductCodes(productList)
	{
		def productCodes = [] as List
		productList.each { product -> 
			productCodes.add(product?.productCode)
		}
		productCodes
	}

	/**
	 *
	 * @param groupSetupData
	 * @return
	 */
	def extractGATPABrokerageIDs(partyTypeDetails) {
		def brokeageIdsMap = [:]
		partyTypeDetails?.each{ partype ->
			def brokerageID = partype?.brokerageID
			if(brokerageID && brokerageID.contains("BKC"))
				brokeageIdsMap.put(partype?.role, brokerageID)
		}
		logger.info("after updation brokeageIdsMap is::: ${brokeageIdsMap}")
		brokeageIdsMap
	}
	
	def getVerifyStatus (workFlow, registeredServiceInvoker, erlHeaders, erlRequestBody, productCodeList)
	{
		List verifyStatus=[]
		def status
		try{
			logger.info("ERL Request headers : ${erlHeaders}")
			logger.info("ERL Request body : ${erlRequestBody}")
			verifyStatus = generateCompensableCode.postSPIErl(workFlow,registeredServiceInvoker, erlHeaders, erlRequestBody, productCodeList)
			logger.info("Successfully retrived ERL response . verifyStatus : ${verifyStatus}")
			status = verifyStatus[0]
		}catch(any)
		{
			logger.error("Error retrieving Verify status reponse from ERL  ${any}")
		}
		status = (status) ? status : GroupSetupConstants.NOT_FOUND
		status
	}
	
	
	/**
	 * 
	 * @param brokerDetails
	 * @param mappedData
	 * @return
	 */
	def frameLicensingCompensableModule(writingProducerDetailsList, mappedData){
		logger.info("Before updating writingProducerDetailsList -> mappedData is:::************************** ${mappedData}")
		def writingProducers = mappedData?.licensingCompensableCode?.writingProducers
		writingProducerDetailsList.each{ writingProducer ->
			writingProducers.add(writingProducer)
		}
		mappedData?.licensingCompensableCode.putAt("writingProducers", writingProducers)
		logger.info("After updating writingProducerDetailsList -> mappedData is:::************************** ${mappedData}")
	}
}


