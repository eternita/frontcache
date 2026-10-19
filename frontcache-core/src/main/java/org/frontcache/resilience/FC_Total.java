/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.resilience;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.frontcache.warmer.WarmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * All requests through Frontcache engine
 * Must use SEMAPHORE to access the same thread
 *
 */
public class FC_Total extends FcCommand<Object> {

	/**
	 * Requests from a client - what an operator reads as "how much traffic is this node taking".
	 *
	 * Kept as an alias of {@link FcCommandKeys#INPUT_REQUEST} rather than replaced by it: this is
	 * where the key has always been referenced from, and the indirection costs nothing.
	 */
	public static final String COMMAND_KEY_INPUT_REQUEST = FcCommandKeys.INPUT_REQUEST;

	/** Requests that are a Frontcache include arriving back through the front door. */
	public static final String COMMAND_KEY_INCLUDE_REQUEST = FcCommandKeys.INCLUDE_REQUEST;

	/** The cache warmer's own requests - see {@code org.frontcache.warmer.WarmRequest}. */
	public static final String COMMAND_KEY_WARM_REQUEST = FcCommandKeys.WARM_REQUEST;

	private final FrontCacheEngine frontCacheEngine;
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_Total.class);

    public FC_Total(FrontCacheEngine frontCacheEngine, RequestContext context) {

        super(Setter
                .withGroupKey(context.getDomain())
                .andCommandKey(commandKeyFor(context))
                );
        this.frontCacheEngine = frontCacheEngine;
        this.context = context;

    }

	/**
	 * Which circuit this request belongs on: a client request, or an include coming back round.
	 *
	 * {@code Input-Request} is the dashboard's headline number, and it was counting both. An include
	 * resolved by {@code fetchIncludeFromOrigin} is an HTTP request to the {@code Host} header - back
	 * through the CDN and in at this node's own front door - so it arrives here as an ordinary request
	 * and was tallied as client traffic. On fc-edge that read 1200/s against real client traffic of
	 * ~11/s. Fixing the cache keys removed most of the volume, but not the miscounting: every include
	 * that misses cache still re-enters, and in **filter mode** or a chained topology (the origin
	 * itself behind Frontcache) it re-enters regardless of how includes are routed. So the count is
	 * only correct if the two are separated here, at the point where the request type is already known.
	 *
	 * {@code init()} has classified the request before this runs - the same classification the request
	 * log prints as {@code toplevel} / {@code include} / {@code include-async}, derived from whether
	 * the caller sent {@code x-frontcache-request-id}. No new header, no new plumbing.
	 *
	 * An unset type means the context never went through {@code init()}, which is not something a real
	 * request can do; it counts as an input request because that is the conservative answer - it keeps
	 * an unknown request visible in the headline number rather than filing it away as an include.
	 */
	static String commandKeyFor(RequestContext context) {

		// Checked first. A warm request is top-level by construction (the warmer sends no request id),
		// and counting it as Input-Request would read a warm run as a traffic spike and take bulkhead
		// permits from real visitors - the Include-Request argument again.
		if (context.isWarmRequest())
			return COMMAND_KEY_WARM_REQUEST;

		String requestType = context.getRequestType();

		if (FCHeaders.COMPONENT_INCLUDE.equals(requestType) || FCHeaders.COMPONENT_ASYNC_INCLUDE.equals(requestType))
			return COMMAND_KEY_INCLUDE_REQUEST;

		return COMMAND_KEY_INPUT_REQUEST;
	}

    @Override
    protected Object run() throws Exception {
    	frontCacheEngine.processRequestInternal(context);
    	return null;
    }

    @Override
    protected Object getFallback() {
		HttpServletRequest httpRequest = context.getRequest();
		HttpServletResponse httpResponse = context.getResponse();
		String url = FCUtils.getRequestURL(httpRequest);

		try {
			context.setFallbackUsed(FallbackLogger.failureType(getExecutionEvents()));
			WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(this.getClass().getName(), url);

			if (context.isWarmRequest() && !httpResponse.isCommitted())
				httpResponse.setHeader(FCHeaders.X_FRONTCACHE_WARM_RESULT, WarmRequest.RESULT_FALLBACK);

			if (null != webResponse)
			{
				FCUtils.applyFallbackHeaders(httpResponse, webResponse);
				httpResponse.getOutputStream().write(webResponse.getContent());
			}

			String failedExceptionMessage = "";
			if (null != getFailedExecutionException())
				failedExceptionMessage += getFailedExecutionException().getMessage();

			logger.error("FC-Total - ERROR FOR - " + url + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context, getFailedExecutionException());
		} catch (Exception e) {
			e.printStackTrace();
		}
        return null;
    }
}