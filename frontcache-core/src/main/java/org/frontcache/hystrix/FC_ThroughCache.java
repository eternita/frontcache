package org.frontcache.hystrix;

import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private final RequestContext context;
	
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache.class);

    public FC_ThroughCache(CacheProcessorBase cacheProcessorBase, String originUrlStr, RequestContext context) {
        //TODO: replace wit hdomainContext
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("coinshome.net"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Cache-Hits"))
        		);
        
        this.originUrlStr = originUrlStr;
        this.cacheProcessorBase = cacheProcessorBase;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
    	return cacheProcessorBase.getFromCacheImpl(originUrlStr);
    }
    
    @Override
    protected WebResponse getFallback() {
    	
		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();
		
		logger.error("FC_ThroughCache - ERROR FOR - " + originUrlStr + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context);
		
		return null;
    }
    
    
}