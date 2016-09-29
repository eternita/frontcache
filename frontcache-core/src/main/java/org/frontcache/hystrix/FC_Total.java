package org.frontcache.hystrix;

import javax.servlet.http.HttpServletRequest;

import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private Logger logger = LoggerFactory.getLogger(FC_Total.class);
	
    public FC_Total(FrontCacheEngine frontCacheEngine, RequestContext context) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Input-Requests"))
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
		HttpServletRequest httpRequest = context.getRequest();
		String url = FCUtils.getRequestURL(httpRequest);
    	logger.error("FC-Total - ERROR - " + url);
    	context.setHystrixError();
    	// !!! Do nothing inside here
    	
//		try {
//			HttpServletRequest httpRequest = context.getRequest();
//			String url = FCUtils.getRequestURL(httpRequest);
//			HttpServletResponse httpResponse = context.getResponse();
//			
//			context.setHystrixError();
//			logger.error("FC-Total - ORIGIN ERROR - " + url);
//			
//			httpResponse.getWriter().write("FC-Input-Requests - ORIGIN ERROR - " + url);
//			httpResponse.setContentType("text/plain");
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
        return null;
    }
}