package groovy.US

import org.springframework.http.HttpStatus
import org.springframework.web.util.UriComponentsBuilder

import com.metlife.domain.model.EntityResult
import com.metlife.domain.model.WorkflowDomain
import com.metlife.gssp.common.controller.RegisteredServiceInvoker
import com.metlife.gssp.common.exception.AppDataException
import com.metlife.gssp.exception.GSSPException
import com.metlife.gssp.logging.Logger
import com.metlife.gssp.logging.LoggerFactory
import com.metlife.gssp.taskflow.Task
import com.metlife.service.entity.EntityService

/**
 * 
 * @author MuskaanBatra
 *
 */
class GroupSetupCommonUtil {
	Logger logger

	GroupSetupCommonUtil(){
		logger = LoggerFactory.getLogger(GroupSetupCommonUtil)
	}
	
	enum states{
		AL('AL','Alabama'), AK('AK','Alaska'),AZ('AZ','Arizona'),AR('AR','Arkansas'),CA('CA','California'),CO('CO','Colorado'),CT('CT','Connecticut'),
		DE('DE','Delaware'),FL('FL','Florida'),GA('GA','Georgia'),HI('HI','Hawaii'),ID('ID','Idaho'),IL('IL','Illinois'),IN('IN','Indiana'),IA('IA','Iowa'),
		KS('KS','Kansas'),KY('KY','Kentucky'),LA('LA','Louisiana'),
		ME('ME','Maine'),MD('MD','Maryland'),MA('MA','Massachusetts'),MI('MI','Michigan'),MN('MN','Minnesota'),MS('MS','Mississippi'),MO('MO','Missouri'),MT('MT','Montana'),
		NE('NE','Nebraska'),NV('NV','Nevada'),NH('NH','New Hampshire'),NJ('NJ','New Jersey'),NM('NM','New Mexico'),NY('NY','New York'),NC('NC','North Carolina'),ND('ND','North Dakota'),
		OH('OH','Ohio'),OK('OK','Oklahoma'),OR('OR','Oregon'),PA('PA','Pennsylvania'),RI('RI','Rhode Island'),SC('SC','South Carolina'),SD('SD','South Dakota'),
		TN('TN','Tennessee'),TX('TX','Texas'),UT('UT','Utah'),VT('VT','Vermont'),VA('VA','Virginia'),
		WA('WA','Washington'),WV('WV','West Virginia'),WI('WI','Wisconsin'),WY('WY','Wyoming')
		private states(String id,String value) {
			this.id=id
			this.value = value
		}
		private final String value
		private final String id
		static final Map map
		
		   static {
			   map = [:] as TreeMap
			   values().each{ state ->
				   println "state: " + state.value 
				   map.put(state.id, state.value)
			   }
		
		   }

		static getValue(stateCode){
			map[stateCode]
		}	
	}
	def getStateName(stateCode){
		def state=states.getValue(stateCode)
		state
	}
	/**
	 * 
	 * @param productCodes - List of product codes
	 * @return
	 */
	def getConvertedProductNameList(productCodes){
		def productList =[] as List
		logger.error("productCodes for conversion "+productCodes)
		for(def productCode : productCodes){
			if(productCode == "BSCLD" || productCode == "OPTLD")
				continue
			productList.add(translateProductCode(productCode))
		}
		productList
	}
	
	/**
	 * 
	 * @param productCode
	 * @return
	 */
	def translateProductCode(String productCode) {
		String productName
		switch(productCode){
			case 'DHMO' :
				productName = 'Dental DHMO'
				break
			case 'BSCLD' :
				productName = 'Basic Dependent Life'
				break
			case 'OPTLD' :
				productName = 'Supplemental Dependent Life AD&D'
				break
			case 'DPPO' :
				productName = 'Dental (PPO)'
				break
			case 'BSCL' :
				productName = 'Basic Life AD&D'
				break
			case 'OPTL' :
				productName = 'Supplemental Life/ADD'
				break
			case 'ACC' :
				productName = 'Accident Insurance'
				break
			case 'HI' :
				productName = 'Hospital Indemnity'
				break
			case 'CIAA' :
				productName = 'Critical Illness'
				break
			case 'VIS' :
				productName = 'Vision'
				break
			case 'STD' :
				productName = 'Short Term Disability'
				break
			case 'LGL' :
				productName = 'MetLaw'
				break
			case 'LTD' :
				productName = 'Long Term Disability'
				break
			default :
				productName = productCode
				logger.info "Invalid product Code....${productCode}"
				break
		}
		productName
	}
	

}
