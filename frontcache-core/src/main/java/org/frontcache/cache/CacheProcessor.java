package org.frontcache.cache;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

public interface CacheProcessor {

	public final static String CACHED_ENTRIES = "cached-entries";
	
	public final static long NO_CACHE = 0;
	
	public final static long CACHE_FOREVER = -1;

	public void init(Properties properties);
	
	public void destroy();
	
	public void putToCache(String domain, String url, WebResponse component);
	
	public WebResponse getFromCache(String url);
	
	public void removeFromCache(String domain, String filter);
	
	public void removeFromCacheAll(String domain);
	
	public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException;
	
	public Map<String, String> getCacheStatus();
	
	public List<String> getCachedKeys();
	
	public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context);
	
	public void patch();	
	
}
