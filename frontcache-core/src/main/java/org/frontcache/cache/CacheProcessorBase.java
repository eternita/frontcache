package org.frontcache.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.ThroughFrontcache;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = LoggerFactory.getLogger(getClass());

	@Override
	public WebResponse processRequest(String originUrlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;
		
		long lengthBytes = -1;
		WebResponse cachedWebResponse = new ThroughFrontcache(this, originUrlStr).execute();

		
		if (null == cachedWebResponse)
		{
			try
			{
				//TODO: remove me after migration from FC filter in coinshome.net (or can be used for back compatibility)
				requestHeaders.put(FCHeaders.X_AVOID_CHN_FRONTCACHE, "true");
				
				cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client);
				lengthBytes = cachedWebResponse.getContentLenth();

				// save to cache
				if (cachedWebResponse.isCacheable())
				{
					WebResponse copy4cache = cachedWebResponse.copy();
					
					copy4cache.getHeaders().remove("Set-Cookie");
					copy4cache.getHeaders().remove("Date");
					
					putToCache(originUrlStr, copy4cache); // put to cache copy
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
		
		
		RequestLogger.logRequest(originUrlStr, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes);
		
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
