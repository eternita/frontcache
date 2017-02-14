package org.frontcache.hystrix;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
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
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(context.getDomainContext().getDomain()))
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
		HttpServletResponse httpResponse = context.getResponse();
		String url = FCUtils.getRequestURL(httpRequest);
    	
		try {
			context.setHystrixFallback();
			WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(context.getDomainContext(), this.getClass().getName(), url);
			if (null != webResponse)
			{
				httpResponse.getOutputStream().write(webResponse.getContent());
				httpResponse.setContentType(webResponse.getHeader(FCHeaders.CONTENT_TYPE));
			}
			
			String failedExceptionMessage = "";
			if (null != getFailedExecutionException())
				failedExceptionMessage += getFailedExecutionException().getMessage();
			
			logger.error("FC-Total - ERROR FOR - " + url + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context);
		} catch (Exception e) {
			e.printStackTrace();
		}
        return null;
    }
}