package org.frontcache.include;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.impl.f.BotIncludeProcessorFilter;
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
	
	private IncludeProcessorFilter incProcessorFilter = new BotIncludeProcessorFilter();
	
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
	 * @param urlStr
	 * @param requestHeaders
	 * @param client
	 * @return
	 */
	protected WebResponse callInclude(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException
    {

		// in case response is from cache -> log request
		// otherwise (response is not cached) it will be logged in FrontcacheEngine
		
		long start = System.currentTimeMillis();
		
		CacheProcessor cacheProcessor = CacheManager.getInstance();
		WebResponse webResponse = cacheProcessor.getFromCache(urlStr); 
		if (null != webResponse)
		{

			{ // request logging
				RequestContext contextCopy = new RequestContext();
				contextCopy.putAll(context);
				contextCopy.setRequestType(FCHeaders.X_FRONTCACHE_COMPONENT_INCLUDE);
				
				RequestLogger.logRequest(
						urlStr, 
						true, // isRequestCacheable 
						true, // isCached 
						System.currentTimeMillis() - start, 
						webResponse.getContentLenth(), // lengthBytes 
						contextCopy);
			}
			return webResponse;
		}
		
		// recursive call to FCServlet (through bot filter / to cache sessionless requests)
		return incProcessorFilter.callInclude(urlStr, requestHeaders, client, context);
    }

	@Override
	public void init(Properties properties) {
	}

	@Override
	public void destroy() {
	}	
	
}

