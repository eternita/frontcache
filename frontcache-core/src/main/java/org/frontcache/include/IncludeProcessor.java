package org.frontcache.include;

import java.util.Properties;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.WebResponse;

/**
 * 
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public interface IncludeProcessor {

	final static int MAX_RECURSION_LEVEL = 10;
	
	final static int MAX_INCLUDE_LENGHT = 500; // max distance/length for include tag with URL inside	
	
	public void init(Properties properties);
	
	public void destroy();

	public boolean hasIncludes(WebResponse webResponse, int recursionLevel);

	public WebResponse processIncludes(WebResponse parentWebResponse, String appOriginBaseURL, MultiValuedMap<String, String> requestHeaders, HttpClient client);
	
	public void setCacheProcessor(CacheProcessor cacheProcessor);

}
