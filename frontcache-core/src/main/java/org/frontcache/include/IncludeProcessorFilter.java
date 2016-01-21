package org.frontcache.include;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.http.client.HttpClient;
import org.frontcache.core.FrontCacheException;

public interface IncludeProcessorFilter
{
	public String callInclude(String urlStr, MultiValuedMap<String, String> requestHeaders, HttpClient client) throws FrontCacheException;
}
