package org.frontcache.hystrix.fr;

import java.util.List;

import org.apache.http.client.HttpClient;
import org.frontcache.core.WebResponse;

public interface FallbackResolver {
	
	public void init(HttpClient client);

	public WebResponse getFallback(String fallbackSource, String urlStr);

	public List<FallbackConfigEntry> getFallbackConfigs();
}
