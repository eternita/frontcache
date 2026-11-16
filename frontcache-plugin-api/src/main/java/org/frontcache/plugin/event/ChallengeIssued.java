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
 * A {@code challenge <provider>} guard rule sent a visitor to the provider's page (a {@code 302} to
 * {@code /fc-x/<plugin>/}). A rule whose provider is absent acts as a dry run (a {@link GuardMatched} with
 * disposition {@code dry-run}) or as its {@code else} action, and is not this event.
 *
 * @param requestId  the request's id
 * @param rule       the guard rule's name
 * @param provider   the challenge provider, e.g. {@code pow}
 * @param plugin     the plugin that serves it
 * @param uri        host + path + query as the request carried them, truncated to {@value FcEvent#MAX_URI_LENGTH}
 * @param clientType the bots.conf verdict's root - {@code bot} or {@code guest}; may be null
 * @param clientKey  the address the node resolved for the visitor; personal data, see {@link FcEvent}
 * @param visitorType the bots.conf verdict as written - a declared type, else {@code clientType}; may be null (2.13.0)
 */
public record ChallengeIssued(long time, String node, String site, String requestId, String rule, String provider,
		String plugin, String uri, String clientType, String clientKey, String visitorType) implements FcEvent {

	public static final String TYPE = "challenge.issued";

	/** The constructor before {@code visitorType}: a request of a root type, {@code visitorType} = {@code clientType}. */
	public ChallengeIssued(long time, String node, String site, String requestId, String rule, String provider,
			String plugin, String uri, String clientType, String clientKey) {
		this(time, node, site, requestId, rule, provider, plugin, uri, clientType, clientKey, clientType);
	}

	@Override
	public String type() {
		return TYPE;
	}
}
