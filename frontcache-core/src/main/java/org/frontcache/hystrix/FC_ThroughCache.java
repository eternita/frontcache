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
package org.frontcache.hystrix;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

/**
 *
 * All GET requests which flows through cache (cached & not)
 *
 */
public class FC_ThroughCache extends HystrixCommand<WebResponse> {

	private final String originUrlStr;
	private final CacheProcessorBase cacheProcessorBase;
	private final RequestContext context;

	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache.class);

    public FC_ThroughCache(CacheProcessorBase cacheProcessorBase, String originUrlStr, RequestContext context) {
        // group by domain (matches FC_Total / FC_BypassCache / RequestLogger).
        // context can be null for admin (FrontCacheIOServlet) lookups -> fall back to default domain
        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(
                		null != context
                                ? context.getDomainContext().getDomain()
                                : FCConfig.getProperty("front-cache.origin-host", "localhost")))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Cache-Hits"))
        		);

        this.originUrlStr = originUrlStr;
        this.cacheProcessorBase = cacheProcessorBase;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
    	return cacheProcessorBase.getFromCacheImpl(originUrlStr);
    }

    @Override
    protected WebResponse getFallback() {

		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();

		logger.error("FC_ThroughCache - ERROR FOR - " + originUrlStr + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context);

		return null;
    }


}