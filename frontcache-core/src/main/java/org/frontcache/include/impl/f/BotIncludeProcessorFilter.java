package org.frontcache.include.impl.f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.WebResponse;
import org.frontcache.include.IncludeProcessorFilter;

/**
 * 
 * TODO: make it configurable and optional
 *
 */
public class BotIncludeProcessorFilter implements IncludeProcessorFilter
{
	private Map<String, String> cache = new ConcurrentHashMap<String, String>();

	// TODO: move to config file
	private String[] botUserAgentKeywords = new String[] {
			"Googlebot", "msnbot", "bingbot", "YandexBot", "YandexDirect", "Baiduspider", "Yahoo! Slurp",
			"majestic12", "Mail.RU_Bot", "EasouSpider", "voilabot", "AhrefsBot", "orangebot", "SemrushBot"};
	

			
	public BotIncludeProcessorFilter() {
		super();
	}

	public String callInclude(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException
	{
		if (isBot(requestHeaders))
		{
			requestHeaders.remove("cookie");
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
//		System.out.println(requestHeaders);
		
		if (null != requestHeaders.get("user-agent"))
		{
			for (String userAgent : requestHeaders.get("user-agent"))
				if (isBot(userAgent))
					return true;
		}
		return false;
	}
	
	private boolean isBot(String userAgent)
	{
		for (String botKeyword : botUserAgentKeywords)
			if (userAgent.contains(botKeyword))
				return true;
			
		return false;
	}
	
}