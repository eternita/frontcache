package org.frontcache;

import java.io.Serializable;

public class WebComponent implements Serializable {



	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

//	private Map<String, String> headers;

	private String content;
	
	private String contentType;
	
	private int cacheMaxAge = 0;
	
//	private String url;
	
	
	public WebComponent() {
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

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getContent() {
		return content;
	}


	public void setContent(String content) {
		this.content = content;
	}
	
}
