package org.frontcache.hystrix.fr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.http.client.HttpClient;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.FallbackLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultFallbackResolver implements FallbackResolver {

	private static Logger fallbackLogger = LoggerFactory.getLogger(FallbackLogger.class);
	
	public DefaultFallbackResolver() {
	}
	
	public WebResponse getFallback(String fallbackSource, String urlStr)
	{
		fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + " default | turn on another FallbackResolver implementation to get better fallbacks | " + urlStr);
		
		byte[] outContentBody = ("Default Fallback for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody, CacheProcessor.NO_CACHE);
		webResponse.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		
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
