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
 * An origin call failed: it threw (a connect failure, a read error), answered with a status outside 2xx-4xx, timed
 * out, or was refused by its command's circuit, bulkhead or pool. One event per failed call, whether or not a
 * fallback was then served ({@link FallbackServed} says when one was). A failure that is only an inner command's
 * failure seen again by the command around it is not reported twice.
 *
 * @param requestId     the request's id, or null
 * @param command       the resilience command key, e.g. {@code Cache-Miss}, {@code Cache-Bypass}
 * @param failure       {@code failure}, {@code timeout}, {@code short-circuited}, {@code semaphore-rejected} or
 *                      {@code thread-pool-rejected}
 * @param status        the origin's status when it answered one outside 2xx-4xx; 0 otherwise
 * @param uri           the URL called, truncated to {@value FcEvent#MAX_URI_LENGTH}
 * @param cause         the class name of what the call threw, or null when it did not throw (a timeout, a
 *                      refusal, a 5xx answered on a call that relays it)
 * @param latencyMillis how long the call took to fail
 */
public record OriginError(long time, String node, String site, String requestId, String command, String failure,
		int status, String uri, String cause, long latencyMillis) implements FcEvent {

	public static final String TYPE = "origin.error";

	@Override
	public String type() {
		return TYPE;
	}
}
