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
	
	private HttpClient client;

	protected FrontCacheClient() {
		client = HttpClientBuilder.create().build();
	}
	
	public FrontCacheClient(String fcURL) {
		this();
		
		if (fcURL.endsWith("/"))
			this.frontCacheURL = fcURL + "frontcache-io";
		else
			this.frontCacheURL = fcURL + "/frontcache-io";
	}
	
	public void removeFromCache(String filter)
	{
	}
	
	public void removeFromCacheAll()
	{
	}
	
	
	public String getCacheState()
	{
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("action", "get-cache-state"));
		
		
		try {
			return requestFrontCache(urlParameters);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	private String requestFrontCache(List<NameValuePair> urlParameters) throws IOException
	{
		HttpPost post = new HttpPost(frontCacheURL);

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
	
	

}
