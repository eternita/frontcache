package org.frontcache.hystrix;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;

/**
 * 
 * All GET requests (cached & not)
 *
 */
public class ThroughFrontcache extends HystrixCommand<WebResponse> {

	private final String originUrlStr;
	private final CacheProcessor cache;

    public ThroughFrontcache(CacheProcessor cache, String originUrlStr) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("ThroughFrontcache"))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
        
        this.originUrlStr = originUrlStr;
        this.cache = cache;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
    	return cache.getFromCache(originUrlStr);
    }
    
}