package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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
import org.frontcache.io.CachedKeysActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
import org.frontcache.io.PutToCacheActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
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
		urlParameters.add(new BasicNameValuePair("action", "invalidate"));
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
		urlParameters.add(new BasicNameValuePair("action", "invalidate"));
		urlParameters.add(new BasicNameValuePair("filter", "*"));
		
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
	public String getCacheState()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", "get-cache-state"));
		
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
	public CachedKeysActionResponse getCachedKeys()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", "get-cached-keys"));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			logger.debug("getFromCache() -> " + responseStr);
			CachedKeysActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), CachedKeysActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
		
	/**
	 * 
	 * @return
	 */
	public GetFromCacheActionResponse getFromCache(String key)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", "get-from-cache"));
		urlParameters.add(new BasicNameValuePair("key", key));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			logger.debug("getFromCache() -> " + responseStr);
			GetFromCacheActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), GetFromCacheActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	/**
	 * 
	 * @return
	 */
	public PutToCacheActionResponse putToCache(WebResponse webResponse)
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", "put-to-cache"));
		String webResponseJSON = null;
		try {
			webResponseJSON = jsonMapper.writeValueAsString(webResponse);
		} catch (JsonProcessingException e1) {
			e1.printStackTrace();
			return null;
		}

		urlParameters.add(new BasicNameValuePair("webResponseJSON", webResponseJSON));
		
		try {
			String responseStr = requestFrontCache(urlParameters);
			logger.debug("putToCache() -> " + responseStr);
			PutToCacheActionResponse actionResponse = jsonMapper.readValue(responseStr.getBytes(), PutToCacheActionResponse.class);
			return actionResponse;
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
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
	
}
