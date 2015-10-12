package org.frontcache.cache;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = Logger.getLogger(getClass().getName());

	private static final String CONTENT_TYPE_KEY = "CONTENT_TYPE_KEY";

	abstract protected void putToCache(String url, WebComponent component);
	abstract protected WebComponent getFromCache(String url);
	
	public String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException 
	{
	
		String urlStr = getRequestURL(httpRequest);
		
		WebComponent cachedRequest = getFromCache(urlStr);
		
		String content = null;
		Map<String, String> headers = null;

		
		if (null == cachedRequest)
		{
			logger.info(urlStr + " - dynamic call");
			
			chain.doFilter(httpRequest, response); // run request to origin
						
			content = response.getContentString();
			
			cachedRequest = str2component(content);
			
			// save to cache
			if (cachedRequest.isCacheable())
			{
				// cache headers as well
				headers = new HashMap<String, String>();
				for (String headerKey : response.getHeaderNames())
					headers.put(headerKey, response.getHeader(headerKey));

				headers.put(CONTENT_TYPE_KEY, response.getContentType());

				cachedRequest.setHeaders(headers);
				
				putToCache(urlStr, cachedRequest);
			}
			
		} else {
			
			logger.info(urlStr + " - cache hit");
			content = cachedRequest.getContent();
			headers = cachedRequest.getHeaders();
			response.setContentType(headers.get(CONTENT_TYPE_KEY));
			
		}

		
		return content;
	}
	
	/**
	 * wrap String to WebComponent.
	 * Check for header - extract caching options.
	 * 
	 * @param content
	 * @return
	 */
	private WebComponent str2component (String content)
	{
		WebComponent component = new WebComponent();
		
		int cacheMaxAgeSec = DEFAULT_CACHE_MAX_AGE;
		
		String outStr = null;
		final String START_MARKER = "<fc:component";
		final String END_MARKER = "/>";
		
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf(END_MARKER, startIdx);
			if (-1 < endIdx)
			{
				String includeTagStr = content.substring(startIdx, endIdx + END_MARKER.length());
				cacheMaxAgeSec = getCacheMaxAge(includeTagStr);
				
				
				// exclude tag from content
				outStr = content.substring(0, startIdx)   +   
						 content.substring(endIdx + END_MARKER.length(), content.length());
				
			} else {
				// can't find closing 
				outStr = content;
			}
			
			
		} else {
			outStr = content;
		}

		component.setContent(outStr);
		component.setCacheMaxAge(cacheMaxAgeSec);
		
		return component;
	}
	
	/**
	 * 
	 * @param content
	 * @return
	 */
	private int getCacheMaxAge(String content)
	{
		logger.info("component tag - " + content);
		final String START_MARKER = "cache-max-age=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String maxAgeStr = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.info("component cache-max-age - " + maxAgeStr);
				try
				{
					return Integer.parseInt(maxAgeStr);
				} catch (Exception e) {
					logger.warning("can't parse component cache-max-age - " + maxAgeStr + " defalut is used (" + DEFAULT_CACHE_MAX_AGE + ")");
					return DEFAULT_CACHE_MAX_AGE;
				}
				
			} else {
				// can't find closing 
				return DEFAULT_CACHE_MAX_AGE;
			}
			
			
		} else {
			// no cache-max-age attribute
			return DEFAULT_CACHE_MAX_AGE;
		}

	}	
	
	
	/**
	 * 
	 * @param request
	 * @return
	 */
	private String getRequestURL(HttpServletRequest request)
	{
        String requestURL = request.getRequestURL().toString();
        
        if ("GET".equals(request.getMethod()))
        {
        	// add parameters for storing 
        	// POST method parameters are not stored because they can be huge (e.g. file upload)
        	StringBuffer sb = new StringBuffer(requestURL);
        	Enumeration paramNames = request.getParameterNames();
        	if (paramNames.hasMoreElements())
        	{
        		sb.append("?");
        	}
        	while (paramNames.hasMoreElements()){
        		String name = (String) paramNames.nextElement();
        		sb.append(name).append("=").append(request.getParameter(name)).append("&");            		
        	}
        	requestURL = sb.toString();
        }	
        return requestURL;
	}
	

	@Override
	public void init(Properties properties) {
		
	}	

}
