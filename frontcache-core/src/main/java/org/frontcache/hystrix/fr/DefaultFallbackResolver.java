package org.frontcache.hystrix.fr;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.WebResponse;

public class DefaultFallbackResolver implements FallbackResolver {

	public DefaultFallbackResolver() {
	}
	
	public WebResponse getFallback(String urlStr)
	{
		byte[] outContentBody = ("Default Fallabck for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}

}
