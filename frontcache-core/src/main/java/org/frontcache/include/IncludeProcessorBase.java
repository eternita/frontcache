package org.frontcache.include;

import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.WebComponent;
import org.frontcache.cache.CacheProcessor;

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
	protected String callInclude(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client)
    {
		
		WebComponent cachedWebComponent;
		try {
			cachedWebComponent = cacheProcessor.processRequest(urlStr, requestHeaders, client);
			return cachedWebComponent.getContent();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return "";
    }

	@Override
	public void init(Properties properties) {
	}

	@Override
	public void destroy() {
	}	
	
}
