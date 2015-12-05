package org.frontcache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.frontcache.cache.CacheProcessor;

public class FCUtils {

	private FCUtils() {
	}
	
	private static Logger logger = Logger.getLogger(FCUtils.class.getName());
	
	private static final String SET_COOKIE_SEPARATOR = "; ";
	private static final String COOKIE = "Cookie";
	
	/**
	 * read cookies from original request and convert to string to be passed to further call (include)
	 * 
	 * @param httpRequest
	 * @return
	 */
	private static String getCookies(HttpServletRequest httpRequest)
	{
        Cookie[] cookies = httpRequest.getCookies();
        if (null != cookies)
        {
    		StringBuffer cookieStringBuffer = new StringBuffer();

        	for (int i = 0; i < cookies.length; i++)
        	{
        		Cookie c = cookies[i];
        		if (null == c)
        			continue;
        		
				cookieStringBuffer.append(c.getName());
				cookieStringBuffer.append("=");
				cookieStringBuffer.append(c.getValue());
				if (i + 1 < cookies.length) // has next
					cookieStringBuffer.append(SET_COOKIE_SEPARATOR);
        	}
            return cookieStringBuffer.toString();
        }		
        
        return null;
	}
	
	/**
	 * 
	 * @param urlStr
	 * @param httpRequest
	 * @return
	 */
	public static Map<String, Object> dynamicCall(String urlStr, HttpServletRequest httpRequest)
    {

		Map<String, Object> respMap = new HashMap<String, Object>();
		// do dynamic call 
		int httpResponseCode = -1;
		String contentType = "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        InputStream is = null;
        try {
            URL u = new URL(urlStr);
            HttpURLConnection uc = (HttpURLConnection) u.openConnection();

            // translate cookies
            String cookies = getCookies(httpRequest);
            if (null != cookies)
    			uc.setRequestProperty(COOKIE, cookies);
            
            
            is = uc.getInputStream();
            int bytesRead = 0;
            int bufferSize = 4000;
             byte[] byteBuffer = new byte[bufferSize];              
             while ((bytesRead = is.read(byteBuffer)) != -1) {
                 baos.write(byteBuffer, 0, bytesRead);
             }
             
             httpResponseCode = uc.getResponseCode();
             contentType = uc.getContentType();
        } catch (Exception e) {
        	e.printStackTrace();
            // TODO Auto-generated catch block
        } finally {
            if (null != is)
            {
                try {
                    is.close();
                } catch (IOException e) {
                	e.printStackTrace();
                    // TODO Auto-generated catch block
                }
            }
        }
		
        String dataStr = new String(baos.toByteArray());

        respMap.put("httpResponseCode", httpResponseCode);
        respMap.put("contentType", contentType);
        respMap.put("dataStr", dataStr);
        return respMap;
    }
	
	
	
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
	 * http://www.coinshome.net/en/welcome.htm -> /en/welcome.htm
	 * 
	 * @param request
	 * @return
	 */
	public static String getRequestURLWithoutHostPort(HttpServletRequest request)
	{
        String requestURL = getRequestURL(request);
        int sIdx = 10; // requestURL.indexOf("://"); // http://www.coinshome.net/en/welcome.htm
        int idx = requestURL.indexOf("/", sIdx);

        return requestURL.substring(idx);
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
