package org.frontcache.cache;

import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.WebResponse;
import org.frontcache.reqlog.RequestLogger;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = Logger.getLogger(getClass().getName());

	@Override
	public WebResponse processRequest(String originUrlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws Exception {

		long start = System.currentTimeMillis();
		boolean isRequestDynamic = true;
		
		long lengthBytes = -1;
		WebResponse cachedWebComponent = getFromCache(originUrlStr);
		
		if (null == cachedWebComponent)
		{
			try
			{
				cachedWebComponent = FCUtils.dynamicCall(originUrlStr, requestHeaders, client);
				lengthBytes = cachedWebComponent.getContentLenth();

				// save to cache
				if (cachedWebComponent.isCacheable())
					putToCache(originUrlStr, cachedWebComponent);

			} catch (Exception ex)
			{
				ex.printStackTrace();
			}
				
		} else {
			isRequestDynamic = false;
		}
		
		
		RequestLogger.logRequest(originUrlStr, isRequestDynamic, System.currentTimeMillis() - start, lengthBytes);
		
		return cachedWebComponent;
	}	
	
/*	
	public String processCacheableRequest(HttpServletRequest httpRequest, HttpServletResponse response, String urlStr) throws IOException, ServletException 
	{
		long start = System.currentTimeMillis();
		boolean isRequestDynamic = true;

		WebResponse cachedWebComponent = getFromCache(urlStr);
		
		String content = null;
		long lengthBytes = -1;
		
		if (null == cachedWebComponent)
		{
			isRequestDynamic = true;
			
			// do dynamic call 
			Map<String, Object> respMap = FCUtils.dynamicCall(urlStr, httpRequest, response);

			String contentType = (String) respMap.get("contentType");
	        content = (String) respMap.get("dataStr");
	        String contentLenghtStr = (String) respMap.get("Content-Length");
	        if (null != contentLenghtStr)
	        	lengthBytes = Long.parseLong(contentLenghtStr);
	        
			if (null != contentType && -1 < contentType.indexOf("text"))
			{
				cachedWebComponent = FCUtils.parseWebComponent(urlStr, content);
				// remove custom component tag from response string
				content = cachedWebComponent.getContent();
				
				// save to cache
				if (cachedWebComponent.isCacheable())
				{
					cachedWebComponent.setContentType(contentType);
					putToCache(urlStr, cachedWebComponent);
				}
			}
			
		} else {
			
			isRequestDynamic = false;
			content = cachedWebComponent.getContent();
			response.setContentType(cachedWebComponent.getContentType());
			lengthBytes = 2*content.length();
		}

		
		RequestLogger.logRequest(urlStr, isRequestDynamic, System.currentTimeMillis() - start, lengthBytes);
		return content;
	}
	
	public String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException 
	{
		long start = System.currentTimeMillis();
		boolean isRequestDynamic = true;

		String urlStr = FCUtils.getRequestURL(httpRequest);
		
		WebResponse cachedWebComponent = getFromCache(urlStr);
		
		String content = null;

		
		if (null == cachedWebComponent)
		{
			isRequestDynamic = true;
			
			chain.doFilter(httpRequest, response); // run request to origin
						
			content = response.getContentString();
			
			cachedWebComponent = FCUtils.parseWebComponent(urlStr, content);
			// remove custom component tag from response string
			content = cachedWebComponent.getContent();
			
			// save to cache
			if (cachedWebComponent.isCacheable())
			{
				cachedWebComponent.setContentType(response.getContentType());
				putToCache(urlStr, cachedWebComponent);
			}
			
		} else {
			
			isRequestDynamic = false;
			content = cachedWebComponent.getContent();
			response.setContentType(cachedWebComponent.getContentType());
		}

		RequestLogger.logRequest(urlStr, isRequestDynamic, System.currentTimeMillis() - start, (null == content) ? -1 : content.length());
		return content;
	}
//*/	
	
//	private String processCacheableRequest(HttpServletRequest httpRequest, FrontCacheHttpResponseWrapper response, FilterChain chain) throws IOException, ServletException 
//	{
//		long start = System.currentTimeMillis();
//		boolean isRequestDynamic = true;
//
//		String urlStr = FCUtils.getRequestURL(httpRequest);
//		
//		WebResponse cachedWebComponent = getFromCache(urlStr);
//		
//		String content = null;
//
//		
//		if (null == cachedWebComponent)
//		{
//			isRequestDynamic = true;
//			
//			chain.doFilter(httpRequest, response); // run request to origin
//						
//			content = response.getContentString();
//			
//			cachedWebComponent = FCUtils.parseWebComponent(content);
//			// remove custom component tag from response string
//			content = cachedWebComponent.getContent();
//			
//			// save to cache
//			if (cachedWebComponent.isCacheable())
//			{
//				cachedWebComponent.setContentType(response.getContentType());
//				putToCache(urlStr, cachedWebComponent);
//			}
//			
//		} else {
//			
//			isRequestDynamic = false;
//			content = cachedWebComponent.getContent();
//			response.setContentType(cachedWebComponent.getContentType());
//		}
//
//		RequestLogger.logRequest(urlStr, isRequestDynamic, System.currentTimeMillis() - start, (null == content) ? -1 : content.length());
//		return content;
//	}
	


	@Override
	public void init(Properties properties) {
		
	}	

}
