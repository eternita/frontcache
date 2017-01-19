package org.frontcache.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.message.BasicHeader;
import org.frontcache.FrontCacheEngine;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.hystrix.FC_ThroughCache_HttpClient;
import org.frontcache.hystrix.FC_ThroughCache_WebFilter;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FCUtils {

	private FCUtils() {
	}
	
	private static Logger logger = LoggerFactory.getLogger(FCUtils.class);
		
    private static final String[] CLIENT_IP_SOURCE_HEADER_LIST = { 
            "x-forwarded-for",
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR" 
    };
        

	// true - save to cache
	public static boolean isWebComponentCacheableForClientType(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMap must not be empty for clientType=" + clientType);
			return false; 
		}
		
		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return false; 			
		}
		
		if (CacheProcessor.NO_CACHE == expireTimeMillis)
			return false;
		
		return true;
	}

	/**
	 * Check with current time if expired
	 *  
	 * @param clientType {bot | browser}
	 * @return
	 */
	public static boolean isWebComponentExpired(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMap must not be empty for clientType=" + clientType);
			return true; 
		}
		
		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return true; 
		}
		
		if (CacheProcessor.CACHE_FOREVER == expireTimeMillis)
			return false;
		
		if (System.currentTimeMillis() > expireTimeMillis)
			return true;
		
		return false;
	}

	
	
    
	/**
	 * e.g. localhost:8080
	 * 
	 * @param httpRequest
	 * @return
	 */
    public static String getHost(HttpServletRequest httpRequest)
    {
    	StringBuffer sb = new StringBuffer();
    	sb.append(httpRequest.getServerName());
    	return sb.toString();
    }

    public static String getProtocol(HttpServletRequest httpRequest)
    {
    	if (httpRequest.isSecure())
    		return "https";
    	else
    		return "http";
    }
	
	/**
	 * GET method only for text requests
	 * 
	 * for cache processor - it can use both (httpClient or filter)
	 * 
	 * @param urlStr
	 * @param httpRequest
	 * @param httpResponse
	 * @return
	 */
	public static WebResponse dynamicCall(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException
    {
		// add include-level header - to trace include tree 
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL, "" + context.getIncludeLevel());
		
		// add request-id header - to distinguish if sent by frontcache or not (for include processing) 
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());
		
		if (context.isFilterMode())
			 return new FC_ThroughCache_WebFilter(context).execute();
		else
			 return new FC_ThroughCache_HttpClient(urlStr, requestHeaders, client, context).execute();
    }

	/**
	 * for includes ONLY - they allways use httpClient
	 */
	public static WebResponse includeDynamicCallHttpClient(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException
    {
		// add request-id header - to trace include tree 
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());
		
		// add header with client IP
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_CLIENT_IP, getClientIP(context.getRequest()));
		
		return new FC_ThroughCache_HttpClient(urlStr, requestHeaders, client, context).execute();
    }
	
	private static String getClientIP(HttpServletRequest request) {
		for (String header : CLIENT_IP_SOURCE_HEADER_LIST) {
			String ip = request.getHeader(header);
			if (ip != null && ip.length() != 0 && !"unknown".equalsIgnoreCase(ip)) {
				return ip;
			}
		}
		return request.getRemoteAddr();
	}
	
	/**
	 * Helper to work with Map<String, List<String>> 
	 * 
	 * @param requestHeaders
	 * @param key
	 * @param name
	 */
	private static void addHeader(Map<String, List<String>> requestHeaders, String key, String name)
	{
		requestHeaders.put(key, Arrays.asList(new String[]{name}));
		return;
	}
	

	/**
	 * is used in ServletFilter mode
	 * 
	 * @param url
	 * @param originWrappedResponse
	 * @return
	 * @throws FrontCacheException
	 * @throws IOException
	 */
	public static WebResponse httpResponse2WebComponent(String url, FrontCacheHttpResponseWrapper originWrappedResponse) throws FrontCacheException, IOException
	{
		WebResponse webResponse = null;
		
		String contentType = originWrappedResponse.getContentType();
		String dataStr = originWrappedResponse.getContentString();
		byte[] data = null;
		if (null != dataStr)
			data = dataStr.getBytes();
		else
			logger.info(url + " has response with no data");

		
		if (null == contentType || -1 == contentType.indexOf("text"))
		{
			contentType = "text"; // 
		}
		
		webResponse = parseWebComponent(url, data, FCUtils.revertHeaders(originWrappedResponse));

		webResponse.setStatusCode(originWrappedResponse.getStatus());

		// get headers
    	Map<String, List<String>> headers = new HashMap<String, List<String>>();
		for (String headerName : originWrappedResponse.getHeaderNames())
		if (isIncludedHeaderToResponse(headerName))
		{
			List<String> hValues = headers.get(headerName);
			if(null == hValues)
			{
				hValues = new ArrayList<String>();
				headers.put(headerName, hValues);
			}
			hValues.add(originWrappedResponse.getHeader(headerName));
		}
		webResponse.setHeaders(headers);
		
		// filter may not set up content type yet -> check and setup 
		if (null != dataStr && null == headers.get(FCHeaders.CONTENT_TYPE))
		{
			webResponse.addHeader(FCHeaders.CONTENT_TYPE, contentType); 
		}
		
		return webResponse;
	}
	
	public static WebResponse httpResponse2WebComponent(String url, HttpResponse response, RequestContext context) throws FrontCacheException, IOException
	{
		
		
		int httpResponseCode = response.getStatusLine().getStatusCode();
			
		if (httpResponseCode < 200 || httpResponseCode > 299)
		{
			// error
//			throw new RuntimeException("Wrong response code " + httpResponseCode);
		}
		
		String contentType = "";
		Header contentTypeHeader = response.getFirstHeader(FCHeaders.CONTENT_TYPE);
		if (null != contentTypeHeader)
			contentType = contentTypeHeader.getValue();
		
		WebResponse webResponse = null;
		
		byte[] respData = null;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		InputStream is = response.getEntity().getContent();
		try {
			int bytesRead = 0;
            int bufferSize = 4000;
	         byte[] byteBuffer = new byte[bufferSize];				
	         while ((bytesRead = is.read(byteBuffer)) != -1) {
	             baos.write(byteBuffer, 0, bytesRead);
	         }
		} catch (Exception e) {
			e.printStackTrace();
		}
		respData = baos.toByteArray();
		
		Map<String, List<String>> headers = revertHeaders(response.getAllHeaders());
		webResponse = parseWebComponent(url, respData, headers);

		webResponse.setHeaders(headers);
		webResponse.setStatusCode(httpResponseCode);
		if (null == headers.get(FCHeaders.CONTENT_TYPE))
			webResponse.addHeader(FCHeaders.CONTENT_TYPE, contentType); 
		
		// process redirects
		Header locationHeader = response.getFirstHeader("Location");
		if (null != locationHeader)
		{
			String originLocation = locationHeader.getValue();
			
			String fcLocation = transformRedirectURL(originLocation, context);
			webResponse.getHeaders().remove("Location");
			webResponse.addHeader("Location", fcLocation);
		}
		
		return webResponse;
	}
	
	public static String transformRedirectURL(String originLocation, RequestContext context)
	{
		String fcLocation = null;
		String protocol = getRequestProtocol(originLocation);
		
		if ("".equals(protocol)) // originLocation is relative path, so protocol is undefined
			protocol = context.getFrontCacheProtocol(); // use current protocol from context
		
		if ("https".equalsIgnoreCase(protocol))
			fcLocation = "https://" + context.getFrontCacheHost() + ":" + context.getFrontCacheHttpsPort() + buildRequestURI(originLocation);
		else // http
			fcLocation = "http://" + context.getFrontCacheHost() + ":" + context.getFrontCacheHttpPort() + buildRequestURI(originLocation);
		
		return fcLocation;
	}
	
	
	
    /**
     * revert header from HttpClient format (call to origin) to Map 
     * 
     * @param headers
     * @return
     */
    public static Map<String, List<String>> revertHeaders(Header[] headers) {
    	Map<String, List<String>> map = new HashMap<String, List<String>>();
		for (Header header : headers) {
			String name = header.getName();
			
			if (isIncludedHeaderToResponse(name))
			{
				List<String> hValues = map.get(name);
				if(null == hValues)
				{
					hValues = new ArrayList<String>();
					map.put(name, hValues);
				}
				hValues.add(header.getValue());
			}
		}
		return map;
	}

    public static Map<String, List<String>> revertHeaders(HttpServletResponse response) {
    	Map<String, List<String>> map = new HashMap<String, List<String>>();
		for (String name : response.getHeaderNames())
		{
			if (isIncludedHeaderToResponse(name))
			{
				List<String> hValues = map.get(name);
				if(null == hValues)
				{
					hValues = new ArrayList<String>();
					map.put(name, hValues);
				}
				hValues.add(response.getHeader(name));
			}
		}
		return map;
	}
    
	private static boolean isIncludedHeaderToResponse(String headerName) {
		String name = headerName.toLowerCase();

		switch (name) {
			case "content-length": // do not use 'content-length' because 'transfer-encoding' is used 
			case "transfer-encoding": // put it here to avoid duplicates 
				return false;
			default:
				return true;
		}
	}

	public static Header[] convertHeaders(Map<String, List<String>> headers) {
		List<Header> list = new ArrayList<>();
		for (String name : headers.keySet()) {
			for (String value : headers.get(name)) {
				list.add(new BasicHeader(name, value));
			}
		}
		return list.toArray(new BasicHeader[0]);
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
        	Enumeration<String> paramNames = request.getParameterNames();
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
     * return true if the client requested gzip content
     *
     * @param contentEncoding 
     * @return true if the content-encoding containg gzip
     */
    public static boolean isGzipped(String contentEncoding) {
        return contentEncoding.contains("gzip");
    }
    
	/**
	 * wrap String to WebResponse.
	 * Check for header - extract caching options.
	 * 
	 * @param contentStr
	 * @return
	 */
	private static final WebResponse parseWebComponent (String urlStr, byte[] content, Map<String, List<String>> headers) // , String contentType
	{
		String cacheMaxAgeSecStr = null;
		String refreshTypeStr = null;
		String cacheLevelStr = null;
		byte[] outContentBody = content;
		
		Collection<String> collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		if (null != collStr && !collStr.isEmpty())
			cacheMaxAgeSecStr = collStr.iterator().next();
		
		collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_REFRESH_TYPE);
		if (null != collStr && !collStr.isEmpty())
			refreshTypeStr = collStr.iterator().next();

		collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_CACHE_LEVEL);
		if (null != collStr && !collStr.isEmpty())
			cacheLevelStr = collStr.iterator().next();
		
		WebResponse component = new WebResponse(urlStr, outContentBody, cacheMaxAgeSecStr, refreshTypeStr);
		
		if (null != cacheLevelStr)
			component.setCacheLevel(cacheLevelStr);
		
		// set invalidation tags
		Collection<String> tagsList = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS);
		if (null != tagsList)
			for (String tagsStr : tagsList)
				component.addTags(Arrays.asList(tagsStr.split(FCHeaders.COMPONENT_TAGS_SEPARATOR)));
		
		return component;
	}
	
	public static long maxAgeStr2Int(String maxAgeStr)
	{
		try
		{
			int multiplyPrefix = 1;
			if ("forever".equalsIgnoreCase(maxAgeStr.trim())) // forever
			{
				return CacheProcessor.CACHE_FOREVER;				
			} else if (maxAgeStr.endsWith("d")) { // days
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
	}

	public static String buildRequestURI(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri;
	}		

	/**
	 * http://localhost:8080/coin_instance_details.htm? -> /coin_instance_details.htm?
	 * 
	 * @param urlStr
	 * @return
	 */
	public static String buildRequestURI(String urlStr) {
		
		int idx = urlStr.indexOf("//");
		if (-1 < idx)
		{
			idx = urlStr.indexOf("/", idx + "//".length());
			if (-1 < idx)
				return urlStr.substring(idx);
		} 
		
		return urlStr;
	}
	
	/**
	 * http://localhost:8080/coin_instance_details.htm? -> http
	 * @param urlStr
	 * @return
	 */
	public static String getRequestProtocol(String urlStr) {
		
		int idx = urlStr.indexOf(":");
		if (-1 < idx)
			return urlStr.substring(0, idx);
		
		return ""; // default
	}		
	
	public static HttpHost getHttpHost(URL host) {
		HttpHost httpHost = new HttpHost(host.getHost(), host.getPort(), host.getProtocol());
		return httpHost;
	}
	
	public static Map<String, List<String>> buildRequestHeaders(HttpServletRequest request) {

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		Enumeration<String> headerNames = request.getHeaderNames();
		if (headerNames != null) {
			while (headerNames.hasMoreElements()) {
				String name = headerNames.nextElement();
				if (isIncludedHeader(name)) {
					Enumeration<String> values = request.getHeaders(name);
					while (values.hasMoreElements()) {
						String value = values.nextElement();
						
						List<String> hValues = headers.get(name);
						if(null == hValues)
						{
							hValues = new ArrayList<String>();
							headers.put(name, hValues);
						}
						hValues.add(value);
					}
				}
			}
		}

		return headers;
	}
	
	private static boolean isIncludedHeader(String headerName) {
		String name = headerName.toLowerCase();

		switch (name) {
			case "host":
			case "connection":
			case "content-length":
			case "content-encoding":
			case "server":
			case "transfer-encoding":
				return false;
			default:
				return true;
		}
	}
	
	
	public static String getVerb(HttpServletRequest request) {
		String sMethod = request.getMethod();
		return sMethod.toUpperCase();
	}	

	public static void writeResponse(InputStream zin, OutputStream out) throws Exception {
		byte[] bytes = new byte[1024];
		int bytesRead = -1;
		while ((bytesRead = zin.read(bytes)) != -1) {
			try {
				out.write(bytes, 0, bytesRead);
				out.flush();
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
			// doubles buffer size if previous read filled it
			if (bytesRead == bytes.length) {
				bytes = new byte[bytes.length * 2];
			}
		}
	}
	
	public static String getDomainFromSiteKeyHeader(HttpServletRequest request) {
		String siteKey = request.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY);
		if (siteKey == null) {
			return null;
		}

		String domain = null;

		DomainContext domainContext = FrontCacheEngine.getFrontCache().getDomainContexBySiteKey(siteKey);
		if (null != domainContext)
			domain = domainContext.getDomain();

		return domain;
	}
	
	
}
