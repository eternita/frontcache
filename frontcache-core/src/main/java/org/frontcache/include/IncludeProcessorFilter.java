package org.frontcache.include;

import java.util.List;
import java.util.Map;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

public interface IncludeProcessorFilter
{
	public WebResponse callInclude(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException;
}
