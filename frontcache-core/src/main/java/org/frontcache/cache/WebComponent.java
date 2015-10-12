package org.frontcache.cache;

import java.io.Serializable;
import java.util.Map;

public class WebComponent implements Serializable {



	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private Map<String, String> headers;

	private String content;
	
	private int cacheMaxAge = 0;
	
//	private String url;
	
	
	public WebComponent() {
		// TODO Auto-generated constructor stub
	}

	public boolean isCacheable()
	{
		return cacheMaxAge != 0;
	}

	public int getCacheMaxAge() {
		return cacheMaxAge;
	}


	public void setCacheMaxAge(int cacheMaxAge) {
		this.cacheMaxAge = cacheMaxAge;
	}


//	public String getUrl() {
//		return url;
//	}
//
//
//	public void setUrl(String url) {
//		this.url = url;
//	}


	public String getContent() {
		return content;
	}


	public void setContent(String content) {
		this.content = content;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}
	
}
