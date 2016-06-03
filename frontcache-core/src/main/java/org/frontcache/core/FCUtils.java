package org.frontcache.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

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
		// add request-id header - to distinguish if sent by frontcache or not (for include processing) 
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());
		
		if (context.isFilterMode())
			 return new FC_ThroughCache_WebFilter(context).execute();
		else
			 return new FC_ThroughCache_HttpClient(urlStr, requestHeaders, client, context).execute();
    }

	/**
	 * for includes - they allways use httpClient
	 */
	public static WebResponse dynamicCallHttpClient(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException
    {
		// add request-id header - to distinguish (toplevel vs include) 
		// & distinguish if sent by frontcache or not (for include processing) 
		addHeader(requestHeaders, FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());
		
		return new FC_ThroughCache_HttpClient(urlStr, requestHeaders, client, context).execute();
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
		List<String> valuesList = new ArrayList<String>();
		valuesList.add(name);
		requestHeaders.put(key, valuesList);
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
		
		webResponse = parseWebComponent(url, data, FCUtils.revertHeaders(originWrappedResponse), contentType);



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
		if (null != dataStr && null == headers.get("Content-Type"))
		{
			webResponse.addHeader("Content-Type", contentType); 
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
		Header contentTypeHeader = response.getFirstHeader("Content-Type");
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
		webResponse = parseWebComponent(url, respData, headers, contentType);

		webResponse.setHeaders(headers);
		webResponse.setStatusCode(httpResponseCode);
		
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
		if ("https".equalsIgnoreCase(protocol))
			fcLocation = "https://" + context.getFrontCacheHost() + ":" + context.getFrontCacheHttpsPort() + buildRequestURI(originLocation);
		else // http
			fcLocation = "http://" + context.getFrontCacheHost() + ":" + context.getFrontCacheHttpPort() + buildRequestURI(originLocation);
		
		return fcLocation;
	}
	
	
    /**
     * returns query params as a Map with String keys and Lists of Strings as values
     * @return
     */
    public static Map<String, List<String>> getQueryParams(RequestContext context) {

        Map<String, List<String>> qp = context.getRequestQueryParams();
        if (qp != null) return qp;

        HttpServletRequest request = context.getRequest();

        qp = new HashMap<String, List<String>>();

        if (request.getQueryString() == null) return qp;
        StringTokenizer st = new StringTokenizer(request.getQueryString(), "&");
        int i;

        while (st.hasMoreTokens()) {
            String s = st.nextToken();
            i = s.indexOf("=");
            if (i > 0 && s.length() >= i + 1) {
                String name = s.substring(0, i);
                String value = s.substring(i + 1);

                try {
                    name = URLDecoder.decode(name, "UTF-8");
                } catch (Exception e) {
                }
                try {
                    value = URLDecoder.decode(value, "UTF-8");
                } catch (Exception e) {
                }

                List<String> valueList = qp.get(name);
                if (valueList == null) {
                    valueList = new LinkedList<String>();
                    qp.put(name, valueList);
                }

                valueList.add(value);
            }
            else if (i == -1)
            {
                String name=s;
                String value="";
                try {
                    name = URLDecoder.decode(name, "UTF-8");
                } catch (Exception e) {
                }
               
                List<String> valueList = qp.get(name);
                if (valueList == null) {
                    valueList = new LinkedList<String>();
                    qp.put(name, valueList);
                }

                valueList.add(value);
                
            }
        }

        context.setRequestQueryParams(qp);
        return qp;
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

	public static String getQueryString(HttpServletRequest request, RequestContext context) {
		Map<String, List<String>> params = FCUtils.getQueryParams(context); 
		StringBuilder query=new StringBuilder();
		
		try {
			for (String paramKey : params.keySet())
			{
				String key = URLEncoder.encode(paramKey, "UTF-8");
				for (String value : params.get(paramKey))
				{
					query.append("&");
					query.append(key);
					query.append("=");
					query.append(URLEncoder.encode(value, "UTF-8"));
				}
				
			}		
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		return (query.length()>0) ? "?" + query.substring(1) : "";
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
	private static final WebResponse parseWebComponent (String urlStr, byte[] content, Map<String, List<String>> headers, String contentType)
	{
		int cacheMaxAgeSec = CacheProcessor.NO_CACHE;
		byte[] outContentBody = content;
		
		// TODO: check headers for component's maxage
		if (null != headers.get(FCHeaders.X_FRONTCACHE_COMPONENT))
		{
			Collection<String> collStr = headers.get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
			if (null != collStr && !collStr.isEmpty())
			{
				String cacheMaxAgeSecStr = collStr.iterator().next();
				try
				{
					cacheMaxAgeSec = maxAgeStr2Int(cacheMaxAgeSecStr);				
					
				} catch (Exception ex) {}
			}

			WebResponse component = new WebResponse(urlStr, outContentBody, cacheMaxAgeSec);
			component.setContentType(contentType);
			return component;
		}
		

		//  for back compatibility - markup inside text
		
		if (null != contentType && -1 < contentType.indexOf("text") && null != content)
		{
			// it's text

			String contentStr = new String(content);
			String outStr = null;
			final String START_MARKER = "<fc:component";
			final String END_MARKER = "/>";
			
			int startIdx = contentStr.indexOf(START_MARKER);
			if (-1 < startIdx)
			{
				int endIdx = contentStr.indexOf(END_MARKER, startIdx);
				if (-1 < endIdx)
				{
					String includeTagStr = contentStr.substring(startIdx, endIdx + END_MARKER.length());
					cacheMaxAgeSec = getCacheMaxAge(includeTagStr);
					
					// exclude tag from content
					StringBuffer outSb = new StringBuffer();
					outSb.append(contentStr.substring(0, startIdx));
					if (FrontCacheEngine.debugComments)
						outSb.append("<!-- fc:component ttl=").append(cacheMaxAgeSec).append("sec -->"); // comment out tag (leave it for debugging purpose)
					outSb.append(contentStr.substring(endIdx + END_MARKER.length(), contentStr.length()));
					outStr = outSb.toString();
				} else {
					// can't find closing 
					outStr = contentStr;
				}
				
			} else {
				outStr = contentStr;
			}
			
			outContentBody = outStr.getBytes();
		} else {
			// it's binary data
		}
		

		WebResponse component = new WebResponse(urlStr, outContentBody, cacheMaxAgeSec);
		component.setContentType(contentType);

		
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
				return maxAgeStr2Int(maxAgeStr);				
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
	
	private static int maxAgeStr2Int(String maxAgeStr)
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
	
}
