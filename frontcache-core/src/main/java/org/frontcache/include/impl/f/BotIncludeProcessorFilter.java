package org.frontcache.include.impl.f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;
import org.frontcache.include.IncludeProcessorFilter;

public class BotIncludeProcessorFilter implements IncludeProcessorFilter
{
	private Map<String, String> cache = new ConcurrentHashMap<String, String>();

	public String callInclude(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException
	{
		if (isBot(requestHeaders))
		{
			// work with cache			
			String content = cache.get(urlStr);
			if (null != content)
				return content;

			// recursive call to FCServlet
			WebResponse webResponse = FCUtils.dynamicCall(urlStr, requestHeaders, client);
			content = webResponse.getContent();
			
			cache.put(urlStr, content);
			
			return content;

		} else {
			// recursive call to FCServlet
			WebResponse webResponse = FCUtils.dynamicCall(urlStr, requestHeaders, client);
			return webResponse.getContent();
		}
		
	}
	
	private boolean isBot(MultiValuedMap<String, String> requestHeaders)
	{
		System.out.println(requestHeaders);
		return false;
	}
}