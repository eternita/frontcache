package org.frontcache.hystrix.fr;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.HttpClient;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.WebResponse;

public class DefaultFallbackResolver implements FallbackResolver {

	public DefaultFallbackResolver() {
	}
	
	public WebResponse getFallback(String urlStr)
	{
		byte[] outContentBody = ("Default Fallback for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		String contentType = "text/html";
		webResponse.setContentType(contentType);
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}

	@Override
	public void init(HttpClient client) {
	}

	@Override
	public List<FallbackConfigEntry> getFallbackConfigs() {
		return new ArrayList<FallbackConfigEntry>();
	}

}
