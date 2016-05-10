package org.frontcache.hystrix;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

/**
 * 
 * All requests through Frontcache engine
 * Must use SEMAPHORE to access the same thread
 *
 */
public class FC_Total extends HystrixCommand<Object> {

	private final FrontCacheEngine frontCacheEngine;
	private final RequestContext context;
	
    public FC_Total(FrontCacheEngine frontCacheEngine, RequestContext context) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("FC_Total"))
                );
        this.frontCacheEngine = frontCacheEngine;
        this.context = context;
        
    }

    @Override
    protected Object run() throws Exception {
    	frontCacheEngine.processRequestInternal(context);
    	return null;
    }
    
    @Override
    protected Object getFallback() {
    	
		try {
			HttpServletRequest httpRequest = context.getRequest();
			String url = FCUtils.getRequestURL(httpRequest);
			HttpServletResponse httpResponse = context.getResponse();
			httpResponse.getWriter().write("FC - ORIGIN ERROR - " + url);
			httpResponse.setContentType("text/plain");
		} catch (Exception e) {
			e.printStackTrace();
		}
        return null;
    }
}