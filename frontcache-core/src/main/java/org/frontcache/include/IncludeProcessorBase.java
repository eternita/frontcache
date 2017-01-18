package org.frontcache.include;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public abstract class IncludeProcessorBase implements IncludeProcessor {

	
	protected Logger logger = LoggerFactory.getLogger(getClass());
	
	protected static final String START_MARKER = "<fc:include";
	protected static final String END_MARKER = "/>";

	protected static final String INCLUDE_TYPE_SYNC = "sync"; // default
	protected static final String INCLUDE_TYPE_ASYNC = "async";
	
	
	private static final String[] NON_MERGEABLE_RESPONSE_HEADERS = new String[]{
			FCHeaders.X_FRONTCACHE_DEBUG_REQUEST
		};
	
	public IncludeProcessorBase() {
	}


	public boolean hasIncludes(WebResponse webResponse, int recursionLevel) 
	{
		byte[] content = webResponse.getContent();

		if (null == content)
			return false;
		
		if (recursionLevel >= MAX_RECURSION_LEVEL)
			return false;
		
		if (!webResponse.isText()) // includes for text only
			return false;

		String contentStr = new String(content);
		
		int startIdx = contentStr.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = contentStr.indexOf(END_MARKER, startIdx);
			if (-1 < endIdx && (endIdx - startIdx) < MAX_INCLUDE_LENGHT)
			{
				return true;
			}
		}
		
		return false;
		
	}
	
	protected void mergeIncludeResponseHeaders(Map<String, List<String>> outHeaders, Map<String, List<String>> includeResponseHeaders) 
	{
		for (String removeKey : NON_MERGEABLE_RESPONSE_HEADERS)
			includeResponseHeaders.remove(removeKey);

		synchronized (outHeaders) {
			for (String name : includeResponseHeaders.keySet()) {
				for (String value : includeResponseHeaders.get(name)) {
					
					List<String> outHeadersValues = outHeaders.get(name);
					if(null == outHeadersValues)
					{
						outHeadersValues = new ArrayList<String>();
						outHeaders.put(name, outHeadersValues);
					}
					if (!outHeadersValues.contains(value))
						outHeadersValues.add(value);
				}
			}
		}
		return;
	}
	

	/**
	 * 
	 * @param content
	 * @return
	 */
	protected String getIncludeURL(String content)
	{
		logger.debug("include tag - " + content);
		final String START_MARKER = "url=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String urlValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.debug("include URL - " + urlValue);
				return urlValue;
			} else {
				// can't find closing 
				return null;
			}
			
			
		} else {
			// no url attribute
			return null;
		}

	}

	/**
	 * 
	 * @param content
	 * @return
	 */
	protected String getIncludeType(String content)
	{
		logger.debug("include tag - " + content);
		final String START_MARKER = "call=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String typeValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				
				if (INCLUDE_TYPE_ASYNC.equalsIgnoreCase(typeValue))
				{
					logger.debug("include call-type - " + INCLUDE_TYPE_ASYNC);
					return INCLUDE_TYPE_ASYNC;
				}
					
			} else {
				// can't find closing 
			}
		} else {
			// no type attribute
		}
		
		logger.debug("include call-type - " + INCLUDE_TYPE_SYNC);
		return INCLUDE_TYPE_SYNC; // default
	}

	protected String getIncludeClientType(String content)
	{
		logger.debug("include tag - " + content);
		final String START_MARKER = "client=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String typeValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				
				if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equalsIgnoreCase(typeValue))
				{
					logger.debug("include client-type - " + FCHeaders.REQUEST_CLIENT_TYPE_BOT);
					return FCHeaders.REQUEST_CLIENT_TYPE_BOT;
				}
				if (FCHeaders.REQUEST_CLIENT_TYPE_BROWSER.equalsIgnoreCase(typeValue))
				{
					logger.debug("include client-type - " + FCHeaders.REQUEST_CLIENT_TYPE_BROWSER);
					return FCHeaders.REQUEST_CLIENT_TYPE_BROWSER;
				}
					
			} else {
				// can't find closing 
			}
		} else {
			// no type attribute
		}
		
		return null; // default
	}
	
	/**
	 * 
	 * @param urlStr
	 * @param requestHeaders
	 * @param client
	 * @return
	 */
	protected WebResponse callInclude(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context, String includeLevel, String includeType) throws FrontCacheException
    {

		// in case response is from cache -> log request
		// otherwise (response is not cached) it will be logged in FrontcacheEngine
		
		long start = System.currentTimeMillis();
		
		CacheProcessor cacheProcessor = CacheManager.getInstance();
		WebResponse webResponse = cacheProcessor.getFromCache(urlStr);
		
		boolean isCacheableForClientType = true;
		
		boolean softRefresh = false;

		if (null != webResponse)
		{
			String clientType = context.getClientType(); // bot | browser
			Map<String, Long> expireTimeMap = webResponse.getExpireTimeMap();
		
			isCacheableForClientType = FCUtils.isWebComponentCacheableForClientType(expireTimeMap, clientType);

			// if data is cacheable for client type -> check data for expiration (only)
			// if data is dynamic for client type -> no expiration / invalidation check
			if (isCacheableForClientType && FCUtils.isWebComponentExpired(expireTimeMap, clientType))
			{
				
				String refreshType = webResponse.getRefreshType();
				if (FCHeaders.COMPONENT_REFRESH_TYPE_SOFT.equalsIgnoreCase(refreshType))
				{
					// soft expiration
					cacheProcessor.doSoftInvalidation(urlStr, urlStr, requestHeaders, client, context);
					softRefresh = true;
				} else {
					// regular expiration
					cacheProcessor.removeFromCache(context.getDomainContext().getDomain(), urlStr);
					webResponse = null; // refresh from origin
				}
			}
		}
			
			
		if (null != webResponse && isCacheableForClientType)
		{
			{ // request logging
				RequestContext contextCopy = new RequestContext();
				contextCopy.putAll(context);
				contextCopy.setRequestType(FCHeaders.COMPONENT_INCLUDE);
				
				RequestLogger.logRequest(
						urlStr, 
						true, // isRequestCacheable 
						true, // isCached 
						System.currentTimeMillis() - start, 
						webResponse.getContentLenth(), // lengthBytes 
						contextCopy, includeLevel);
				
				RequestLogger.logRequestToHeader(
						urlStr, 
						includeType, // it's include (sync or asyn)   { toplevel | include | include-async }
						true, // isCached 
						softRefresh,
						System.currentTimeMillis() - start, 
						webResponse.getContentLenth(), // lengthBytes 
						contextCopy, includeLevel);
			}
			return webResponse;
		}
		
		// recursive call to FCServlet
 		requestHeaders.put(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL, Arrays.asList(new String[]{includeLevel})); // add include-level header
 		
 		WebResponse outWebResponse =  FCUtils.includeDynamicCallHttpClient(urlStr, requestHeaders, client, context);
 		
 		// TODO: sometimes async includes are not logged to headers because response returned faster then async IncludeResolutionPlaceholder.call() is triggered
 		// However async includes are always in log files
		RequestLogger.logRequestToHeader(
				urlStr, 
				includeType, // it's include (sync or asyn)   { toplevel | include | include-async }
				false, // isCached 
				softRefresh, 
				System.currentTimeMillis() - start, 
				outWebResponse.getContentLenth(), // lengthBytes 
				context, includeLevel);
		
 		return outWebResponse;
    }

	@Override
	public void init(Properties properties) {
	}

	@Override
	public void destroy() {
	}	
	
}

