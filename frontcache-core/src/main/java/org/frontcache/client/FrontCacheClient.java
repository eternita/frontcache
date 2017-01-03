package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackConfigEntry;
import org.frontcache.io.CacheStatusActionResponse;
import org.frontcache.io.FrontcacheAction;
import org.frontcache.io.GetBotsActionResponse;
import org.frontcache.io.GetDynamicURLsActionResponse;
import org.frontcache.io.GetFallbackConfigActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class FrontCacheClient {

	private String frontCacheURL;
	
	private String frontCacheURI;
	
	private final static String IO_URI = "frontcache-io";
	
	private ObjectMapper jsonMapper = new ObjectMapper();
	
	private HttpClient client;
	
	private Logger logger = LoggerFactory.getLogger(FrontCacheClient.class);


	public FrontCacheClient(String frontcacheURL) {
		final RequestConfig requestConfig = RequestConfig.custom()
				.setSocketTimeout(10000)
				.setConnectTimeout(3000)
				.setCookieSpec(CookieSpecs.IGNORE_COOKIES)
				.build();
		
	    ConnectionKeepAliveStrategy keepAliveStrategy = new ConnectionKeepAliveStrategy() {
	        @Override
	        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
	            HeaderElementIterator it = new BasicHeaderElementIterator
	                (response.headerIterator(HTTP.CONN_KEEP_ALIVE));
	            while (it.hasNext()) {
	                HeaderElement he = it.nextElement();
	                String param = he.getName();
	                String value = he.getValue();
	                if (value != null && param.equalsIgnoreCase
	                   ("timeout")) {
	                    return Long.parseLong(value) * 1000;
	                }
	            }
	            return 10 * 1000;
	        }
	    };
	    
	    client = HttpClients.custom()
				.setDefaultRequestConfig(requestConfig)
				.setRetryHandler(new DefaultHttpRequestRetryHandler(0, false))
				.setKeepAliveStrategy(keepAliveStrategy)
				.setRedirectStrategy(new RedirectStrategy() {
					@Override
					public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return false;
					}

					@Override
					public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
						return null;
					}
				})
				.build();
		
		this.frontCacheURL = frontcacheURL;
		
		if (frontcacheURL.endsWith("/"))
			this.frontCacheURI = frontcacheURL + IO_URI;
		else
			this.frontCacheURI = frontcacheURL + "/" + IO_URI;
	}
	
	/**
	 * 
	 * @param filter
	 * @return
	 */
	public String removeFromCache(String filter)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.INVALIDATE));
		urlParameters.add(new BasicNameValuePair("filter", filter));
		
		try {
			return requestFrontCache(urlParameters);
		} catch (Exception e) {
			e.printStackTrace();
			return "ERROR " + e.getMessage(); 
		}
	}
	
	/**
	 * 
	 * @return
	 */
	public String removeFromCacheAll()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.INVALIDATE));
		urlParameters.add(new BasicNameValuePair("filter", "*"));
		
		try {
			return requestFrontCache(urlParameters);
		} catch (Exception e) {
			e.printStackTrace();
			return "ERROR " + e.getMessage(); 
		}
	}
	
	public Map<String, String> getCacheState()
	{
		CacheStatusActionResponse actionResponse = getCacheStateActionResponse();
		
		if (null == actionResponse)
			return null;
		
		return actionResponse.getCacheStatus();
		
	}

	/**
	 * 
	 * @return
	 */
	public CacheStatusActionResponse getCacheStateActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_CACHE_STATE));
		String responseStr = null;
		try {
			responseStr = requestFrontCache(urlParameters);
			CacheStatusActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), CacheStatusActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			logger.error("Can't parse response. JSON format is expected for " + responseStr, e);
		}
		
		return null;
	}
	
	/**
	 * Writes keys to provided output stream
	 * 
	 * @param os
	 * @return
	 */
	public boolean getCachedKeys(OutputStream os)
	{
		boolean success = true;
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_CACHED_KEYS));
		
		HttpPost post = new HttpPost(frontCacheURI);

//    	post.addHeader("Accept-Encoding", "gzip");

		InputStream is = null;
		try {
			post.setEntity(new UrlEncodedFormEntity(urlParameters));
			HttpResponse response = client.execute(post);
			is = response.getEntity().getContent();
	        int bytesRead = 0;
	        int bufferSize = 4000;
	         byte[] byteBuffer = new byte[bufferSize];              
	         while ((bytesRead = is.read(byteBuffer)) != -1) {
	             os.write(byteBuffer, 0, bytesRead);
	         }
		} catch (Exception e) {
			success = false;
			e.printStackTrace();
		} finally {
			try {
				if (null != is)
					is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		return success;
	}
	
	/**
	 * 
	 * @return
	 */
	public GetFallbackConfigActionResponse getFallbackConfigsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_FALLBACK_CONFIGS));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			GetFallbackConfigActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetFallbackConfigActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public List<FallbackConfigEntry> getFallbackConfigs()
	{
		GetFallbackConfigActionResponse actionResponse = getFallbackConfigsActionResponse();
		
		if (null == actionResponse)
			return null;
		
		return actionResponse.getFallbackConfigs();
	}

	
	/**
	 * 
	 * @return
	 */
	public GetBotsActionResponse getBotsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_BOTS));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			GetBotsActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetBotsActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public Map<String, Set<String>> getBots()
	{
		GetBotsActionResponse actionResponse = getBotsActionResponse();
		
		if (null == actionResponse)
			return null;
		
		return actionResponse.getBots();
	}
	

	/**
	 * 
	 * @return
	 */
	public GetDynamicURLsActionResponse getDynamicURLsActionResponse()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_DYNAMIC_URLS));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			GetDynamicURLsActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetDynamicURLsActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public Map<String, Set<String>> getDynamicURLs()
	{
		GetDynamicURLsActionResponse actionResponse = getDynamicURLsActionResponse();
		
		if (null == actionResponse)
			return null;
		
		return actionResponse.getDynamicURLs();
	}
	
	
	/**
	 * 
	 * @return
	 */
	public GetFromCacheActionResponse getFromCacheActionResponse(String key)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", FrontcacheAction.GET_FROM_CACHE));
		urlParameters.add(new BasicNameValuePair("key", key));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			logger.debug("getFromCache(" + this + ") -> done");
			GetFromCacheActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetFromCacheActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public WebResponse getFromCache(String key)
	{
		GetFromCacheActionResponse actionResponse = getFromCacheActionResponse(key);
		
		if (null == actionResponse)
			return null;
		
		return actionResponse.getValue();
	}
	
	/**
	 * 
	 * @param urlParameters
	 * @return
	 * @throws IOException
	 */
	private String requestFrontCache(List<NameValuePair> urlParameters) throws IOException
	{
		HttpPost post = new HttpPost(frontCacheURI);

//    	post.addHeader("Accept-Encoding", "gzip");

		post.setEntity(new UrlEncodedFormEntity(urlParameters));

		StringBuffer result = new StringBuffer();
		HttpResponse response = client.execute(post);
		BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));

		String line = "";
		while ((line = rd.readLine()) != null) {
			result.append(line);
		}
		
		return result.toString();
	}

	public String getFrontCacheURL() {
		return frontCacheURL;
	}
	
	/**
	 *  http://localhost:8080/ -> localhost:8080
	 * @return
	 */
	public String getName() {
		String name = frontCacheURL.trim();
		
		int idx = name.indexOf("//");
		if (-1 < idx)
			name = name.substring(idx + "//".length());
		
		idx = name.indexOf(":"); // localhost:8080 -> localhost
		if (-1 < idx)
			name = name.substring(0, idx);
		
//		idx = name.indexOf("/"); // 
//		if (-1 < idx)
//			name = name.substring(0, idx);
		
		// e.g. localhost
		return name;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((frontCacheURI == null) ? 0 : frontCacheURI.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		FrontCacheClient other = (FrontCacheClient) obj;
		if (frontCacheURI == null) {
			if (other.frontCacheURI != null)
				return false;
		} else if (!frontCacheURI.equals(other.frontCacheURI))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "FrontCacheClient [" + frontCacheURL + "]";
	}
	
}
