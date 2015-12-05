package org.frontcache.cache;

import java.io.IOException;
import java.util.Properties;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.WebComponent;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;

public interface CacheProcessor {

	public final static int NO_CACHE = 0;
	public final static int CACHE_FOREVER = -1;

	public void init(Properties properties);
	public void destroy();
	
	public void putToCache(String url, WebComponent component);
	public WebComponent getFromCache(String url);
	public void removeFromCache(String filter);
	public void removeFromCacheAll();
	
	// used in filter
	public String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException;
	
	// used in servlet
	public String processCacheableRequest(HttpServletRequest httpRequest, HttpServletResponse response, String urlStr) throws IOException, ServletException;
	
}
