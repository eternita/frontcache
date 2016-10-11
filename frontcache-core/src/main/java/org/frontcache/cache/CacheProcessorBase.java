package org.frontcache.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
			FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE,
			FCHeaders.X_FRONTCACHE_COMPONENT_TAGS,
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

		
		if (null == cachedWebResponse)
		{
			try
			{
				cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client, context);
				lengthBytes = cachedWebResponse.getContentLenth();

				// save to cache
				if (cachedWebResponse.isCacheable())
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
	
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = new HashMap<String, String>();
		status.put("impl", this.getClass().getName());

		return status;
	}
	


	@Override
	public void init(Properties properties) {
		
	}	

}
