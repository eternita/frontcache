package org.frontcache.cache;

import java.util.Properties;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.FrontCacheClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;

public interface CacheProcessor {

	public final static int NO_CACHE = 0;
	public final static int CACHE_FOREVER = -1;

	public void init(Properties properties);
	public void destroy();
	
	public void putToCache(String url, WebResponse component);
	public WebResponse getFromCache(String url);
	public void removeFromCache(String filter);
	public void removeFromCacheAll();
	
	public WebResponse processRequest(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException;
	
	public FrontCacheClient getFrontCacheClient();

	// used in filter
//	public String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException;
	
	// used in servlet
//	public String processCacheableRequest(HttpServletRequest httpRequest, HttpServletResponse response, String urlStr) throws IOException, ServletException;
	
}
