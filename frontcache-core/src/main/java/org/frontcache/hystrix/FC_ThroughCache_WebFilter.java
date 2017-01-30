package org.frontcache.hystrix;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.frontcache.wrapper.HttpResponseWrapperImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

public class FC_ThroughCache_WebFilter extends HystrixCommand<WebResponse> {


	String url = "open-circuit-default-key"; // when circuit is open - the value is not overriden during run() call
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_WebFilter.class);
	
    public FC_ThroughCache_WebFilter(RequestContext context) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(context.getDomainContext().getDomain()))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Origin-Hits"))
        		);
        
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpServletRequest httpRequest = context.getRequest();
		url = FCUtils.getRequestURL(httpRequest);
		HttpServletResponse httpResponse = context.getResponse();
		FilterChain chain = context.getFilterChain();

		FrontCacheHttpResponseWrapper wrappedResponse = new HttpResponseWrapperImpl(httpResponse);
		
		try {
			chain.doFilter(httpRequest, wrappedResponse); // run request to origin
			
			WebResponse webResponse = FCUtils.httpResponse2WebComponent(url, wrappedResponse);
			return webResponse;
			
		} catch (IOException | ServletException e) {
			logger.error("Can't read from " + url, e);
			throw new FrontCacheException("FilterChain exception", e);
		} 
		
    }
    
    @Override
    protected WebResponse getFallback() {
		context.setHystrixError();
		
		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();
			
		logger.error("FC_ThroughCache_WebFilter - ERROR FOR - " + url + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context);
		
		WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(context.getDomainContext(), this.getClass().getName(), url);
		return webResponse;
    }
    
	
}