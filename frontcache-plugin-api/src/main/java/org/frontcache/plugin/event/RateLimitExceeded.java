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
 * A client went over a rate-limit bucket's limit - <b>once per client per window</b>, on the request that crossed
 * the limit, not once per request refused after it.
 *
 * @param requestId    the request's id, or null
 * @param bucket       the bucket counted in (a rule's own name unless the rule names a shared bucket)
 * @param rule         the rule whose {@code rate:} predicate counted the request
 * @param limit        requests allowed per window
 * @param windowMillis the window
 * @param uri          host + path + query of the request that crossed the limit, truncated
 * @param clientKey    the address counted; personal data, see {@link FcEvent}
 */
public record RateLimitExceeded(long time, String node, String site, String requestId, String bucket, String rule,
		int limit, long windowMillis, String uri, String clientKey) implements FcEvent {

	public static final String TYPE = "ratelimit.exceeded";

	@Override
	public String type() {
		return TYPE;
	}
}
