/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
	
    protected ExecutorService softInvalidationExecutor = null;

	private static final String[] NON_PERSISTENT_HEADERS = new String[]{
			"Set-Cookie", 
			"Date",
			FCHeaders.X_FRONTCACHE_ID,
			FCHeaders.X_FRONTCACHE_COMPONENT,
//			FCHeaders.X_FRONTCACHE_FALLBACK_IS_USED, - if you see it in cache - something wrong - requests with fallbacks should not be cached
//			FCHeaders.X_FRONTCACHE_COMPONENT_CACHE_LEVEL,
//			FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE,
//			FCHeaders.X_FRONTCACHE_COMPONENT_REFRESH_TYPE,
//			FCHeaders.X_FRONTCACHE_SOFT_REFRESH,
//			FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, //  tags should not be filtered by FC (e.g. client -> fc2 (standalone) -> fc1 (filter) -> origin)
			FCHeaders.X_FRONTCACHE_REQUEST_ID,
			FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL,
			FCHeaders.X_FRONTCACHE_CLIENT_IP,
			FCHeaders.X_FRONTCACHE_TRACE,
			FCHeaders.X_FRONTCACHE_TRACE_REQUEST
		};
	
	public abstract WebResponse getFromCacheImpl(String url);

	@Override
	public final WebResponse getFromCache(String url)
	{
		WebResponse cachedWebResponse = new FC_ThroughCache(this, url, null).execute();
		
		return cachedWebResponse;
	}


	@Override
	public WebResponse processRequest(String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;
		
		long lengthBytes = -1;
		
		String currentRequestURL = context.getCurrentRequestURL();
		
		WebResponse cachedWebResponse = new FC_ThroughCache(this, currentRequestURL, context).execute();
		
		// isDynamicForClientType depends on clientType (bot|browser) - maxAge="[bot|browser:]30d"
		// content is cached for bots and dynamic for browsers
		// when dynamic - don't update cache
		boolean isCacheableForClientType = true; // true - save/update to cache (default value is incorrect when include is pure dynamic);  false - don't save/update to cache
		
		if (null != cachedWebResponse)
		{
			String clientType = context.getClientType(); // bot | browser
			Map<String, Long> expireTimeMap = cachedWebResponse.getExpireTimeMap();
		
			isCacheableForClientType = FCUtils.isWebComponentCacheableForClientType(expireTimeMap, clientType);
			
			// if data is cacheable for client type -> check data for expiration (only)
			// if data is dynamic for client type -> no expiration / invalidation check
			if (isCacheableForClientType && FCUtils.isWebComponentExpired(expireTimeMap, clientType))
			{
				
				String refreshType = cachedWebResponse.getRefreshType();
				if (FCHeaders.COMPONENT_REFRESH_TYPE_SOFT.equalsIgnoreCase(refreshType))
				{
					// soft expiration
					doSoftInvalidation(currentRequestURL, originUrlStr, requestHeaders, client, context);
				} else {
					// regular expiration
					removeFromCache(context.getDomainContext().getDomain(), currentRequestURL);
					cachedWebResponse = null; // refresh from origin
				}
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
				
				boolean isFreshDataCacheableForClientType = FCUtils.isWebComponentCacheableForClientType(expireTimeMap, clientType);
				
				lengthBytes = cachedWebResponse.getContentLenth();

				// save to cache
				if (!context.isHystrixFallback() // don't cache hystrix fallbacks 
						&& isCacheableForClientType 
						&& isFreshDataCacheableForClientType 
						&& cachedWebResponse.isCacheable())
				{
					WebResponse copy4cache = cachedWebResponse.copy();
					Map<String, List<String>> copyHeaders = copy4cache.getHeaders(); 
					cleanupNonPersistentHeaders(copyHeaders);
					
					copy4cache.setUrl(currentRequestURL);
					putToCache(context.getDomainContext().getDomain(), currentRequestURL, copy4cache); // put to cache copy
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
			context.setToplevelCached();
			lengthBytes = cachedWebResponse.getContentLenth();			
		}
		
		
		RequestLogger.logRequest(currentRequestURL, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);
		
		return cachedWebResponse;
	}
	
	/**
	 * perform async invalidation
	 * 
	 * @param currentRequestURL
	 * @param originUrlStr
	 * @param requestHeaders
	 * @param client
	 * @param context
	 */
	public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context)
	{
		// async invalidation
		softInvalidationExecutor.submit(new Runnable(){

			@Override
			public void run() {
				try {
					
//					logger.info("Soft invalidation: removing form cache: " + currentRequestURL);
					removeFromCache(context.getDomainContext().getDomain(), currentRequestURL);
					
					RequestContext ctxCopy = context.copy();
					ctxCopy.setFilterChain(null); // async calls for ServletFilter doesnt works (some objects already disposed), so use http calls for soft resets 
					Map<String, List<String>> requestHeadersCopy = new HashMap<String, List<String>>();
					requestHeadersCopy.putAll(requestHeaders);
					
					requestHeadersCopy.put(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST, Arrays.asList(new String[]{"true"}));
					requestHeadersCopy.put(FCHeaders.X_FRONTCACHE_SOFT_REFRESH, Arrays.asList(new String[]{"true"}));
					
					WebResponse copy4cache = FCUtils.dynamicCall(originUrlStr, requestHeadersCopy, client, ctxCopy);
					Map<String, List<String>> copyHeaders = copy4cache.getHeaders(); 
					cleanupNonPersistentHeaders(copyHeaders);
					
					copy4cache.setUrl(currentRequestURL);

					if (!ctxCopy.isHystrixFallback()) // don't cache hystrix fallbacks
						putToCache(context.getDomainContext().getDomain(), currentRequestURL, copy4cache); // put to cache copy
					
				} catch (Exception e) {
					
					logger.error("Soft invalidation/refresh failed: " + originUrlStr, e);
				}  
			}
			
		});
		
		return;
	}

	
	/**
	 * remove header not supposed to be stored in cache
	 * right before saving to cache
	 * 
	 * @param headers
	 */
	private void cleanupNonPersistentHeaders(Map<String, List<String>> headers)
	{
		Set<String> cleanupKeys = new HashSet<String>(Arrays.asList(NON_PERSISTENT_HEADERS));
		for(String key : headers.keySet())
			if (key.startsWith(FCHeaders.X_FRONTCACHE_TRACE_REQUEST)) // X-frontcache.debug.request.0, X-frontcache.debug.request.1.1.2.3.4.33, etc
				cleanupKeys.add(key);

		for (String removeKey : cleanupKeys)
			headers.remove(removeKey);
		
		return;
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
		int threadAmount = 2;
		softInvalidationExecutor = Executors.newFixedThreadPool(threadAmount); 

	}
	
	@Override
	public void destroy() {
		softInvalidationExecutor.shutdown();
	}

}
