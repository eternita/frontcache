package org.frontcache.hystrix;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.frontcache.wrapper.HttpResponseWrapperImpl;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;

public class ThroughFrontcache_WebFilter extends HystrixCommand<WebResponse> {


	String url = "nothing";
    public ThroughFrontcache_WebFilter() {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("ThroughFrontcache_WebFilter"))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
        
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		RequestContext context = RequestContext.getCurrentContext();
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
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new FrontCacheException("FilterChain exception", e);
		} 
		
    }
    
    @Override
    protected WebResponse getFallback() {
        return fallbackForWebComponent(this.url);
    }
    
	
	private WebResponse fallbackForWebComponent(String urlStr)
	{
		byte[] outContentBody = ("Fallabck for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}
    
}