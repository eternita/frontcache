package org.frontcache.hystrix;

import org.frontcache.FrontCacheEngine;
import org.frontcache.core.WebResponse;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;

/**
 * 
 * All requests through Frontcache engine
 * Must use SEMAPHORE to access the same thread
 *
 */
public class FC_Total extends HystrixCommand<Object> {

	private final FrontCacheEngine frontCacheEngine;
	
    public FC_Total(FrontCacheEngine frontCacheEngine) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("FC_Total"))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionTimeoutInMilliseconds(2000))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))
                );
        this.frontCacheEngine = frontCacheEngine;
        
    }

    @Override
    protected Object run() throws Exception {
    	frontCacheEngine.processRequestInternal();
    	return null;
    }
    
    @Override
    protected Object getFallback() {
    	System.out.println("FC_Total - fallback");
        return null;
    }
}