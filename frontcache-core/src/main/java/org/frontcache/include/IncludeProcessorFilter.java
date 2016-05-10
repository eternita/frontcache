package org.frontcache.include;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

public interface IncludeProcessorFilter
{
	public WebResponse callInclude(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException;
}
