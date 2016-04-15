package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;

public class FrontCacheClient {

	private String frontCacheURL;
	
	private String frontCacheURI;
	
	private final static String IO_URI = "frontcache-io";
	
	private HttpClient client;

	public FrontCacheClient(String frontcacheURL) {
		client = HttpClientBuilder.create().build();
		
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
		}
		
		return null;
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
		}
		
		return null;
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
		}
		
		return null;
	}
	
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
