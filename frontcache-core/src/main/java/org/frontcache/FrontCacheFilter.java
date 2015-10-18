package org.frontcache;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorManager;
import org.frontcache.reqlog.RequestLogger;
import org.frontcache.wrapper.FrontCacheHttpResponseWrapper;
import org.frontcache.wrapper.HttpResponseWrapperImpl;



public class FrontCacheFilter implements Filter {

	private Logger logger = Logger.getLogger(getClass().getName());
	
	private String appOriginBaseURL = FCConfig.getProperty("app_origin_base_url");
	
	private final String UTF8 = "UTF-8";
	
	private IncludeProcessor includeProcessor = null;
	
	private CacheProcessor cacheProcessor = null; // can be null (no caching)
	
	
	@Override
	public void init(FilterConfig arg0) throws ServletException {

		cacheProcessor = CacheManager.getInstance();
		
		includeProcessor = IncludeProcessorManager.getInstance();
			
		includeProcessor.setCacheProcessor(cacheProcessor);
		
		return;
	}

	@Override
	public void destroy() {

		if (null != cacheProcessor)
			cacheProcessor.destroy();
		
		if (null != includeProcessor)
			includeProcessor.destroy();
		
		return;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		
		HttpServletRequest httpRequest = (HttpServletRequest) request;	
		FrontCacheHttpResponseWrapper wrappedResponse = getHttpResponseWrapper((HttpServletResponse) response);
		
		
		// if no appOriginBaseURL -> use current app
		if (null == appOriginBaseURL)
			appOriginBaseURL = getDefaultOriginBaseURL(httpRequest);
	
		String content = null;
		if (null != cacheProcessor)
		{
			content = cacheProcessor.processCacheableRequest(httpRequest, wrappedResponse, chain);
		} else {
			long start = System.currentTimeMillis();
			boolean isRequestDynamic = true;
			
			chain.doFilter(request, wrappedResponse); // run request to origin
			content = wrappedResponse.getContentString();
			
			RequestLogger.logRequest(httpRequest.getRequestURL().toString(), isRequestDynamic, System.currentTimeMillis() - start);
			
		}

		content = includeProcessor.processIncludes(content, appOriginBaseURL, httpRequest);
		
		// populate input response		
		populateOriginalResponse(response, wrappedResponse, content);
		return;
	}

	/**
	 * 
	 * @param httpResponse
	 * @return
	 */
	private FrontCacheHttpResponseWrapper getHttpResponseWrapper(HttpServletResponse httpResponse)
	{
		FrontCacheHttpResponseWrapper wrappedResponse = new HttpResponseWrapperImpl(httpResponse);
		return wrappedResponse;
	}

	
	/**
	 * 
	 * @param originalResponse
	 * @param wrappedResponse
	 * @param content
	 * @throws IOException
	 */
	private void populateOriginalResponse(ServletResponse originalResponse, FrontCacheHttpResponseWrapper wrappedResponse, String content) throws IOException
	{
		// populate input response		
		byte[] data = content.getBytes();
		originalResponse.getOutputStream().write(data);
		originalResponse.setContentLengthLong(data.length);

		originalResponse.setCharacterEncoding(UTF8); // ? support other encodings
		originalResponse.setContentType(wrappedResponse.getContentType());
	}

	
	/**
	 * Extracts origin base url from request
	 * 
	 * http://localhost:8080/demo-fc/hello-world.jsp -> http://localhost:8080/demo-fc
	 * 
	 * @param httpRequest
	 * @return
	 */
	private String getDefaultOriginBaseURL(HttpServletRequest httpRequest)
	{
		
		String protocol = httpRequest.getProtocol();
		protocol = protocol.substring(0, httpRequest.getProtocol().indexOf("/"));
		
		return protocol + "://" + httpRequest.getServerName() + ":" + httpRequest.getServerPort() + httpRequest.getContextPath();
	}


	
}

