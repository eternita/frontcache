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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.client.HttpClient;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.reqlog.RequestLogger;

public class NoCacheProcessor implements CacheProcessor {

	@Override
	public WebResponse processRequest(String originUrlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;
		long lengthBytes = -1;
		
		WebResponse cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client, context);

		lengthBytes = cachedWebResponse.getContentLenth();

		RequestLogger.logRequest(originUrlStr, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);

		return cachedWebResponse;
	}

	@Override
	public void init(Properties properties) {
		// TODO Auto-generated method stub

	}

	@Override
	public void destroy() {
		// TODO Auto-generated method stub

	}

	@Override
	public void putToCache(String domain, String url, WebResponse component) {
		// TODO Auto-generated method stub

	}

	@Override
	public WebResponse getFromCache(String url) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void removeFromCache(String domain, String filter) {
		// TODO Auto-generated method stub

	}

	@Override
	public void removeFromCacheAll(String domain) {
		// TODO Auto-generated method stub

	}

	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = new HashMap<String, String>();
		status.put("impl", this.getClass().getName());

		return status;
	}

	@Override
	public List<String> getCachedKeys() {
		return new ArrayList<String>();
	}

	@Override
	public void doSoftInvalidation(String currentRequestURL, String originUrlStr,
			Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) {
	
		return;
	}

	@Override
	public void patch() {
		// TODO Auto-generated method stub
		
	}

}



