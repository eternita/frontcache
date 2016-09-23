package org.frontcache.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.cache.CacheProcessor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 
 * Container for web response. Usually it's text response for GET method.  
 * 
 *
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class WebResponse implements Serializable {


	/**
	 * 
	 */
	private static final long serialVersionUID = 4L; // v0.4 -> 4, v1.0 -> 10

	private int statusCode = -1; // for redirects
	
	private String url;
	
	private byte[] content;
	
	/**
	 * Some headers, such as Accept-Language can be sent by clients as several headers each with a different value rather than sending the header as a comma separated list
	 */
	private Map<String, List<String>> headers;
	
	private Set<String> tags;
	
	private String contentType;
	
	// -1 cache forever
	// 0 never cache
	// 123456789 expiration time in ms
	private long expireTimeMillis = CacheProcessor.NO_CACHE;
	
	public WebResponse() { // for JSON converter
		this("dummy", null, -1);
	}
	/**
	 * some responses has no body (e.g. response for redirect)
	 * 
	 * @param url
	 */
	public WebResponse(String url) {
		this(url, null, -1);
	}
	
	public WebResponse(String url, byte[] content) {
		this(url, content, -1);
	}
	
	public WebResponse(String url, byte[] content, int cacheMaxAgeSec) {
		super();
		this.url = url;
		this.headers = new HashMap<String, List<String>>();
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



	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	public String getHeader(String name) {
		
		Collection<String> headerValues = headers.get(name);
		
		if (null != headerValues && !headerValues.isEmpty())
			return headerValues.iterator().next();
		
		return null;
	}
	
	public boolean isText()
	{
		String contentType = getContentType();
		if (null != contentType && -1 < contentType.indexOf("text"))
			return true;
		
		return false;
	}

	/**
	 * 
	 * @param headers
	 */
	public void setHeaders(Map<String, List<String>> headers) {
		this.headers = headers;
		
		return;
	}

	/**
	 * 
	 * @return
	 */
	public Set<String> getTags() {
		return tags;
	}


	/**
	 * 
	 * @param tags
	 */
	public void setTags(Set<String> tags) {
		this.tags = tags;
	}

	/**
	 * 
	 * @return
	 */
	@JsonIgnore
	public byte[] getContent() {
		return content;
	}

	/**
	 * 
	 * @param content
	 */
	@JsonIgnore
	public void setContent(byte[] content) {
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
	 * Check if response is cacheable 
	 * 
	 * @return
	 */
	public boolean isCacheable()
	{
		if (expireTimeMillis == CacheProcessor.NO_CACHE) 
			return false; // do not cache marker
		
		if (null == headers)  
			return false; // no header
		
		if (null == content) 
			return false;  // no data
		
		if (null == contentType || -1 == contentType.indexOf("text")) 
			return false;  // response data is not text
		
		return true; 
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
		if (null != content)
			return content.length;
		
		return -1;
	}
	
	public void addHeader(String name, String value)
	{
		List<String> values = headers.get(name);
		if(null == values)
		{
			values = new ArrayList<String>();
			headers.put(name, values);
		}
		values.add(value);
	}

    /**
     * 
     * @return
     */
    public WebResponse copy() {
    	WebResponse copy = new WebResponse(this.url, this.content);
    	copy.contentType = this.contentType;
    	copy.expireTimeMillis = this.expireTimeMillis;
    	copy.statusCode = this.statusCode;
    	
    	if (null != this.tags)
    	{
    		copy.tags = new HashSet<String>();
    		copy.tags.addAll(this.tags);
    	}
    	
    	if (null != headers)
    	{
    		copy.headers = new HashMap<String, List<String>>(); //ArrayListValuedHashMap<String, String>();
    		
    		for (String name : this.headers.keySet()) 
    			for (String value : headers.get(name)) 
    				copy.addHeader(name, value);
    	}
    	
        return copy;
    }	
}
