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
package org.frontcache.hystrix.fr;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.http.client.HttpClient;
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.FallbackLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultFallbackResolver implements FallbackResolver {

	private static Logger fallbackLogger = LoggerFactory.getLogger(FallbackLogger.class);
	
	public DefaultFallbackResolver() {
	}
	
	public WebResponse getFallback(DomainContext domain, String fallbackSource, String urlStr)
	{
		fallbackLogger.trace(FallbackLogger.logTimeDateFormat.format(new Date()) + " | " + fallbackSource + " default | turn on another FallbackResolver implementation to get better fallbacks | " + urlStr);
		
		byte[] outContentBody = ("Default Fallback for " + urlStr).getBytes();

		WebResponse webResponse = new WebResponse(urlStr, outContentBody);
		webResponse.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		
		int httpResponseCode = 200;
		webResponse.setStatusCode(httpResponseCode);

		return webResponse;
	}

	@Override
	public void init(HttpClient client) {
	}

	@Override
	public Map <String, Set<FallbackConfigEntry>> getFallbackConfigs() {
		return new HashMap<String, Set<FallbackConfigEntry>>();
	}

}
