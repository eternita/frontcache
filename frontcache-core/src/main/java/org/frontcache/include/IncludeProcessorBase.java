package org.frontcache.include;

import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.frontcache.FCUtils;
import org.frontcache.WebComponent;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.reqlog.RequestLogger;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public abstract class IncludeProcessorBase implements IncludeProcessor {

	
	protected Logger logger = Logger.getLogger(getClass().getName());

	protected static final String START_MARKER = "<fc:include";
	protected static final String END_MARKER = "/>";
	

	protected CacheProcessor cacheProcessor;
	
	public IncludeProcessorBase() {
	}

	
	public void setCacheProcessor(CacheProcessor cacheProcessor)
	{
		this.cacheProcessor = cacheProcessor;
	}
	

	/**
	 * 
	 * @param content
	 * @return
	 */
	protected String getIncludeURL(String content)
	{
		logger.fine("include tag - " + content);
		final String START_MARKER = "url=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String urlValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.fine("include URL - " + urlValue);
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
	 * @param httpRequest
	 * @return
	 */
	protected String callInclude(String urlStr, HttpServletRequest httpRequest)
    {

		long start = System.currentTimeMillis();
		// check if cache is ON and response is cached
		if (null != cacheProcessor)
		{
			WebComponent cachedWebComponent = cacheProcessor.getFromCache(urlStr);
			if (null != cachedWebComponent)
			{
				
				RequestLogger.logRequest(urlStr, false, System.currentTimeMillis() - start, (null == cachedWebComponent.getContent()) ? -1 : cachedWebComponent.getContent().length());
				return cachedWebComponent.getContent();
			}
		}
		
		
		// do dynamic call 
		Map<String, Object> respMap = FCUtils.dynamicCall(urlStr, httpRequest);

		int httpResponseCode = (Integer) respMap.get("httpResponseCode");
		String contentType = (String) respMap.get("contentType");
        String dataStr = (String) respMap.get("dataStr");
        
        if (200 == httpResponseCode || 201 == httpResponseCode)
        {
        	// response is OK -> check if response is subject to cache
    		if (null != cacheProcessor)
    		{
    			WebComponent cachedWebComponent = FCUtils.parseWebComponent(urlStr, dataStr);
				// remove custom component tag from response string
				dataStr = cachedWebComponent.getContent(); 
    			if (cachedWebComponent.isCacheable())
    			{
    				cachedWebComponent.setContentType(contentType);
    				cacheProcessor.putToCache(urlStr, cachedWebComponent);
    			}
    		}
        }
//        TODO: fix it for remote includes
//    FOR THE SAME APP -> DO NOT LOG DYNAMIC REQUEST - IT WILL BE LOGGED in PageCacheFilter    
//		RequestLogger.logRequest(urlStr, true, System.currentTimeMillis() - start);
		
        return dataStr;
    }

	@Override
	public void init(Properties properties) {
	}

	@Override
	public void destroy() {
	}	
	
}
