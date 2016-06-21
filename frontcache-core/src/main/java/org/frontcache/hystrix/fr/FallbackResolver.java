package org.frontcache.hystrix.fr;

import org.frontcache.core.WebResponse;

public interface FallbackResolver {

	public WebResponse getFallback(String urlStr);

}
