package org.frontcache.core;

import java.io.Serializable;
import java.util.Set;

import org.apache.commons.collections4.MultiValuedMap;
import org.frontcache.cache.CacheProcessor;

public class WebResponse implements Serializable {


	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private int statusCode = -1; // for redirects
	
	private String url;
	
	private String content;
	
	/**
	 * Some headers, such as Accept-Language can be sent by clients as several headers each with a different value rather than sending the header as a comma separated list
	 */
	private MultiValuedMap<String, String> headers;
	
	private Set<String> tags;
	
	private String contentType;
	
	// -1 cache forever
	// 0 never cache
	// 123456789 expiration time in ms
	private long expireTimeMillis = CacheProcessor.NO_CACHE;
	
	
	public WebResponse(String url, String content, int cacheMaxAgeSec) {
		super();
		this.content = content;
		setExpireTime(cacheMaxAgeSec);
	}	
	
	public int getStatusCode() {
		return statusCode;
	}



	public void setStatusCode(int statusCode) {
		this.statusCode = statusCode;
	}



	public String getUrl() {
		return url;
	}



	public void setUrl(String url) {
		this.url = url;
	}



	public MultiValuedMap<String, String> getHeaders() {
		return headers;
	}



	public void setHeaders(MultiValuedMap<String, String> headers) {
		this.headers = headers;
	}



	public Set<String> getTags() {
		return tags;
	}



	public void setTags(Set<String> tags) {
		this.tags = tags;
	}



	/**
	 * 
	 * @return
	 */
	public String getContent() {
		return content;
	}

	/**
	 * 
	 * @param content
	 */
	public void setContent(String content) {
		this.content = content;
	}
	
	/**
	 * 
	 * @return
	 */
	public String getContentType() {
		return contentType;
	}

	/**
	 * 
	 * @param contentType
	 */
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	/**
	 * 
	 * @return
	 */
	public boolean isCacheable()
	{
		if (expireTimeMillis == CacheProcessor.NO_CACHE)
			return false;
		
		if (null == headers)
			return false;
		
		if (null == contentType || -1 == contentType.indexOf("text"))
			return false;

			
//		for (String name : headers.keySet()) {
//			for (String value : headers.get(name)) {
//				if ("Content-Type".equals(name) && -1 < value.indexOf("text"))
//					return true;
//			}
//		}
		
		return true;
		
//		return expireTimeMillis != CacheProcessor.NO_CACHE;
	}

	/**
	 * 
	 * @param cacheMaxAgeSec time to live in seconds or CacheProcessor.CACHE_FOREVER
	 */
	public void setExpireTime(long cacheMaxAgeSec) {
		
		if (CacheProcessor.CACHE_FOREVER == cacheMaxAgeSec)
			this.expireTimeMillis = CacheProcessor.CACHE_FOREVER;
		
		else if (CacheProcessor.NO_CACHE == cacheMaxAgeSec)
			this.expireTimeMillis = CacheProcessor.NO_CACHE;
		
		else 
			this.expireTimeMillis = System.currentTimeMillis() + 1000 * cacheMaxAgeSec;
		
		return;
	}
	
	/**
	 * Check with current time if expired 
	 * 
	 * @return
	 */
	public boolean isExpired()
	{
		if (CacheProcessor.CACHE_FOREVER == expireTimeMillis)
			return false;
		
		if (System.currentTimeMillis() > expireTimeMillis)
			return true;
		
		return false;
	}

	/**
	 * content length in bytes
	 * 
	 * @return
	 */
	public long getContentLenth() 
	{
		if (null != getContent())
			return 2*getContent().length();
		
		return -1;
	}
	
}
