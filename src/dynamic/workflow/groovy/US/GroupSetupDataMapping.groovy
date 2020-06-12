package groovy.US

import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory

/**
 * 
 * @author MuskaanBatra
 *This class is for mapping group set up data from Majesco
 */
class GroupSetupDataMapping {
	Logger logger
	GroupSetupUtil utilObject
	GetGroupSetupData getGSData
	public GroupSetupDataMapping(){
		logger = LoggerFactory.getLogger(GroupSetupDataMapping)
		utilObject = new GroupSetupUtil()
		getGSData= new GetGroupSetupData()
	}

	/**
	 * 
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param groupNumber
	 * @param proposalId
	 * @param profile
	 * @param mappedData
	 * @param mongoExistingPerData
	 * @return
	 */

	def getSubmittedData(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, proposalId, String[] profile, mappedData, mongoExistingPerData){
		def module=mongoExistingPerData?.extension?.module
		logger.info("getSubmittedData module in existing data .."+module)
		if(module){
			def eSignatures= getGSData.getEsignature(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, profile)
			def licensingCompensable= mongoExistingPerData?.licensingCompensableCode
			def gropSetUpData=getGSData.getConsolidateData(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, profile)
                gropSetUpData=mongoExistingPerData
			def clientInfo=frameClientInfo(gropSetUpData,mongoExistingPerData)
			def riskAssessment= frameRiskAssessment(registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, proposalId, profile, eSignatures,gropSetUpData)
			def renewalNotification= [:] as Map
			def comissionAcknowledgement= [:] as Map
			def groupStructure= [:] as Map
			def billing= [:] as Map
			def authorization= [:] as Map
			def masterAppSignature = mongoExistingPerData?.masterAppSignature
			def masterApp = mongoExistingPerData?.masterApp
			if(GroupSetupConstants.RISK_ASSESSMENT.equalsIgnoreCase(module)){
				renewalNotification=mongoExistingPerData?.renewalNotificationPeriod
				comissionAcknowledgement=mongoExistingPerData?.comissionAcknowledgement
				groupStructure=mongoExistingPerData?.groupStructure
				billing= mongoExistingPerData?.billing
				authorization= mongoExistingPerData?.authorization
			}else if(GroupSetupConstants.NO_CLAIMS.equalsIgnoreCase(module) || GroupSetupConstants.FINALIZE_GROUP_SETUP.equalsIgnoreCase(module)){
				renewalNotification= gropSetUpData?.renewalNotificationPeriod
				comissionAcknowledgement= frameComissionAcknowledgement(gropSetUpData, mongoExistingPerData, eSignatures)
				groupStructure= frameGroupStructure(gropSetUpData, mongoExistingPerData)
				billing= frameBilling(gropSetUpData, mongoExistingPerData, eSignatures)
				authorization= frameAuthorization(gropSetUpData, mongoExistingPerData, eSignatures, registeredServiceInvoker, spiPrefix, spiHeadersMap, groupNumber, profile)
				if(GroupSetupConstants.FINALIZE_GROUP_SETUP.equalsIgnoreCase(module)){
					masterAppSignature= frameMasterAppSignature(eSignatures)
				}
			}
			/** Preparing consolidated API from data collected **/
			mappedData?.extension?.module=module
			mappedData.putAt("licensingCompensableCode", licensingCompensable)
			mappedData.putAt("clientInfo", clientInfo)
			mappedData.putAt("riskAssessment", riskAssessment)
			mappedData.putAt("renewalNotificationPeriod", renewalNotification)
			mappedData.putAt("comissionAcknowledgement", comissionAcknowledgement)
			mappedData.putAt("groupStructure", groupStructure)
			mappedData.putAt("billing", billing)
			mappedData.putAt("authorization",authorization)
			mappedData.putAt("masterAppSignature",masterAppSignature)
			mappedData.putAt("masterApp",masterApp)
		}else{
			def actualStatus=mappedData?.extension?.actualStatus
			mappedData= mongoExistingPerData
			mappedData?.extension?.actualStatus=actualStatus
			mappedData.remove("id")
		}
		logger.info(".getSubmittedData Consolidated Api as per module....."+mappedData)
		mappedData
	}
	def frameMasterAppSignature(eSignatures){
		def masterApp= [:] as Map
		def eSigns= eSignatures.getAt("2005")
		for(def eSign: eSigns){
			masterApp= eSign
		}
		logger.info(" masterApp signature from Majesco "+masterApp)
		masterApp
	}
	/**
	 * 
	 * @param gropSetUpData
	 * @param mongoExistingPerData
	 * @param eSignatures
	 * @return
	 */
	def frameAuthorization(groupSetUpData, mongoExistingPerData, eSignatures, registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, profile){
		def authorization= [:] as Map
		def permanentAuth=mongoExistingPerData?.authorization
		def grossSubmit= frameGrossSubmit(permanentAuth, eSignatures)
		def hipaa= frameHipaa(permanentAuth, groupSetUpData, eSignatures)
		def savedNoClaims= getGSData.getNoClaim(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, profile)
		def disabilityTaxation= frameDisabilityTaxation(permanentAuth, eSignatures)
		def portabilityTrust= framePortabilityTrust(eSignatures)
		def noClaims= frameNoClaims(savedNoClaims,eSignatures)
		authorization.putAt("grossSubmit",grossSubmit)
		authorization.putAt("HIPAA",hipaa)
		authorization.putAt("disabilityTaxation",disabilityTaxation)
		authorization.putAt("portabilityTrust",portabilityTrust)
		authorization.putAt("noClaims",noClaims)
		logger.info(" Authorization from Majesco "+authorization)
		authorization
	}

	/**
	 * 
	 * @param savedNoClaims
	 * @param eSignatures
	 * @return
	 */
	def frameNoClaims(savedNoClaims,eSignatures){
		def noClaims= [:] as Map
		def eSigns= eSignatures.getAt("1009")
		for(def eSign: eSigns){
			noClaims = eSign
		}
		noClaims.putAt("claimsIncurred",savedNoClaims?.claimsIncurred)
		noClaims.putAt("termConditionChecked","Yes")
		noClaims.putAt("claimInformation",savedNoClaims?.claimInformation)
		logger.info("noClaims from Majesco "+noClaims)
		noClaims
	}
	/**
	 * 
	 * @param eSignatures
	 * @return
	 */
	def framePortabilityTrust(eSignatures){
		def portabilityTrust= [:] as Map
		def eSigns= eSignatures.getAt("1008")
		for(def eSign: eSigns){
			portabilityTrust= eSign
		}
		portabilityTrust.putAt("isChecked","Yes")
		logger.info("portabilityTrust from Majesco "+portabilityTrust)
		portabilityTrust
	}
	/**
	 * 
	 * @param permanentAuth
	 * @param eSignatures
	 * @return
	 */
	def frameDisabilityTaxation(permanentAuth, eSignatures){
		def disabilityTaxation= [:] as Map
		def perDisability= permanentAuth?.disabilityTaxation
		def taxEsigns= eSignatures.getAt("1007")
		for(def taxEsign: taxEsigns){
			disabilityTaxation= taxEsign
		}
		disabilityTaxation.putAt("issueDisability",perDisability?.issueDisability)
		disabilityTaxation.putAt("payrollVendor",perDisability?.payrollVendor)
		disabilityTaxation.putAt("termConditionChecked",perDisability?.termConditionChecked)
		logger.info(" disabilityTaxation from Majesco  "+disabilityTaxation)
		disabilityTaxation
	}

	/**
	 * 
	 * @param permanentAuth
	 * @param groupSetUpData
	 * @param eSignatures
	 * @return
	 */
	def frameHipaa(permanentAuth, groupSetUpData, eSignatures){
		def hipaa= groupSetUpData?.authorization?.HIPAA
		def hipaaSigns= eSignatures.getAt("2004")
		def hipaaEConsents= eSignatures.getAt("1006")
		def eConsent=[:] as Map
		def eSign=[:] as Map
		for(def hipaaSign: hipaaSigns){
			eSign= hipaaSign
		}
		eSign.putAt("isChecked","Yes")
		for(def hipaaEConsent: hipaaEConsents){
			eConsent= hipaaEConsent
		}
		eConsent.putAt("isChecked","Yes")
		def fileDetails=permanentAuth?.HIPAA?.authorizationRequest?.fileDetails
		hipaa.putAt("fileDetails",fileDetails)
		hipaa.putAt("eSign",eSign)
		hipaa.putAt("eConsent",eConsent)
		logger.info("hipaa from Majesco "+hipaa)
		hipaa
	}
	/**
	 * 
	 * @param mongoExistingPerData
	 * @param eSignatures
	 * @return
	 */
	def frameGrossSubmit(permanentAuth, eSignatures){
		def grossSubmit = [:] as Map
		def gramLeachBailley= frameGramLeachBailley(eSignatures)
		def icNotice= frameICNotice(eSignatures)
		def grossUpLetter= frameGrossUpLetter(permanentAuth, eSignatures)
		def onlineAccess= permanentAuth?.grossSubmit?.onlineAccess
		grossSubmit.putAt("grammLeachBilley",gramLeachBailley)
		grossSubmit.putAt("ICNotice",icNotice)
		grossSubmit.putAt("grossUpLetter",grossUpLetter)
		grossSubmit.putAt("onlineAccess",onlineAccess)
		logger.info("grossSubmit from Majesco "+grossSubmit)
		grossSubmit
	}

	def frameGrossUpLetter(permanentAuth, eSignatures){
		def perGross= permanentAuth?.grossSubmit?.grossUpLetter
		def grossSigns= eSignatures.getAt("1005")
		def grossUpLetter= [:] as Map
		for(def esign : grossSigns){
			grossUpLetter= esign
		}
		grossUpLetter.putAt("isChecked","Yes")
		grossUpLetter.putAt("LTDorSTD",perGross?.LTDorSTD)
		logger.info("grossUpLetter from Majesco "+grossUpLetter)
		grossUpLetter
	}

	/**
	 * 
	 * @param eSignatures
	 * @return
	 */
	def frameICNotice(eSignatures){
		def icSigns= eSignatures.getAt("1004")
		def icNotice= [:] as Map
		for(def esign : icSigns){
			icNotice= esign
		}
		icNotice.putAt("ICNoticeChecked","Yes")
		icNotice.putAt("isChecked","Yes")
		logger.info("icNotice from Majesco "+icNotice)
		icNotice
	}

	/**
	 * 
	 * @param eSignatures
	 * @return
	 */
	def frameGramLeachBailley(eSignatures){
		def gramesigns= eSignatures.getAt("1003")
		def gramLeachBailley= [:] as Map
		for(def esign : gramesigns){
			gramLeachBailley= esign
		}
		gramLeachBailley.putAt("isChecked","Yes")
		logger.info("gramLeachBailley from Majesco ."+gramLeachBailley)
		gramLeachBailley
	}

	/**
	 * 
	 * @param gropSetUpData
	 * @param mongoExistingPerData
	 * @return
	 */
	def frameBilling(gropSetUpData,mongoExistingPerData,eSignatures){
		def billing=[:] as Map
		def eSignature = eSignatures.getAt("1002")
		def majBill= gropSetUpData?.billing
		def perBill= mongoExistingPerData?.billing
		for(def sign: eSignature){
			billing= sign
		}
		//billing.putAt("isChecked","Yes") -As part of Kick-out Notification Defect
		billing.putAt("deliveryMethod", majBill?.deliveryMethod)
		billing.putAt("billType", majBill?.billType)
		billing.putAt("premiumPayment", perBill?.premiumPayment)
		billing.putAt("premiumAmount", perBill?.premiumPayment)
		billing.putAt("paymentMode", perBill?.paymentMode)
		billing.putAt("paymentMethod", perBill?.paymentMethod)
		billing.putAt("acknowledgementIsChecked", perBill?.acknowledgementIsChecked)
		logger.info("gramLeachBailley from Majesco "+billing)
		billing
	}
	/**
	 * 
	 * @param gropSetUpData
	 * @param mongoExistingPerData
	 * @return
	 */
	def frameGroupStructure(groupSetUpData,mongoExistingPerData){
		def groupStructure=[:] as Map
		//As Per of Restricting consolidated call from Majsco
		//def classDefinition=frameClassDefinition groupSetUpData,mongoExistingPerData
		def classDefinition=mongoExistingPerData?.groupStructure?.classDefinition
		def subGroup= groupSetUpData?.groupStructure?.subGroup
		def locations= mongoExistingPerData?.groupStructure?.locations
		def billing= mongoExistingPerData?.groupStructure?.billing
		def departments= mongoExistingPerData?.groupStructure?.departments
		def contact= mongoExistingPerData?.groupStructure?.contact
		def buildCaseStructure= mongoExistingPerData?.groupStructure?.buildCaseStructure
		groupStructure.put("newClassAdded", "")
		groupStructure.put("classDefinition", classDefinition)
		groupStructure.put("locations", locations)
		groupStructure.put("billing",billing)
		groupStructure.put("departments",departments)
		groupStructure.put("contact", contact)
		groupStructure.put("buildCaseStructure", buildCaseStructure)
		groupStructure.put("subGroup", subGroup)
		logger.info(" groupStructure from Majesco "+groupStructure)
		groupStructure
	}
	/**
	 * 
	 * @param groupSetUpData
	 * @param mongoExistingPerData
	 * @return
	 */
	def frameClassDefinition(groupSetUpData,mongoExistingPerData){
		def clsDef= groupSetUpData?.groupStructure?.classDefinition
		def classDefinition= [] as List
		for(def classDef: clsDef){
			def productList= classDef?.products
			def classDescription=frameClassDescription classDef
			ArrayList jobs=classDescription?.jobTitle
			if(!jobs.empty){
				classDef?.newClassDescription="Selected Employees"
			}
			def productDetails= frameGroupStructureProvisons(productList,classDef?.productDetails)
			classDef.putAt("classDescription", classDescription)
			classDef.putAt("productDetails",productDetails)
			classDefinition.add(classDef)
		}
		logger.info("classDefinition from Majesco "+classDefinition)

		classDefinition
	}
	/**
	 * 
	 * @param productList
	 * @param productDetails
	 * @return
	 */
	def frameGroupStructureProvisons(productList,productDetails){
		def productDetailsFramed=[] as List
		def existingProvision= [] as List
		def sameWatingPeriodforProduct= false
		def waitingTimeValues= [] as List
		def waitingTimeAndPeriod=[] as List
		for(def productDetail: productDetails){
			def provisionList=productDetail?.provisions
			def waitingTimeVal
			for(def provision: provisionList){
				def provisionName=provision?.provisionName
				if(provisionName){
					if(provisionName.equals("waitingTime")){
						def prod=[] as List
						prod.add(productDetail?.productName)
						waitingTimeVal=provision?.provisionValue
						provision.putAt("products",prod)
						waitingTimeAndPeriod.add(provision)
					}else if(provisionName.equals("period")){
						def prod=[] as List
						prod.add(productDetail?.productName)
						waitingTimeValues.add(waitingTimeVal+" "+provision?.provisionValue)
						provision.putAt("products",prod)
						waitingTimeAndPeriod.add(provision)
					}else if(!existingProvision.contains(provisionName)){
						if(provisionName.equalsIgnoreCase("earnings")){
							def earningProvisions =[] as List
							existingProvision.add("earningDefinition")
							existingProvision.add("earningInclude")
							earningProvisions=getEarningProvisionList(provision,productList)
							productDetailsFramed= addObject(productDetailsFramed,earningProvisions)
						}else{
							existingProvision.add(provisionName)
							provision.putAt("products",productList)
							productDetailsFramed.add(provision)
						}
					}else{
						continue
					}
				}else{
					continue
				}
			}
		}
		List waitingPeriodProvisions = waitingPeriodProvisionsList(waitingTimeValues,waitingTimeAndPeriod,productList)
		existingProvision.add("sameWatingPeriodforProduct")
		existingProvision.add("waitingTime")
		existingProvision.add("period")
		productDetailsFramed= addObject(productDetailsFramed,waitingPeriodProvisions)
		List remainingProvisions=remainingDefaultProvision(existingProvision,productList)
		if(!remainingProvisions.isEmpty()){
			productDetailsFramed= addObject(productDetailsFramed,remainingProvisions)
		}
		productDetailsFramed
	}

	def addObject(productDetailsFramed,anotherList){
		for(def obj:anotherList){
			productDetailsFramed.add(obj)
		}
		productDetailsFramed
	}
	/**
	 * 
	 * @param waitingTimeValues
	 * @param waitingTimeAndPeriod
	 * @param productList
	 * @return
	 */
	def waitingPeriodProvisionsList(waitingTimeValues,List waitingTimeAndPeriod,productList){
		def value=""
		def flag= false
		for(def waitingTimeVal: waitingTimeValues){
			if(value){
				if(value.equalsIgnoreCase(waitingTimeVal)){
					flag=true
					continue
				}else{
					flag=false
					break
				}
			}else{
				value= waitingTimeVal
			}
		}
		def provision=[:] as Map
		def waitingProvisionList=[] as List

		if(flag.equals(false)){
			provision.put("products", productList)
			provision.put("provisionName", "sameWatingPeriodforProduct")
			provision.put("provisionValue", "No")
			provision.put("grouping", "")
			provision.put("qualifier", "")
			waitingProvisionList.add(provision)
			//			waitingProvisionList.add(waitingTimeAndPeriod)
			waitingProvisionList=addObject(waitingProvisionList,waitingTimeAndPeriod)
		}else{
			provision.put("products", productList)
			provision.put("provisionName", "sameWatingPeriodforProduct")
			provision.put("provisionValue", "Yes")
			provision.put("grouping", "")
			provision.put("qualifier", "")
			def waitingFlag= true
			def periodFlag= true
			for(def waitProv:waitingTimeAndPeriod){
				if(waitingFlag && "waitingTime".equalsIgnoreCase(waitProv?.provisionName)){
					waitProv.putAt("products", productList)
					waitingProvisionList.add(waitProv)
					waitingFlag=false
				}else if(periodFlag && "period".equalsIgnoreCase(waitProv?.provisionName)){
					waitProv.putAt("products", productList)
					waitingProvisionList.add(waitProv)
					periodFlag=false
				}else if(!waitingFlag && !periodFlag){
					break
				}
			}
			waitingProvisionList.add(provision)
		}
		waitingProvisionList
	}
	/**
	 * 
	 * @param existingProvision
	 * @param productList
	 * @return
	 */
	def remainingDefaultProvision(existingProvision,productList){
		def defaultProvisions=retreiveNonRatedProvisions()
		def remainingProvisions =[] as List
		for(def defaultProvision: defaultProvisions){
			def provisionName = defaultProvision?.provisionName
			if(!existingProvision.contains(provisionName)){
				defaultProvision.putAt("products", productList)
				remainingProvisions.add(defaultProvision)
			}else{
				continue
			}
		}
		remainingProvisions
	}
	/**
	 * 
	 * @param provision
	 * @param productList
	 * @return
	 */
	def getEarningProvisionList(provision,productList){
		def earningProvisions =[] as List
		String provisionValues=provision?.provisionValue
		String[] earningValues=provisionValues.split("\\+")
		def earningDefinitionValue = ""
		def earningIncludeValue =""
		if(earningValues.size()>=2){
			earningDefinitionValue=earningValues[0]
			earningIncludeValue=earningValues[1]
		}
		def earningDefinitionProvision=[:] as Map
		def earningIncludeProvision=[:] as Map
		earningDefinitionProvision.put("products",productList)
		earningDefinitionProvision.put("provisionName","earningDefinition")
		earningDefinitionProvision.put("provisionValue",earningDefinitionValue)
		earningDefinitionProvision.put("grouping",provision?.grouping)
		earningDefinitionProvision.put("qualifier",provision?.qualifier)
		earningProvisions.add(earningDefinitionProvision)

		earningIncludeProvision.put("products",productList)
		earningIncludeProvision.put("provisionName","earningInclude")
		earningIncludeProvision.put("provisionValue",earningIncludeValue)
		earningIncludeProvision.put("grouping",provision?.grouping)
		earningIncludeProvision.put("qualifier",provision?.qualifier)
		earningProvisions.add(earningIncludeProvision)

		earningProvisions
	}
	def retreiveNonRatedProvisions(){
		getGSData.retreiveNonRatedProvisions()
	}
	/**
	 * 
	 * @param classDef
	 * @return
	 */
	def frameClassDescription(classDef){
		def classDescription=classDef?.classDescription
		ArrayList defaultTitles = ['Owners', 'Hourly Employees', 'Salaried Employees', 'Partners', 'Physicians', 'Sales Employees', 'Chief Executive Officer(s)', 'Supervisors', 'President(s)', 'Vice President(s)', 'Union Employees', 'Non-Union Employee', 'Managers', 'Board Of Directors']
		def savedTitles=classDescription?.jobTitle
		def jobTitle =[] as List
		if(savedTitles){
			for(def defaultTitle: defaultTitles){
				for(def job: savedTitles){
					def allTitles=[:] as Map
					def title=job?.title
					if(title && !defaultTitle.equals(title)){
						allTitles<<['title' : defaultTitle]
						allTitles<<['isChecked': false]
					}else{
						allTitles=job
					}
					jobTitle.add(allTitles)
				}
			}
		}
		classDescription.putAt("jobTitle", jobTitle)
		classDescription
	}

	/**
	 * 
	 * @param gropSetUpData
	 * @param mongoExistingPerData
	 * @param eSignatures
	 * @return
	 */
	def frameComissionAcknowledgement(gropSetUpData,mongoExistingPerData,eSignatures){
		def comissionAcknowledgment=[] as List
		def perComAck=mongoExistingPerData?.comissionAcknowledgment
		for(def comAck: perComAck){
			def eConsents= eSignatures.getAt("1001")
			def eSigns= eSignatures.getAt("2003")
			def brokerCode= comAck?.brokerDetails?.brokerCode
			eConsents.each(){eConsent ->
				if(brokerCode.equalsIgnoreCase(eConsent?.transactionReference)){
					eConsent.putAt("isChecked","Yes")
					comAck.putAt("eConsent", eConsent)
				}
			}
			eSigns.each(){eSign ->
				if(brokerCode.equalsIgnoreCase(eSign?.transactionReference))
					eSign.putAt("isChecked","Yes")
				comAck.putAt("eSign", eSign)
			}
			comissionAcknowledgment.add(comAck)
		}
		logger.info("comissionAcknowledgment from Majesco "+comissionAcknowledgment)
		comissionAcknowledgment
	}
	/**
	 * 
	 * @param registeredServiceInvoker
	 * @param spiPrefix
	 * @param spiHeadersMap
	 * @param groupNumber
	 * @param profile
	 * @param eSignatures
	 * @return
	 */
	def frameRiskAssessment(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, proposalId, profile, eSignatures,gropSetUpData){
		
		//As Part of Kickout scenario changing from Majesco data to parmanent
		//def riskAssessment = getGSData.getRiskAssessmnet(registeredServiceInvoker, spiPrefix,spiHeadersMap, groupNumber, proposalId, profile)
		
		def riskAssessment=gropSetUpData?.riskAssessment
		def riskAssessmentAcknowledgment= [] as List
		def brokerEsigns=eSignatures.getAt("2002")
		def employerEsigns=eSignatures.getAt("2001")
		for(def employerEsign: employerEsigns){
			employerEsign.putAt("isChecked","Yes")
			riskAssessmentAcknowledgment.add(employerEsign)
		}
		for(def brokerEsign: brokerEsigns){
			brokerEsign.putAt("isChecked","Yes")
			riskAssessmentAcknowledgment.add(brokerEsign)
		}
		if(riskAssessment)
		  riskAssessment.putAt("riskAssesmentAcknowledgement", riskAssessmentAcknowledgment)
		logger.info("riskAssessment from Majesco :"+riskAssessment)
		riskAssessment
	}

	/**
	 * 
	 * @param gropSetUpData
	 * @param mongoExistingPerData
	 * @return
	 */
	def frameClientInfo(gropSetUpData,mongoExistingPerData){
		def clientInfo= gropSetUpData?.clientInfo
		def basicInfo=clientInfo?.basicInfo
		def contributions=mongoExistingPerData?.clientInfo?.contributions
		def isSeparateCorrespondenceAddress= checkCorrespondanceAddress(basicInfo)
		def erisa=frameErisaStructure(clientInfo,mongoExistingPerData)
		basicInfo.putAt("isSeparateCorrespondenceAddress", isSeparateCorrespondenceAddress)
		clientInfo.putAt("basicInfo", basicInfo)
		clientInfo.putAt("contributions", contributions)
		clientInfo.putAt("erisa", erisa)
		logger.info("ClientInfo from Majesco :"+clientInfo)
		clientInfo
	}
	/**
	 * 
	 * @param basicInfo
	 * @return
	 */
	def checkCorrespondanceAddress(basicInfo){
		def correspondanceAddress = basicInfo?.correspondenceAddress?.addressLine1
		if(!"".equals(correspondanceAddress)){
			return "Yes"
		}
		return "No"
	}
	/**
	 * 
	 * @param clientInfo
	 * @param mongoExistingPerData
	 * @return
	 */
	def frameErisaStructure(clientInfo,mongoExistingPerData){
		def erisaNew=clientInfo?.erisa
		def policyCoveredUnderSection125=erisaNew?.policyCoveredUnderSection125
		def existingErisa=mongoExistingPerData?.clientInfo?.erisa

		def policySection125List=[] as List
		for(def section125: policyCoveredUnderSection125){
			def provisionVal=section125?.provisionValue
			if("Y".equalsIgnoreCase(provisionVal)){
				section125?.provisionValue="Yes"
			}else if("N".equalsIgnoreCase(provisionVal)){
				section125?.provisionValue="No"
			}
			policySection125List.add(section125)
		}
		erisaNew.putAt("policyCoveredUnderSection125",policySection125List)
		erisaNew.putAt("planYearEnds", existingErisa?.planYearEnds)
		erisaNew.putAt("fiscalYearMonth", existingErisa?.fiscalYearMonth)
		logger.info("erisa from Majesco : "+erisaNew)
		erisaNew
	}
}
