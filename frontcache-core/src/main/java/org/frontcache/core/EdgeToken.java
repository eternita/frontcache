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
package org.frontcache.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.apache.hc.core5.http.HttpRequest;
import org.frontcache.FCConfig;
import org.slf4j.Logger;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The {@code front-cache.edge-token} - how a second-layer node tells a request from its own edge
 * apart from one that reached it directly.
 *
 * In a chained deployment (browser -> edge -> second layer -> app) the second layer is usually
 * reachable some other way too: a public origin hostname the edge itself needs, a CDN tunnel, a bare
 * IP. Traffic that takes that route skips every guard rule and rate limit on the edge, and nothing on
 * the wire said so - the edge's own markers are either forgeable ({@code x-frontcache-request-id},
 * {@code X-Forwarded-Host}) or absent on some of its calls ({@code FC_BypassCache} sends no
 * request-id).
 *
 * <h2>One token per chain, sent and accepted by every node</h2>
 *
 * A node with a token SENDS it on every origin call it makes ({@link #stamp} - and only to the origin
 * host, see there) and ACCEPTS it on
 * every request it receives ({@link #isValid}). Every node of the chain carries the same value, so
 * there is one setting and no notion of direction - which is what makes a filter-mode node's own
 * include fetch, which re-enters the same node over HTTP, a non-event: it is an origin call by a node
 * with a token, so it carries one. Per-edge tokens were considered and dropped: one token per chain
 * is enough, and a compromised edge is answered by rotating it ({@code .previous} keeps both values
 * accepted while the fleet is re-configured).
 *
 * <h2>Not the api-key</h2>
 *
 * The api-key authorizes management writes. Sending it on every origin call, through every CDN hop
 * and into the origin app's request headers, would widen the exposure of a write credential for no
 * gain. A separate secret has a separate blast radius and its own rotation.
 *
 * <h2>Where it is enforced</h2>
 *
 * Nowhere by itself. {@link #isValid} is computed once per request in {@code FrontCacheEngine.init()}
 * and exposed to guard-rules.conf as the {@code edge-token} predicate, so enforcement is an ordinary
 * guard rule with dry-run, hit counters, the console's Guard tab and the failed-requests log:
 *
 * <pre>
 *   direct-get   | !edge-token ; method:GET | redirect:301 https://www.example.com${uri}${query}
 *   direct-other | !edge-token              | reject:403
 * </pre>
 *
 * <h2>Never relayed</h2>
 *
 * {@code FCUtils.isIncludedHeader} drops an inbound {@link FCHeaders#X_FRONTCACHE_EDGE_TOKEN}, and
 * {@link #stamp} SETS the header rather than adding it, so a node only ever sends its own value and a
 * client's copy goes no further than the first Frontcache it reaches. In filter mode the app sees the
 * raw servlet request, header included - nothing a filter can strip without wrapping the request.
 *
 * <h2>Read per call</h2>
 *
 * Like {@link ApiKey}: a property read is cheap, and it keeps a test or a future config reload from
 * holding on to a stale value.
 */
public class EdgeToken {

	/** Sent on every origin call, accepted on every inbound request. */
	public static final String EDGE_TOKEN_PROPERTY = "front-cache.edge-token";

	/** Accepted, never sent - the overlap that lets a fleet rotate without a flag day. */
	public static final String PREVIOUS_EDGE_TOKEN_PROPERTY = "front-cache.edge-token.previous";

	private static final String ORIGIN_HOST_PROPERTY = "front-cache.origin-host";
	private static final String DEFAULT_ORIGIN_HOST = "localhost";

	/** Below this a token is guessable rather than secret; warned about, not refused. */
	static final int MIN_RECOMMENDED_LENGTH = 32;

	private EdgeToken() { }

	/** @return the token this node sends and accepts, or "" when it has none */
	public static String configured()
	{
		return property(EDGE_TOKEN_PROPERTY);
	}

	/** @return the token accepted during a rotation, or "" */
	public static String previous()
	{
		return property(PREVIOUS_EDGE_TOKEN_PROPERTY);
	}

	/**
	 * @return true when this node has an edge token. Without one it sends nothing, and a guard rule
	 *         naming {@code edge-token} is refused at load - {@code !edge-token} would otherwise match
	 *         every request.
	 */
	public static boolean isConfigured()
	{
		return configured().length() > 0;
	}

	/**
	 * @param request the inbound request
	 * @return true when the request carries this node's token, or the previous one. Always false on a
	 *         node with no token: an unconfigured node has nothing to compare against, and "valid" must
	 *         never be the answer to "I cannot tell".
	 */
	public static boolean isValid(HttpServletRequest request)
	{
		if (null == request)
			return false;

		return isValid(request.getHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN));
	}

	/** @see #isValid(HttpServletRequest) */
	static boolean isValid(String presented)
	{
		if (null == presented)
			return false;

		presented = presented.trim();
		if (0 == presented.length())
			return false;

		String current = configured();
		if (0 == current.length())
			return false;

		// both compared, always - returning on the first match would time which of the two it was
		boolean matchesCurrent = matches(current, presented);
		String previous = previous();
		boolean matchesPrevious = (0 < previous.length()) && matches(previous, presented);

		return matchesCurrent || matchesPrevious;
	}

	/**
	 * Puts this node's token on an outgoing request - but only one addressed to
	 * {@code front-cache.origin-host}. Replaces any value already there, so an inbound copy can never
	 * ride along. Does nothing on a node with no token.
	 *
	 * <h2>Why the host check</h2>
	 *
	 * The commands that call this do not only ever talk to the origin. With
	 * {@code include-fetch-target=edge} an absolute {@code <fc:include url="https://other.example/…">}
	 * is fetched from where it points, and a {@code fallbacks.conf} init URL can name any host. A
	 * shared secret that went wherever page markup or a config line sent it would be a secret in
	 * somebody else's access log. Everything the token exists for - page misses, origin-mode include
	 * fetches (rebased onto the origin by {@code FCUtils.rebaseURL}), bypassed calls - is addressed
	 * to the origin host, so that is the whole of where it goes. An edge-mode include comes back
	 * through this node's own front door and gets the token on that request's origin call instead.
	 *
	 * @param request    the request about to be executed
	 * @param targetHost the host it is addressed to ({@code HttpHost.getHostName()})
	 */
	public static void stamp(HttpRequest request, String targetHost)
	{
		if (null == request || null == targetHost)
			return;

		String token = configured();
		if (0 == token.length())
			return;

		if (!targetHost.equalsIgnoreCase(originHost()))
			return;

		request.setHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, token);
	}

	/** The same property, and the same default, {@code FrontCacheEngine} builds the origin URLs from. */
	private static String originHost()
	{
		String host = property(ORIGIN_HOST_PROPERTY);
		return (0 == host.length()) ? DEFAULT_ORIGIN_HOST : host;
	}

	/**
	 * Says once, at startup, what this node does with the token - and never what the token is.
	 */
	public static void logConfiguration(Logger logger)
	{
		if (!isConfigured())
		{
			if (0 < previous().length())
				logger.warn(PREVIOUS_EDGE_TOKEN_PROPERTY + " is set but " + EDGE_TOKEN_PROPERTY + " is not -"
						+ " the previous value is only ever accepted beside a current one, so it is ignored");
			return;
		}

		logger.info("Edge token configured: sent as " + FCHeaders.X_FRONTCACHE_EDGE_TOKEN + " on every origin call,"
				+ " and accepted on inbound requests (guard-rules.conf predicate 'edge-token')"
				+ ((0 < previous().length()) ? ", previous value also accepted" : ""));

		if (configured().length() < MIN_RECOMMENDED_LENGTH)
			logger.warn(EDGE_TOKEN_PROPERTY + " is shorter than " + MIN_RECOMMENDED_LENGTH + " characters -"
					+ " it is a shared secret, use a long random value (e.g. openssl rand -hex 32)");
	}

	private static String property(String key)
	{
		String value = FCConfig.getProperty(key, "");
		return (null == value) ? "" : value.trim();
	}

	/** Constant-time, as {@link ApiKey} compares. */
	private static boolean matches(String expected, String presented)
	{
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				presented.getBytes(StandardCharsets.UTF_8));
	}

}
