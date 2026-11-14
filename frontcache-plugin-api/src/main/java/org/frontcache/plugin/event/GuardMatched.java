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
 * A guard rule matched a request and acted on it ({@code rejected} / {@code redirected} / {@code challenged} - a
 * {@code challenge} rule's redirect to its provider's page, also a {@link ChallengeIssued}), or matched as a dry run
 * ({@code dry-run}, status 0 - a challenge rule whose provider is absent included). An {@code allow} rule's match is
 * not an event: it is the request being served.
 *
 * @param requestId   the request's id, or null
 * @param rule        the rule's name
 * @param disposition {@code rejected}, {@code redirected}, {@code challenged} or {@code dry-run}
 * @param status      the status the node answered with; 0 for a dry run
 * @param uri         host + path + query as the request carried them, truncated to {@value FcEvent#MAX_URI_LENGTH}
 * @param clientType  the bots.conf verdict - {@code bot}, {@code guest}, ...; may be null
 * @param clientKey   the address the rate limiter counts this client by; personal data, see {@link FcEvent}
 */
public record GuardMatched(long time, String node, String site, String requestId, String rule, String disposition,
		int status, String uri, String clientType, String clientKey) implements FcEvent {

	public static final String TYPE = "guard.matched";

	@Override
	public String type() {
		return TYPE;
	}
}
