package org.frontcache.hystrix;

import org.frontcache.cache.CacheProcessor;
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
	private final CacheProcessor cache;

    public FC_ThroughCache(CacheProcessor cache, String originUrlStr) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("FC_ThroughCache"))
        		);
        
        this.originUrlStr = originUrlStr;
        this.cache = cache;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
    	return cache.getFromCache(originUrlStr);
    }
    
}