package org.frontcache.hystrix;

import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

/**
 * 
 * All GET requests which flows through cache (cached & not)
 *
 */
public class FC_ThroughCache extends HystrixCommand<WebResponse> {

	private final String originUrlStr;
	private final CacheProcessorBase cacheProcessorBase;

    public FC_ThroughCache(CacheProcessorBase cacheProcessorBase, String originUrlStr) {
        //TODO: replace wit hdomainContext
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("coinshome.net"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Cache-Hits"))
        		);
        
        this.originUrlStr = originUrlStr;
        this.cacheProcessorBase = cacheProcessorBase;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
    	return cacheProcessorBase.getFromCacheImpl(originUrlStr);
    }
    
}