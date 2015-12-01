package org.frontcache;

import java.util.Enumeration;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.frontcache.cache.CacheProcessor;

public class FCUtils {

	private FCUtils() {
	}
	
	private static Logger logger = Logger.getLogger(FCUtils.class.getName());
	
	
	/**
	 * 
	 * @param request
	 * @return
	 */
	public static String getRequestURL(HttpServletRequest request)
	{
        String requestURL = request.getRequestURL().toString();
        
        if ("GET".equals(request.getMethod()))
        {
        	// add parameters for storing 
        	// POST method parameters are not stored because they can be huge (e.g. file upload)
        	StringBuffer sb = new StringBuffer(requestURL);
        	Enumeration paramNames = request.getParameterNames();
        	if (paramNames.hasMoreElements())
        		sb.append("?");

        	while (paramNames.hasMoreElements()){
        		String name = (String) paramNames.nextElement();
        		sb.append(name).append("=").append(request.getParameter(name));
        		
        		if (paramNames.hasMoreElements())
        			sb.append("&");
        	}
        	requestURL = sb.toString();
        }	
        return requestURL;
	}
	

	/**
	 * wrap String to WebComponent.
	 * Check for header - extract caching options.
	 * 
	 * @param content
	 * @return
	 */
	public static final WebComponent parseWebComponent (String content)
	{
		int cacheMaxAgeSec = CacheProcessor.NO_CACHE;
		
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

		WebComponent component = new WebComponent(outStr, cacheMaxAgeSec);
		
		return component;
	}
	
	/**
	 * 
	 * @param content
	 * @return time to live in cache in seconds
	 */
	private static int getCacheMaxAge(String content)
	{
		final String START_MARKER = "maxage=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String maxAgeStr = content.substring(startIdx + START_MARKER.length(), endIdx);
				try
				{
					int multiplyPrefix = 1;
					if (maxAgeStr.endsWith("d")) // days
					{
						maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
						multiplyPrefix = 86400; // 24 * 60 * 60
					} else if (maxAgeStr.endsWith("h")) { // hours
						maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
						multiplyPrefix = 3600; // 60 * 60
					} else if (maxAgeStr.endsWith("m")) { // minutes
						maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
						multiplyPrefix = 60;
					} else if (maxAgeStr.endsWith("s")) { // seconds
						maxAgeStr = maxAgeStr.substring(0, maxAgeStr.length() - 1);
						multiplyPrefix = 1;
					} else {
						// seconds
					}
					
					return multiplyPrefix * Integer.parseInt(maxAgeStr); // time to live in cache in seconds
				} catch (Exception e) {
					logger.info("can't parse component maxage - " + maxAgeStr + " defalut is used (NO_CACHE)");
					return CacheProcessor.NO_CACHE;
				}
				
			} else {
				logger.info("no closing tag for - " + content);
				// can't find closing 
				return CacheProcessor.NO_CACHE;
			}
			
			
		} else {
			// no maxage attribute
			logger.info("no maxage attribute for - " + content);
			return CacheProcessor.NO_CACHE;
		}

	}	
	

}
