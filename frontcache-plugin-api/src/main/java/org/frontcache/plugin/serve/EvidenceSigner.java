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
package org.frontcache.plugin.serve;

import java.time.Duration;

/**
 * The node's signer of <b>evidence cookies</b> - what the core's {@code cookie-hmac:<name>} predicate verifies. The
 * evidence pattern in one line: the core decides who must prove something and checks the proof; a plugin only helps
 * the visitor produce it. A challenge plugin verifies a solution, then asks this signer for a cookie and sets it with
 * {@link PluginResponse#cookie}; whether the next request passes is decided by a guard rule, in the core, with a key
 * the plugin never sees.
 *
 * <p>Handed to a serving plugin by {@code PluginContext.evidenceSigner()} - only when the node has a cookie secret
 * ({@code front-cache.visitor.cookie-secret}) and the plugin's manifest names at least one cookie. Every call emits a
 * {@code ChallengeResult} event.
 */
public interface EvidenceSigner {

	/** The longest a pass may live. */
	Duration MAX_TTL = Duration.ofDays(30);

	/**
	 * What a cookie may be bound to - resolved by the node from the request, so a plugin can never bind a pass to a
	 * value of its choosing.
	 */
	enum Binding {
		/** The visitor's address, as {@link PluginRequest#clientAddress()} resolves it: a pass does not travel. */
		CLIENT_IP,
		/** The visitor's {@code User-Agent}. */
		USER_AGENT
	}

	/**
	 * Signs a pass and reports {@code ChallengeResult(pass)}.
	 *
	 * @param request    the request being served - the view the node handed the endpoint, never another
	 * @param provider   the challenge provider that was passed - one the manifest names in {@code challenges}
	 * @param cookieName a cookie the manifest names in {@code cookies}
	 * @param ttl        how long the pass is good for, at most {@link #MAX_TTL}
	 * @param bindings   what the pass is bound to; a bound pass is refused on a request the binding does not hold for
	 * @return the cookie's value - set it with {@link PluginResponse#cookie}
	 * @throws IllegalArgumentException for a cookie or provider the manifest does not name, a ttl out of range, a
	 *         request the node did not hand over, or a binding the request cannot establish (an unresolved address)
	 */
	String sign(PluginRequest request, String provider, String cookieName, Duration ttl, Binding... bindings);

	/**
	 * Reports a challenge that was not passed: {@code ChallengeResult(fail)}, or {@code error} when the plugin could
	 * not tell (a third-party verifier unreachable - never a pass).
	 */
	void failed(PluginRequest request, String provider, boolean error);
}
