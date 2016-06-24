package org.frontcache.hystrix.fr;

import org.apache.http.client.HttpClient;
import org.frontcache.core.WebResponse;

public interface FallbackResolver {
	
	public void init(HttpClient client);

	public WebResponse getFallback(String urlStr);

}
