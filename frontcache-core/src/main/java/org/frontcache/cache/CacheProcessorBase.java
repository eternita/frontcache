package org.frontcache.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
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
		WebResponse cachedWebResponse = new FC_ThroughCache(this, originUrlStr).execute();

		
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
		
		
		RequestLogger.logRequest(originUrlStr, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);
		
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
