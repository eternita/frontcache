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

public class FC_ThroughCache_WebFilter extends HystrixCommand<WebResponse> {


	String url = "nothing";
	private final RequestContext context;
    public FC_ThroughCache_WebFilter(RequestContext context) {
        
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("Frontcache"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("FC_ThroughCache_WebFilter"))
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