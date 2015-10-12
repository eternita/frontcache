package org.frontcache.cache;

import java.io.IOException;
import java.util.Properties;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;

public interface CacheProcessor {

	public final static int NO_CACHE = 0;
	public final static int CACHE_FOREVER = -1;
	public final static int DEFAULT_CACHE_MAX_AGE = NO_CACHE;

	public void init(Properties properties);
	public void destroy();
	
	public String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException;
	
}
