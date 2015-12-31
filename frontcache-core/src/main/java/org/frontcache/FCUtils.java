package org.frontcache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.frontcache.cache.CacheProcessor;

public class FCUtils {

	private FCUtils() {
	}
	
	private static Logger logger = Logger.getLogger(FCUtils.class.getName());
	
	/**
	 * 
	 * @param urlStr
	 * @param httpRequest
	 * @return
	 */
	public static Map<String, Object> dynamicCall(String urlStr, HttpServletRequest httpRequest)
    {
		return dynamicCall(urlStr, httpRequest, null);
    }
	
	
	/**
	 * 
	 * @param urlStr
	 * @param httpRequest
	 * @param resp
	 * @return
	 */
	public static Map<String, Object> dynamicCall(String urlStr, HttpServletRequest httpRequest, HttpServletResponse resp)
    {
		System.out.println("call origin " + urlStr);
		Map<String, Object> respMap = new HashMap<String, Object>();
		// do dynamic call 
		int httpResponseCode = -1;
		
		
		HttpClient client = HttpClientBuilder.create().build();
		HttpGet request = new HttpGet(urlStr);
		
		// translate headers
		Enumeration<String> headerNames = httpRequest.getHeaderNames(); 
		while(headerNames.hasMoreElements())
		{
			String hName = headerNames.nextElement();
			request.addHeader(hName, httpRequest.getHeader(hName));
		}

		HttpResponse response;
		String contentType = null;
		try {
			response = client.execute(request);
			httpResponseCode = response.getStatusLine().getStatusCode();
			System.out.println("Response Code : " + httpResponseCode);
			
			if (null != resp)
				resp.setStatus(httpResponseCode);
				
			if (httpResponseCode < 200 || httpResponseCode > 399)
			{
				// error
		        respMap.put("httpResponseCode", httpResponseCode);
				return respMap;
			}
			
			for (Header respHeader : response.getAllHeaders())
			{
				if (null != resp)
					resp.addHeader(respHeader.getName(), respHeader.getValue());
				
				if ("Content-Type".equals(respHeader.getName()))
					contentType = respHeader.getValue();
			}
			
			if (null != contentType && -1 < contentType.indexOf("text")) {
				// response is a text - extract String for caching + do in-out
				// copy
				OutputStream respOut = null;
				if (null != resp)
					respOut = resp.getOutputStream();
				
				BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

				StringBuffer result = new StringBuffer();
				String line = "";
				while ((line = rd.readLine()) != null) {
					result.append(line);
					if (null != resp)
						respOut.write(line.getBytes());
				}
				
				String dataStr = result.toString();
		        respMap.put("dataStr", dataStr);
	        	respMap.put("Content-Length", "" + dataStr.length());
			} else {
				// not a text - do in-out copy
				if (null != resp)
				{
					if (httpResponseCode >= 200 && httpResponseCode < 299) // do not copy if 304 (cached)
					{
						InputStream is = response.getEntity().getContent();
						IOUtils.copy(is, resp.getOutputStream());
					}
					
			        String contentLenghtHeader = resp.getHeader("Content-Length");
			        if (null != contentLenghtHeader)
			        	respMap.put("Content-Length", contentLenghtHeader);
				}
			}
			
		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        respMap.put("httpResponseCode", httpResponseCode);
        respMap.put("contentType", contentType);
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
