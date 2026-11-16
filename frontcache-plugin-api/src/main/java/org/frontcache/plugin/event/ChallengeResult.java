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
 * A challenge was answered: the plugin asked the node to sign a pass ({@code pass}), or reported that the visitor
 * failed ({@code fail}) or that it could not tell ({@code error} - a third-party verifier unreachable, which is never a
 * pass). What a "pass rate fell" alert is built on.
 *
 * @param requestId the id of the request that answered it
 * @param provider  the challenge provider, e.g. {@code pow}
 * @param plugin    the plugin that serves it
 * @param outcome   {@value #PASS}, {@value #FAIL} or {@value #ERROR}
 * @param clientKey the address the node resolved for the visitor; personal data, see {@link FcEvent}
 */
public record ChallengeResult(long time, String node, String site, String requestId, String provider, String plugin,
		String outcome, String clientKey) implements FcEvent {

	public static final String TYPE = "challenge.result";

	public static final String PASS = "pass";
	public static final String FAIL = "fail";
	public static final String ERROR = "error";

	@Override
	public String type() {
		return TYPE;
	}
}
