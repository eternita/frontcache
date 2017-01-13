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
	private static final long serialVersionUID = 7L; 

	private int statusCode = -1; // for redirects
	
	private String url;
	
	private String domain;
	
	private byte[] content;
	
	/**
	 * Some headers, such as Accept-Language can be sent by clients as several headers each with a different value rather than sending the header as a comma separated list
	 */
	private Map<String, List<String>> headers;
	
	private final Set<String> tags = new HashSet<String>();
	
	// -1 cache forever
	// 0 never cache
	// 123456789 expiration time in ms
	
	// empty map -> never cache
	private Map<String, Long> expireTimeMap = new HashMap<String, Long>();
	
	private String refreshType = null; // null is default (regular) [regular | soft]
	
	private String cacheLevel = null; // null is default (L2) [L1 | L2]
	
	
	public WebResponse() { // for JSON converter
		this("dummy", null, null, null);
	}
	/**
	 * some responses has no body (e.g. response for redirect)
	 * 
	 * maxAgeStr = null so, content is not subject to cache (maxAge = 0/no_cache)
	 * 
	 * @param url
	 */
	public WebResponse(String url) {
		this(url, null, null, null);
	}
	
	/**
	 * 
	 * maxAgeStr = null so, content is not subject to cache (maxAge = 0/no_cache)
	 * 
	 * @param url
	 * @param content
	 */
	public WebResponse(String url, byte[] content) {
		this(url, content, null, null);
	}
	
	public WebResponse(String url, byte[] content, String maxAgeStr, String refreshType) {
		super();
		this.url = url;
		this.headers = new HashMap<String, List<String>>();
		this.content = content;
		setExpireTime(maxAgeStr);
		this.refreshType = refreshType;
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
		String contentType = getHeader(FCHeaders.CONTENT_TYPE);
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
		this.tags.addAll(tags);
	}

	/**
	 * 
	 * @param tags
	 */
	public void addTags(Collection<String> tags) {
		this.tags.addAll(tags);
	}
	
	/**
	 * 
	 * @return
	 */
	public byte[] getContent() {
		return content;
	}

	/**
	 * 
	 * @param content
	 */
	public void setContent(byte[] content) {
		this.content = content;
	}
	
	/**
	 * Check if response is cacheable 
	 * 
	 * @return
	 */
	public boolean isCacheable()
	{
		if (expireTimeMap.isEmpty()) 
			return false; // do not cache marker
		
		if (null == headers)  
			return false; // no header
		
		if (null == content) 
			return false;  // no data
		
		String contentType = getHeader(FCHeaders.CONTENT_TYPE);
		if (null == contentType || -1 == contentType.indexOf("text")) 
			return false;  // response data is not text
		
		return true; 
	}

	/**
	 * 
	 * 
	 * @param expiteTimeStr - maxAge="[bot|browser:]30d"
	 */
	private void setExpireTime(String maxAgeStr) {
	
		String maxAgeClientType = null; // bot and browser
		String maxAgeTime = "0"; // default no cache
		
		if (null != maxAgeStr)
		{
			if (-1 < maxAgeStr.indexOf(":"))
			{
				maxAgeClientType = maxAgeStr.substring(0, maxAgeStr.indexOf(":"));
				maxAgeTime = maxAgeStr.substring(maxAgeStr.indexOf(":") + 1);
				
				if ("".equals(maxAgeTime))
					maxAgeTime = "0";
				
			} else {
				maxAgeTime = maxAgeStr;
			}
		}

		
		
		long cacheMaxAgeSecLong = 0; // default - no cache
		try
		{
			cacheMaxAgeSecLong = FCUtils.maxAgeStr2Int(maxAgeTime);				
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		

		if (CacheProcessor.CACHE_FOREVER == cacheMaxAgeSecLong)
			cacheMaxAgeSecLong = CacheProcessor.CACHE_FOREVER;
		else if (CacheProcessor.NO_CACHE == cacheMaxAgeSecLong)
			cacheMaxAgeSecLong = CacheProcessor.NO_CACHE;
		else 
			cacheMaxAgeSecLong = System.currentTimeMillis() + 1000 * cacheMaxAgeSecLong;
		
		if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(maxAgeClientType))
		{
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, cacheMaxAgeSecLong);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, CacheProcessor.NO_CACHE);
			
		} else if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(maxAgeClientType)) {
			
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, CacheProcessor.NO_CACHE);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, cacheMaxAgeSecLong);
		} else {
			// default
			// TODO: something wrong - log it 
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, cacheMaxAgeSecLong);
			this.expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, cacheMaxAgeSecLong);
		}
			
		return;
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
    	copy.expireTimeMap.putAll(this.getExpireTimeMap());
    	copy.statusCode = this.statusCode;
    	copy.refreshType = this.refreshType;
    	copy.cacheLevel = this.cacheLevel;
    	copy.domain = this.domain;
    	copy.tags.addAll(this.tags);
    	
    	if (null != headers)
    	{
    		copy.headers = new HashMap<String, List<String>>(); //ArrayListValuedHashMap<String, String>();
    		
    		for (String name : this.headers.keySet()) 
    			for (String value : headers.get(name)) 
    				copy.addHeader(name, value);
    	}
    	
        return copy;
    }
    
	public Map<String, Long> getExpireTimeMap() {
		return expireTimeMap;
	}
	
	public void setExpireTimeMap(Map<String, Long> expireTimeMap) {
		this.expireTimeMap = expireTimeMap;
	}
	
	public String getRefreshType() {
		return refreshType;
	}
	
	// for JSON convenience only
	public void setRefreshType(String refreshType) {
		this.refreshType = refreshType;
	}
	public String getCacheLevel() {
		return cacheLevel;
	}
	public void setCacheLevel(String cacheLevel) {
		this.cacheLevel = cacheLevel;
	}
	public String getDomain() {
		return domain;
	}
	public void setDomain(String domain) {
		this.domain = domain;
	}
	
}
