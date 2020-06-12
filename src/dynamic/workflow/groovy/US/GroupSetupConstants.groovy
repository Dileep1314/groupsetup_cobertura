package groovy.US


/**
 * This is Used for Constant Variable
 * @author Vishal
 *
 */
class GroupSetupConstants {
	
    public GroupSetupConstants(){
    }
	
	//Groupsetup Roles
	public static final String ROLE_BROKER = "Broker"
	public static final String ROLE_EMPLOYER = "Employer"
	public static final String ROLE_GA = "General Agent"
	public static final String ROLE_TPA = "Third Party Administrator"
	
	//Product codes
	
	public static final String SITUSSTATECHANGE ='SitusStateChange'
	public static final String PROVISIONCHANGE ='ProvisionChange'
	public static final String USER_KEY ='User_Key'
	public static final String EMPLOYER_EXECUTIVE_CONTACT='40005'
	public static final String EMPLOYER_BA='40006'
	public static final int DENTALPPO_CODE = 202
	public static final int ACCIDENT_CODE = 502
	public static final int HOSPITAL_INDEMNITY_CODE = 504
	public static final int CRITICALILLNESS_CODE = 601
	public static final int VISION_CODE = 704
	
	public static final String EMPLOYER_PERSONA_TYPE_CODE = "30002"
	public static final String BROKER_PERSONA_TYPE_CODE = "30003"
	public static final String TPA_PERSONA_TYPE_CODE = "30004"
	public static final String GA_PERSONA_TYPE_CODE = "30005"
	
	public static final String EMPLOYER_EMAIL_TEMPLATE_ID = "17339922"
	public static final String BROKER_EMAIL_TEMPLATE_ID = "16882546"
	public static final String EMPLOYER_DIRECT_EMAIL_TEMPLATE_ID = "17340669"
	public static final String GA_TPA_EMAIL_TEMPLATE_ID = "17412015"
	public static final String BROKER_EMAIL_TEMPLATEID = "17340416"
	public static final String BROKER_GA_TPA_EMAIL_TEMPLATE_ID_ADDPRODUCT = "17278100"
	public static final String EMPLOYER_DIRECT_EMAIL_TEMPLATE_ID_ADDPRODUCT="17277116"
	public static final String EMPLOYER_EMAIL_TEMPLATE_ID_ADDPRODUCT="17277997"
	
	//State codes
	public static final String WEST_VIRGINIA_STATE_CODE = 'WV'
	public static final String FLORIDA_STATE_CODE = 'FL'

	//GroupSetUp Status codes
	public static final int STATUS_ACTIVE_CODE = 100
	public static final int STATUS_IN_ACTIVE_CODE = 101
	public static final int STATUS_APPLICATION_NOT_STARTED_CODE = 102
	public static final int STATUS_APPLICATION_IN_PROGRESS_CODE = 103

	//GroupSetUp Status Names
	public static final String STATUS_ACTIVE = 'Active'
	public static final String STATUS_IN_ACTIVE = 'Inactive'
	public static final String STATUS_APPLICATION_NOT_STARTED = 'Application Not Started'
	public static final String STATUS_APPLICATION_IN_PROGRESS = 'Application In Progress'
	public static final String AE_Prefix = 'spi.AEPrefix'
	//SPI Related Constant
	public static final def X_GSSP_TRACE_ID = 'x-gssp-trace-id'
	public static final String GSSP_ENTITY_SERVICE = "GSSPEntityService"
	public static final String GSSP_REPO_SERVICE = "GSSPRepository"
	public static final String REGISTERED_SERVICE_INVOKER = "registeredServiceInvoker"
	public static final String STATUS = "status"
	public static final String SPI_PREFIX = 'spi.prefix'
	public static final String X_GSSP_TENANTID = 'x-gssp-tenantid'
	public static final String SMDGSSP_TENANTID = 'smdgssp.tenantid'
	public static final String X_SPI_SERVICE_ID = 'x-spi-service-id'
	public static final String SMDGSSP_SERVICEID = 'smdgssp.serviceid'
	public static final String GSSP_HEADERS = 'gssp.headers'

	//Db Collection
	public static final String COLLECTION_GROUP_SETUP_DATA = "GroupSetupData"
	public static final String PER_COLLECTION_NAME = "GSAssignAccess"
	public static final String COLLECTION_GS_SOLD_PROPOSAL_CLIENTS = "GSSoldProposalClients"
	public static final String COLLECTION_GS_MASTER_APP_EFORMS = 'GSMasterAppFormList'
	public static final String COLLECTION_GS_STATIC_DOCUMENMTS = 'GSStaticDocuments'
	public static final String COLLECTION_GS_UPLOAD_DOCUMENMTS = 'GSUploadDocuments'
	public static final String COLLECTION_GS_PRODUCTCODE_NAME = 'GSProductCodeNames'
	public static final String COLLECTION_GS_KICKOUT_NOTIFICATION_GROUPS = "GSKickOutNotificationGroups"
	public static final String COLLECTION_GS_BANNER_DETAILS = "BannerDetails"
	public static final String COLLECTION_CUSTOMERS = "customers"
	public static final String COLLECTION_STRUCTURE_HISTORY_DATA = "GSStructureHistory"


	//	DMF related Headers
	public static final String SPIDOCUMENTSEARCHURL="spi.DMFSearchprefix"
	public static final String DMF_UPLOAD_SPI_URL="spi.DMFUploadprefix"
	public static final String SPIDOCUMENTGETURL="spi.DMFGetprefix"
	public static final String APMC_TENTENT_ID="apmc.tenantId"
	public static final String APMC_SERVICE_ID ="apmc.serviceId"
	public static final String APMC_CLIENT_ID ="apmc.clientId"
	public static final String SERVICE_NAME ="SEARCH_MDATA"
	public static final String EDPMPREFIX="spi.EDPMprefix"
	public static final String GET_METHOD ="GET"
	public static final String POST_METHOD ="POST"

	// Request or Path parameters
	public static final String TENANT_ID="tenantId"
	public static final String BROKER_ID="brokerId"
	public static final String LIMIT="limit"
	public static final String OFFSET='offset'
	public static final String FILTER_BY_STATUS='filterByStatus'
	public static final String REQUEST_PARAM_Q='q'
	public static final String ORDER="order"
	public static final String ORDER_BY="orderBy"
	public static final String SEARCH_DATA="searchData"
	public static final String FILTER_BY_RFPID = "filterByRFP"
	public static final String UNIQUE_ID = 'uniqueId'
	public static final String REQUEST_FROM="requestFrom"
	public static final String PERSONA = 'persona'
	public static final String PERSONA_ID = 'personaId'
	public static final String METREF_ID = 'metrefid'
	public static final String DELETE_BKC_CODE="deleteBKCCode"

	//Special Characters
	public static final String HYPHEN = '-'
	public static final String UNDERSCORE = '_'
	public static final String COMMA = ','
	public static final String SEMI_COLON = ';'
	public static final String DOUBLE_EQUAL = '=='

	public static final String LOCAL="local"
	public static final String ALL = "All"
	public static final String ORDER_DESC="desc"
	public static final String CLIENT_NAME = 'ClientName'
	public static final String PRIMARY_ADDRESS = 'PrimaryAddress'
	public static final String CAMEL_CASE_UNIQUE_ID = 'UniqueId'
	public static final String PROPOSALS = 'proposals'
	public static final String ELIGIBLELIVES="EligibleLives"
	public static final String CLIENT_STATUS = "ClientStatus"
	public static final String DATA = "data"
	public static final String MODULE ="module"
	public static final String OK = 'ok'
	public static final String GROUPSETUP_ID= 'groupSetUpId'
	public static final String GROUPSETUP_STATUS= 'groupSetUpStatus'
	public static final String USER_DIRECTORY = 'user.dir'
	public static final String UTF_8 = 'UTF-8'
	public static final String TEST_DATA_PATH = '/src/test/data/'

	//ERL and GBR
	public static final String SPI_ERLprefix = 'spi.ERLprefix'
	public static final String TOKENMANAGEMENTSERVICE = 'tokenManagementService'
	public static final String SPI_GBRprefix = 'spi.GBRprefix'
	public static final String ACTIVE = "Active"
	public static final String NOT_ACTIVE = "Not Active"
	public static final String EXPIRED = "Expired"
	public static final String NOT_FOUND = "Not Found"
	public static final String TRUE = "true"
	public static final String FALSE = "false"
	public static final String SMALL_BUSINESS_CENTRE="Small Buissiness Center"
	public static final String FOUR="4"
	public static final String FIVE="5"
	public static final String ZERO="0"
	public static final String SPONSORSHIP = 'sponsorship'
	public static final String LICENSE_NUMBER = 'licenseNumber'
	public static final String COMPENSABLE_CODE = 'compensableCode'
	public static final String VERIFY_STATUS_CODE = 'isVerifyStatusCode'
	public static final String PERSON_GIVEN1_NAME = 'personGiven1Name'
	public static final String PERSON_LAST_NAME = 'personLastName'
	public static final String POLICY_CARRIER_CODE_MLI = 'MLI'
	public static final String PAYMENT = "Payment"
	public static final String BUSINESS = "Business"
	public static final String DILT = "DILT"
	public static final String HI = "HI"
	public static final String NA = "NA"
	public static final String CRIL = "CRIL"
	public static final String VISN = "VISN"
	public static final String DIST = "DIST"
	public static final String AH = "AH"
	public static final String DENT = "DENT"
	public static final String TERM = "TERM"
	public static final String SUPPLEMENTAL_DEPENDENT_LIFE = "OPTLD"
	public static final String SUPPLEMENTAL_LIFE_OADD = "OPTL"
	public static final String BASIC_DEPENDENT_LIFE = "BSCLD"
	public static final String BASIC_LIFE_AD_D = "BSCL"
	public static final String LONG_TERM_DISABILITY = "LTD"
	public static final String HOSPITAL_INDEMNITY = "HI"
	public static final String MET_LAW = "LGL"
	public static final String CRITICAL_ILLNESS = "CIAA"
	public static final String VISION = "VIS"
	public static final String SHORT_TERM_DISABILITY = "STD"
	public static final String ACCIDENT = "ACC"
	public static final String DENTAL_PPO = "DPPO"
	public static final String DENTAL_DHMO = "DHMO"
	public static final String EDPM_USER = "edpm.userId"
	public static final String EDPM_PASS = "edpm.password"

	public static final String GSSP_CONFIGURATION = 'GSSPConfiguration'
	public static final String IIB_PREFIX='spi.iibPrefix'
	public static final String SOLD_CASE_STATUS_UPDATE = 'soldcasestatusupdate'
	public static final String RISK_ASSESSMENT = "riskAssessment"
	public static final String ASSIGN_ACCESS = "assignAccess"
	public static final String LICENSING_COMPENSABLE_CODE = "licensingCompensableCode"
	public static final String CLIENT_INFO = "clientInfo"
	public static final String CLASS_DEFINITION = "classDefinition"
	public static final String CONTRIBUTIONS = "contributions"
	public static final String COMISSION_ACKNOWLEDGEMENT = "comissionAcknowledgement"
	public static final String RENEWAL_NOTIFICATION_PERIOD = "renewalNotificationPeriod"
	public static final String GROUP_STRUCTURE = "groupStructure"
	public static final String NO_CLAIMS = "noClaims"
	public static final String FINALIZE_GROUP_SETUP="finalizeGroup"
	public static final String CHECK_FOR_WORKFLOW="checkForWorkflow"
	public static final String BILLING = "billing"
	public static final String AUTHORIZATION = "authorization"
	public static final String BASICINFO = "basicInfo"
	public static final String ASSIGN_CLASS = "assignClassLocation"
	public static final String PRAGNENT_EMP = "pregnantEmployeeDetails"
	public static final String HEALTH_RISK = "healthRisk"
	public static final String DISABLE_EMP = "disabledEmployees"
	public static final String RISK_ASSESSMENT_ACK="riskAssesmentAcknowledgement"
	public static final String MODULE_NAME = "moduleName"
	public static final String ACTION = "action"
	public static final String OFFLINE_SUBMIT = 'offlinesubmit'
	public static final String SOLD_CASE_ID = "soldCaseId"
	public static final String GROUP_NUMBER = "groupNumber"
	public static final String WH = 'WH'
	public static final String NO_OF_LIVES_3 = '3'
	public static final String NO_OF_LIVES_11 = '11'
	public static final String NO_OF_LIVES_2_50 = '2-50'
	public static final String NO_OF_LIVES_51 = '51'
	public static final String MAINTENANCEVIP= "accountService.maintenanceServiceVip"
	public static final String REQOUTEVIP= "accountService.requoteServiceVip"
	public static final String COLLECTION_PAGE_CONTENT_DATA = "GSPageContent"
	public static final String USER_ROLE_TYPE_CODE = "userRoleTypeCode"
	public static final String ORGNIZATION_USERS = "organizationUsers"
	public static final String PARTY_KEY = "partyKey"
	public static final String ID = "_id"
	public static final CUSTOMER_NAME_KEY = "customerName"
	public static final BROKER_NAME_KEY = "brokerName"
	public static final SCHEDULED = "scheduled"
	
	/**
	 * performance environment metrics  
	 */
	
	public static final String METHOD_NAME = "UI_MS_REQUEST_METHOD_NAME"
	public static final String API_NAME = "UI_MS_API_NAME"
	public static final String START_TIME = "UI_MS_REQUEST_START_TIME"
	public static final String END_TIME = "UI_MS_REQUEST_END_TIME"
	public static final String SUB_API_NAME = "SUB_API_NAME"
	public static final String SUB_API_START = "SUB_API_START_TIME"
	public static final String SUB_API_END = "SUB_API_END_TIME"
	public static final String DB_OP_START = "DB_OP_START_TIME"
	public static final String DB_OP_END = "DB_OP_END_TIME"
	public static final String PERF = "int"
	public static final String GROUP_SETUP_PERF_METRICS = "GROUP_SETUP_PERF_METRICS"
	public static final String SECURITY_CHECK = "securityCheck"
	public static final String SHAREDSERVICEVIP="accountService.sharedServiceVip"
	
	/**
	 * Add Coverage constants
	 */
	
	public static final String NEW_BUSINESS = "NEWBUSINESS"
	public static final String ADD_PRODUCT = "ADDPRODUCT"
}
