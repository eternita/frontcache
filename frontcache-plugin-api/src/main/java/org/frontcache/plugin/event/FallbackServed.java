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
 * A fallback answered instead of the origin: a resilience command failed (or its circuit, bulkhead or pool refused
 * the call) and the node served what {@code fallbacks.conf} - or the default fallback - had for the URL. Follows the
 * command's {@link OriginError} when the command itself failed.
 *
 * @param requestId the request's id, or null (a management lookup has none)
 * @param command   the resilience command key, e.g. {@code Cache-Miss}, {@code Cache-Bypass}; {@code include}
 *                  for a sync include whose fallback the include processor resolved itself
 * @param failure   why the origin did not answer: {@code failure}, {@code timeout}, {@code short-circuited},
 *                  {@code semaphore-rejected}, {@code thread-pool-rejected}; or, for {@code include},
 *                  {@code timeout} (it did not resolve in time) or {@code fallback-failed} (its command's own
 *                  fallback failed)
 * @param uri       the URL the fallback stands in for, truncated to {@value FcEvent#MAX_URI_LENGTH}
 * @param cause     the class name of what the call threw, or null when it did not throw (a timeout, a refusal)
 */
public record FallbackServed(long time, String node, String site, String requestId, String command, String failure,
		String uri, String cause) implements FcEvent {

	public static final String TYPE = "fallback.served";

	@Override
	public String type() {
		return TYPE;
	}
}
