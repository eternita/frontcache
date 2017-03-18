/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

public interface CacheProcessor {

	public final static String CACHED_ENTRIES = "cached-entries";
	
	public final static long NO_CACHE = 0;
	
	public final static long CACHE_FOREVER = -1;

	public void init(Properties properties);
	
	public void destroy();
	
	public void putToCache(String domain, String url, WebResponse component);
	
	public WebResponse getFromCache(String url);
	
	public void removeFromCache(String domain, String filter);
	
	public void removeFromCacheAll(String domain);
	
	public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException;
	
	public Map<String, String> getCacheStatus();
	
	public List<String> getCachedKeys();
	
	public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context);
	
	public void patch();	
	
}
