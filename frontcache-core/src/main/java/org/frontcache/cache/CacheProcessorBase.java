package org.frontcache.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.FC_ThroughCache;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = LoggerFactory.getLogger(getClass());
	
	private static final String[] NON_PERSISTENT_HEADERS = new String[]{
			"Set-Cookie", 
			"Date",
			FCHeaders.X_FRONTCACHE_ID,
			FCHeaders.X_FRONTCACHE_COMPONENT,
//			FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE,
//			FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, //  tags should not be filtered by FC (e.g. client -> fc2 (standalone) -> fc1 (filter) -> origin)
			FCHeaders.X_FRONTCACHE_REQUEST_ID,
			FCHeaders.X_FRONTCACHE_CLIENT_IP,
			FCHeaders.X_FRONTCACHE_DEBUG,
			FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE,
			FCHeaders.X_FRONTCACHE_DEBUG_CACHED,
			FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME,
			FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE
		};
	
	public abstract WebResponse getFromCacheImpl(String url);

	@Override
	public final WebResponse getFromCache(String url)
	{
		WebResponse cachedWebResponse = new FC_ThroughCache(this, url).execute();
		
		return cachedWebResponse;
	}


	@Override
	public WebResponse processRequest(String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;
		
		long lengthBytes = -1;
		
		String currentRequestURL = context.getCurrentRequestURL();
		
		WebResponse cachedWebResponse = new FC_ThroughCache(this, currentRequestURL).execute();
		
		// isDynamicForClientType depends on clientType (bot|browser) - maxAge="[bot|browser:]30d"
		// content is cached for bots and dynamic for browsers
		// when dynamic - don't update cache
		boolean isCacheableForClientType = true; // true - save/update to cache (default value is incorrect when include is pure dynamic);  false - don't save/update to cache
		
		if (null != cachedWebResponse)
		{
			String clientType = context.getClientType(); // bot | browser
			Map<String, Long> expireTimeMap = cachedWebResponse.getExpireTimeMap();
		
			isCacheableForClientType = isWebComponentCacheableForClientType(expireTimeMap, clientType);
			
			if (isWebComponentExpired(expireTimeMap, clientType))
			{
				removeFromCache(currentRequestURL);
				cachedWebResponse = null; // refresh from origin
			}
		}

		if (!isCacheableForClientType || // call origin if request is dynamic for client type [bot|browser] or component is null
				null == cachedWebResponse)
		{
			try
			{
				cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client, context); // it can be pure dynamic include -> check if we need to save to cache 
				
				String clientType = context.getClientType(); // bot | browser
				Map<String, Long> expireTimeMap = cachedWebResponse.getExpireTimeMap();
				
				boolean isFreshDataCacheableForClientType = isWebComponentCacheableForClientType(expireTimeMap, clientType);
				
				lengthBytes = cachedWebResponse.getContentLenth();

				// save to cache
				if (isCacheableForClientType && isFreshDataCacheableForClientType && cachedWebResponse.isCacheable())
				{
					WebResponse copy4cache = cachedWebResponse.copy();
					Map<String, List<String>> copyHeaders = copy4cache.getHeaders(); 
					for (String removeKey : NON_PERSISTENT_HEADERS)
						copyHeaders.remove(removeKey);
					
					copy4cache.setUrl(currentRequestURL);
					putToCache(currentRequestURL, copy4cache); // put to cache copy
				}
			} catch (FrontCacheException ex) {
				throw ex;
			} catch (Exception ex) {
				ex.printStackTrace();
				throw new FrontCacheException(ex);
			}
				
		} else {
			
			cachedWebResponse = cachedWebResponse.copy(); //to avoid modification instance in cache
			isCached = true;
			lengthBytes = cachedWebResponse.getContentLenth();			
		}
		
		
		RequestLogger.logRequest(currentRequestURL, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);
		
		return cachedWebResponse;
	}	
	
	/**
	 * Check with current time if expired
	 *  
	 * @param clientType {bot | browser}
	 * @return
	 */
	private boolean isWebComponentExpired(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMap must not be empty for clientType=" + clientType);
			return true; 
		}
		
		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentExpired() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return true; 
		}
		
		if (CacheProcessor.CACHE_FOREVER == expireTimeMillis)
			return false;
		
		if (System.currentTimeMillis() > expireTimeMillis)
			return true;
		
		return false;
	}

	// true - save to cache
	private boolean isWebComponentCacheableForClientType(Map<String, Long> expireTimeMap, String clientType)
	{
		if (expireTimeMap.isEmpty())
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMap must not be empty for clientType=" + clientType);
			return false; 
		}
		
		Long expireTimeMillis = expireTimeMap.get(clientType);
		if (null == expireTimeMillis)
		{
			// not a case -> log it
			logger.error("isWebComponentCacheableForClientType() - expireTimeMillis must be in expireTimeMap for clientType=" + clientType);
			return false; 			
		}
		
		if (CacheProcessor.NO_CACHE == expireTimeMillis)
			return false;
		
		return true;
	}
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = new HashMap<String, String>();
		status.put("impl", this.getClass().getName());

		return status;
	}
	
	@Override
	public void init(Properties properties) {		
		Objects.requireNonNull(properties, "Properties should not be null");
	}

}
