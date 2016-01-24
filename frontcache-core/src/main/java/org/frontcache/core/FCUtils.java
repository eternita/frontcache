package org.frontcache.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.message.BasicHeader;
import org.frontcache.cache.CacheProcessor;


public class FCUtils {

	private FCUtils() {
	}
	
	private static Logger logger = Logger.getLogger(FCUtils.class.getName());
	
	/**
	 * e.g. http://localhost:8080
	 * 
	 * @param httpRequest
	 * @return
	 */
    public static String getBaseURL(HttpServletRequest httpRequest)
    {
    	StringBuffer sb = new StringBuffer();
    	if (httpRequest.isSecure())
    		sb.append("https://");
    	else
    		sb.append("http://");
    	sb.append(httpRequest.getServerName());
    	sb.append(":");
    	sb.append(httpRequest.getServerPort());
    	return sb.toString();
    }

	
	/**
	 * GET method only for text requests
	 * 
	 * @param urlStr
	 * @param httpRequest
	 * @param httpResponse
	 * @return
	 */
	public static WebResponse dynamicCall(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException
    {
		HttpGet request = new HttpGet(urlStr);
		
		// translate headers
		Header[] httpHeaders = convertHeaders(requestHeaders);
		for (Header header : httpHeaders)
			request.addHeader(header);
		
		HttpResponse response = null;
		try {
			response = client.execute(request);
			WebResponse webResp = httpResponse2WebComponent(urlStr, response);
			return webResp;

		} catch (IOException ioe) {
			throw new FrontCacheException("Can't read from " + urlStr, ioe);
		} finally {
			if (null != response)
				try {
					((CloseableHttpResponse) response).close();
				} catch (IOException e) {
					e.printStackTrace();
				} 
		}
		
    }
	
	private static WebResponse httpResponse2WebComponent(String url, HttpResponse response) throws FrontCacheException, IOException
	{
		
		
		int httpResponseCode = response.getStatusLine().getStatusCode();
			
		if (httpResponseCode < 200 || httpResponseCode > 299)
		{
			// error
//			throw new RuntimeException("Wrong response code " + httpResponseCode);
		}

		// get headers
		MultiValuedMap<String, String> headers = revertHeaders(response.getAllHeaders());
		
		// process redirects
		Header locationHeader = response.getFirstHeader("Location");
		if (null != locationHeader)
		{
			String originLocation = locationHeader.getValue();
			
			RequestContext context = RequestContext.getCurrentContext();
			String currentRequestBaseURL = context.getFrontCacheHost();
			
			String fcLocation = currentRequestBaseURL + buildRequestURI(originLocation);
			headers.remove("Location");
			headers.put("Location", fcLocation);
		}
		
		String contentType = "";
		Header contentTypeHeader = response.getFirstHeader("Content-Type");
		if (null != contentTypeHeader)
			contentType = contentTypeHeader.getValue();
		
		WebResponse webResponse = null;
		if (-1 == contentType.indexOf("text"))
		{
//			throw new FrontCacheException("Not cacheable content type (" + contentType + ") for " + url);
			webResponse = new WebResponse(url);

		} else {
			BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
			StringBuffer result = new StringBuffer();
			String line = null;
			while ((line = rd.readLine()) != null) {
				result.append(line);
			}
			
			String dataStr = result.toString();
			webResponse = parseWebComponent(url, dataStr);
		}

		webResponse.setStatusCode(httpResponseCode);
		webResponse.setHeaders(headers);
		
		if (!"".equals(contentType))
			webResponse.setContentType(contentType);
				
		return webResponse;
	}
	
//	private static void replaceRedirectHeaderInOrigingRequest(MultiValuedMap<String, String> headers)
//	{
//		
//		return;
//	}
	
    /**
     * returns query params as a Map with String keys and Lists of Strings as values
     * @return
     */
    public static Map<String, List<String>> getQueryParams() {

        Map<String, List<String>> qp = RequestContext.getCurrentContext().getRequestQueryParams();
        if (qp != null) return qp;

        HttpServletRequest request = RequestContext.getCurrentContext().getRequest();

        qp = new HashMap<String, List<String>>();

        if (request.getQueryString() == null) return null;
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

        RequestContext.getCurrentContext().setRequestQueryParams(qp);
        return qp;
    }
	
    /**
     * revert header from HttpClient format (call to origin) to Map 
     * 
     * @param headers
     * @return
     */
    public static MultiValuedMap<String, String> revertHeaders(Header[] headers) {
		MultiValuedMap<String, String> map = new ArrayListValuedHashMap<String, String>();
		for (Header header : headers) {
			String name = header.getName();
			
			if (isIncludedHeaderToResponse(name))
				map.put(name, header.getValue());
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

	public static Header[] convertHeaders(MultiValuedMap<String, String> headers) {
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

	public static String getQueryString(HttpServletRequest request) {
		MultiValuedMap<String, String> params = FCUtils.builRequestQueryParams(request);
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
	 * @param content
	 * @return
	 */
	private static final WebResponse parseWebComponent (String urlStr, String content)
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

		WebResponse component = new WebResponse(urlStr, outStr, cacheMaxAgeSec);
		
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
	
	public static HttpHost getHttpHost(URL host) {
		HttpHost httpHost = new HttpHost(host.getHost(), host.getPort(), host.getProtocol());
		return httpHost;
	}
	
	public static MultiValuedMap<String, String> builRequestQueryParams(HttpServletRequest request) {
		Map<String, List<String>> map = FCUtils.getQueryParams();
		MultiValuedMap<String, String> params = new ArrayListValuedHashMap<>();
		if (map == null) {
			return params;
		}
		for (String key : map.keySet()) {
			for (String value : map.get(key)) {
				params.put(key, value);
			}
		}
		return params;
	}	

	public static MultiValuedMap<String, String> buildRequestHeaders(HttpServletRequest request) {

		MultiValuedMap<String, String> headers = new ArrayListValuedHashMap<>();
		Enumeration<String> headerNames = request.getHeaderNames();
		if (headerNames != null) {
			while (headerNames.hasMoreElements()) {
				String name = headerNames.nextElement();
				if (isIncludedHeader(name)) {
					Enumeration<String> values = request.getHeaders(name);
					while (values.hasMoreElements()) {
						String value = values.nextElement();
						headers.put(name, value);
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
