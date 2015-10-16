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

import org.frontcache.FCUtils;
import org.frontcache.WebComponent;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = Logger.getLogger(getClass().getName());

//	private static final String CONTENT_TYPE_KEY = "CONTENT_TYPE_KEY";
	
	public String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException 
	{
	
		String urlStr = getRequestURL(httpRequest);
		
		WebComponent cachedWebComponent = getFromCache(urlStr);
		
		String content = null;
//		Map<String, String> headers = null;

		
		if (null == cachedWebComponent)
		{
			logger.info(urlStr + " - dynamic call");
			
			chain.doFilter(httpRequest, response); // run request to origin
						
			content = response.getContentString();
			
			cachedWebComponent = FCUtils.parseWebComponent(content);
			
			// save to cache
			if (cachedWebComponent.isCacheable())
			{
				// cache headers as well
//				headers = new HashMap<String, String>();
//				for (String headerKey : response.getHeaderNames())
//					headers.put(headerKey, response.getHeader(headerKey));

//				headers.put(CONTENT_TYPE_KEY, response.getContentType());

				cachedWebComponent.setContentType(response.getContentType());
				
				putToCache(urlStr, cachedWebComponent);
			}
			
		} else {
			
			logger.info(urlStr + " - cache hit");
			content = cachedWebComponent.getContent();
//			headers = cachedWebComponent.getHeaders();
			response.setContentType(cachedWebComponent.getContentType());
			
		}

		
		return content;
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
