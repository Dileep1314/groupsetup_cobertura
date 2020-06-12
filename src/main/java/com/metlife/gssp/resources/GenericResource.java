package com.metlife.gssp.resources;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;

import com.metlife.gssp.common.constant.LoggingContextKey;
import com.metlife.gssp.exception.GSSPException;
import com.metlife.gssp.exception.ValidationException;
import com.metlife.service.BaseRestController;
import com.metlife.service.ConfigurationService;
import com.metlife.service.orchestration.ServiceDelegator;
import com.metlife.utility.ServiceRequestBuilder;


/**
 * Generic resource for accepting service requests. Responds to GET, PUT, POST
 * and DELETE verbs.
 *
 */
@RestController
@SuppressWarnings("unchecked")
public class GenericResource implements BaseRestController {

	private static final String REQUEST_MAPPING = "/{version:v[0-9]+}/{contract:[a-zA-Z1-100-_]+}/{tenantId}/**";
	private static final String GENERAL_ERROR_CODE = "9999";
	private static final String ERROR_CODE_KEY = "errorCode";
	private static final String TENANT_ID_CONTEXT_PATH_PATTERN = ".*/tenants/(?<tenantId>[A-Za-z0-9_]+)/.*";
	private static final String TENANT_ID = "tenantId";
	private static final String X_GSSP_MICROSERVICE_TRX_ID = "X-GSSPMicroservice-TrxId";

	private final Logger logger = LoggerFactory.getLogger(GenericResource.class);

	@Value("${spring.profiles.active}") // Pass this in via -D
	private String profile;

	@Autowired
	private ServiceDelegator serviceDelegator;

	
	@Autowired
	private ConfigurationService configurationService;

	@Autowired
	private ApplicationContext context;

	@RequestMapping(value = REQUEST_MAPPING, method = RequestMethod.GET)
	public ResponseEntity<Object> getMethod(@PathVariable Map<String, Object> pathParamMap,
			@RequestParam Map<String, Object> reqParamMap, @RequestHeader Map<String, Object> headerMap,
			HttpServletRequest request) throws Throwable {
		MDC.clear();
		Instant startTime = Instant.now();
		MDC.put("UI_MS_REQUEST_START_TIME", getDateAndTimeStamp());
		MDC.put("UI_MS_REQUEST_METHOD_NAME", RequestMethod.GET.toString());
		MDC.put("UI_MS_START_TIME", startTime.toString());
		Map<String, Object> segmentedRequest = new ServiceRequestBuilder().buildRequestMap().addHeader(headerMap)
				.addPathParams(pathParamMap).addRequestParams(reqParamMap).get();

		String url = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		// to do lookaheads for query in pattern match
		// url += StringUtils.isNotBlank(request.getQueryString()) ?
		// ("?"+request.getQueryString()) : "";
		MDC.put("UI_MS_API_NAME", url);
		setTransactionId(pathParamMap,headerMap);
		return (ResponseEntity<Object>) serviceDelegator
				.serveRequestChain(segmentedRequest, url, RequestMethodType.GET);
	}

	@RequestMapping(value = REQUEST_MAPPING, method = RequestMethod.DELETE)
	public ResponseEntity<Object> deleteMethod(@PathVariable Map<String, Object> pathParamMap,
			@RequestParam Map<String, Object> reqParamMap, @RequestHeader Map<String, Object> headerMap,
			HttpServletRequest request) throws Throwable {
		
		Map<String, Object> segmentedRequest = new ServiceRequestBuilder().buildRequestMap().addHeader(headerMap)
				.addPathParams(pathParamMap).addRequestParams(reqParamMap).get();

		String url = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		
		setTransactionId(pathParamMap,headerMap);
		return (ResponseEntity<Object>) serviceDelegator
				.serveRequestChain(segmentedRequest, url, RequestMethodType.DELETE);
	}

	@RequestMapping(value = REQUEST_MAPPING, method = RequestMethod.PUT)
	public ResponseEntity<Object> putMethod(@PathVariable Map<String, Object> pathParamMap,
			@RequestParam Map<String, Object> reqParamMap, @RequestHeader Map<String, Object> headerMap,
			@RequestBody Map<String, Object> requestBodyMap, HttpServletRequest request) throws Throwable {
		
		Map<String, Object> segmentedRequest = new ServiceRequestBuilder().buildRequestMap().addHeader(headerMap)
				.addPathParams(pathParamMap).addRequestParams(reqParamMap).addRequestBody(requestBodyMap).get();

		String url = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		setTransactionId(pathParamMap,headerMap);
		return (ResponseEntity<Object>)serviceDelegator
				.serveRequestChain(segmentedRequest, url, RequestMethodType.PUT);
	}

	@RequestMapping(value = REQUEST_MAPPING, method = RequestMethod.POST)
	public ResponseEntity<Object> postMethod(@PathVariable Map<String, Object> pathParamMap,
			@RequestParam Map<String, Object> reqParamMap, @RequestHeader Map<String, Object> headerMap,
			@RequestBody Map<String, Object> requestBodyMap, HttpServletRequest request) throws Throwable {
		MDC.clear();
		Instant startTime = Instant.now();
		MDC.put("UI_MS_REQUEST_START_TIME", getDateAndTimeStamp());
		MDC.put("UI_MS_REQUEST_METHOD_NAME", RequestMethod.POST.toString());
		MDC.put("UI_MS_START_TIME", startTime.toString());
		Map<String, Object> segmentedRequest = new ServiceRequestBuilder().buildRequestMap().addHeader(headerMap)
				.addPathParams(pathParamMap).addRequestParams(reqParamMap).addRequestBody(requestBodyMap).get();

		String url = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
		setTransactionId(pathParamMap,headerMap);
		MDC.put("UI_MS_API_NAME", url);
		return (ResponseEntity<Object>) serviceDelegator
				.serveRequestChain(segmentedRequest, url, RequestMethodType.POST);
	}

	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<Object> rulesForValidation(HttpServletRequest req, Exception e) {
		return new ResponseEntity<Object>(((ValidationException) e).getErrors(), HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(GSSPException.class)
	public ResponseEntity<Object> rulesForGenericException(Exception e, WebRequest webRequest) {
		return configurationService.createResponseForException(((GSSPException) e).getErrorMap());
	}

	@ExceptionHandler(RestClientException.class)
	public ResponseEntity<Object> handleRestClientException(Exception e, HttpServletRequest hsr) {
		return configurationService.createResponseForException((RestClientException) e, hsr);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> globalHandler(HttpServletRequest req, Exception e) {
		logger.error("Unexpected error", e);
		Pattern path = Pattern.compile(TENANT_ID_CONTEXT_PATH_PATTERN);
		Matcher m = path.matcher(req.getRequestURI());
		String tenantId = m.find() ? m.group(TENANT_ID) : null;
		Map<String, String> defaultErrorMap = new HashMap<String, String>();
		defaultErrorMap.put(ERROR_CODE_KEY, GENERAL_ERROR_CODE);
		defaultErrorMap.put(TENANT_ID, tenantId);
		return configurationService.createResponseForException(defaultErrorMap);
	}

	@Override
	@RequestMapping(value=REQUEST_MAPPING, method = RequestMethod.PATCH)
	public ResponseEntity<Object> patchMethod(@PathVariable Map<String, Object> pathParamMap,
			@RequestParam Map<String, Object> reqParamMap, @RequestHeader Map<String, Object> headerMap,
			@RequestBody Map<String, Object> requestBodyMap, HttpServletRequest request) throws Throwable {
		Map<String, Object> segmentedRequest = new ServiceRequestBuilder().buildRequestMap()
				.addHeader(headerMap)
				.addPathParams(pathParamMap)
				.addRequestParams(reqParamMap)
				.addRequestBody(requestBodyMap).get();

			String url = (String) request.getAttribute(
			        HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
			setTransactionId(pathParamMap,headerMap);
			return (ResponseEntity<Object>)serviceDelegator.serveRequestChain(segmentedRequest, url,RequestMethodType.PATCH);
	}
	
	/**
     * Setting transaction Id to request header and log context -  if available set as is else generated and set
     * 
     * If transaction id is not passed by consumer, leveraging the SMD generated ID to set as transaction ID for traceability. 
     * 
     * @param pathParamMap Map of parameters pass in URL path.
     * @param headerMap Map of parameters pass in URL path
     */
    private void setTransactionId(Map<String, Object> pathParamMap, Map<String, Object> headerMap) {
    	final String xGsspTrxIdKey = context.getEnvironment().getProperty("header.transactionId-key");
		final String xSmUserKey = context.getEnvironment().getProperty("header.smuser");
		final String xCustomerIdKey = context.getEnvironment().getProperty("header.customerId");
		final String xChannelIdKey = context.getEnvironment().getProperty("header.channelId");

		String transactionId = (String) headerMap.get(xGsspTrxIdKey);
		String smUserId = (String) headerMap.get(xSmUserKey);
		String customerId = (String) headerMap.get(xCustomerIdKey);
		String channelId = (String) headerMap.get(xChannelIdKey);
		logger.info("Transaction id from header: {}: {} ", xGsspTrxIdKey, transactionId);
		if (StringUtils.isEmpty(transactionId)) {
			transactionId = (String) headerMap.get(X_GSSP_MICROSERVICE_TRX_ID);
			headerMap.put(xGsspTrxIdKey, transactionId);
		}
		MDC.put(LoggingContextKey.GSSP_TRANSACTION_ID, transactionId);
		MDC.put(LoggingContextKey.SMUSER, smUserId);
		MDC.put(LoggingContextKey.CUSTOMER_ID, customerId);
		MDC.put(LoggingContextKey.GSSP_CHANNEL, channelId);
        logger.info("Transaction id will be used for traceability: {}: {}", xGsspTrxIdKey, transactionId);
    }
    /**
	 * to collect API level metrics to scale-up performance
	 * @return
	 */
	public static String getDateAndTimeStamp() {
		Date now = new Date();
		SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		return simpleDateFormat.format(now).toString();
	}


 
}