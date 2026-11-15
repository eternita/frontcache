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
package org.frontcache.plugin.event;

/**
 * A top-level request was served - the request log's line, as an event. <b>Sampled</b>: a node delivers each
 * request to a subscriber with the probability {@code front-cache.plugins.<id>.request-sample} (default
 * {@value #DEFAULT_SAMPLE}; {@code 1.0} delivers every request, and the node WARNs at start that it does). Includes
 * are not events of their own; a request a guard rule rejected or redirected is a {@link GuardMatched}.
 *
 * @param requestId     the request's id
 * @param method        the HTTP method
 * @param uri           host + path + query, truncated to {@value FcEvent#MAX_URI_LENGTH}
 * @param outcome       {@code success}, or {@code error} when a fallback was served
 * @param cacheable     whether the request went through the cache ({@code cacheable}) or straight to the origin
 * @param cache         {@code from-cache}, {@code dynamic} (the origin answered), {@code dynamic-soft} (a soft
 *                      refresh), or - for a request under {@code /fc-x/}, answered by neither - {@code plugin:<id>}
 *                      ({@code plugin:none} when no plugin owns the path)
 * @param runtimeMillis how long it took
 * @param lengthBytes   the response body's length
 * @param clientKey     the client's resolved address; personal data, see {@link FcEvent}
 * @param clientType    the bots.conf verdict's root - {@code bot} or {@code guest}; may be null
 * @param clientRule    the bots.conf rule that decided it, or null
 * @param userAgent     the User-Agent header, truncated to {@value FcEvent#MAX_URI_LENGTH}; may be null
 * @param fallbackReason why a fallback was served, when {@code outcome} is {@code error}; null otherwise
 * @param visitorType   the bots.conf verdict as written - a declared type, else {@code clientType}; may be null
 *                      (2.13.0)
 */
public record RequestCompleted(long time, String node, String site, String requestId, String method, String uri,
		String outcome, boolean cacheable, String cache, long runtimeMillis, long lengthBytes, String clientKey,
		String clientType, String clientRule, String userAgent, String fallbackReason, String visitorType) implements FcEvent {

	public static final String TYPE = "request.completed";

	/** The constructor before {@code visitorType}: a request of a root type, {@code visitorType} = {@code clientType}. */
	public RequestCompleted(long time, String node, String site, String requestId, String method, String uri,
			String outcome, boolean cacheable, String cache, long runtimeMillis, long lengthBytes, String clientKey,
			String clientType, String clientRule, String userAgent, String fallbackReason) {
		this(time, node, site, requestId, method, uri, outcome, cacheable, cache, runtimeMillis, lengthBytes, clientKey,
				clientType, clientRule, userAgent, fallbackReason, clientType);
	}

	/** The share of requests a subscriber receives unless {@code front-cache.plugins.<id>.request-sample} says. */
	public static final double DEFAULT_SAMPLE = 0.01;

	@Override
	public String type() {
		return TYPE;
	}
}
