package org.frontcache;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

import org.frontcache.cache.CacheProcessor;

public class WebComponent implements Serializable {


	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private String url;
	
	private String content;
	
	private Map<String, String> headers;
	
	private Set<String> tags;
	
	private String contentType;
	
	// -1 cache forever
	// 0 never cache
	// 123456789 expiration time in ms
	private long expireTimeMillis = CacheProcessor.NO_CACHE;
	
	
	public WebComponent(String url, String content, int cacheMaxAgeSec) {
		super();
		this.content = content;
		setExpireTime(cacheMaxAgeSec);
	}

	
	
	public String getUrl() {
		return url;
	}



	public void setUrl(String url) {
		this.url = url;
	}



	public Map<String, String> getHeaders() {
		return headers;
	}



	public void setHeaders(Map<String, String> headers) {
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
		return expireTimeMillis != CacheProcessor.NO_CACHE;
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
	
}
