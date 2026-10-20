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
package org.frontcache.warmer;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;

/**
 * What makes a request a warm request, and the vocabulary a warm request is answered in.
 *
 * <h2>The token</h2>
 *
 * The warmer sends {@code x-frontcache-warm: <token>} to this node's own front door. The token is 256
 * random bits generated when this class loads. It lives only in this JVM's memory and is never
 * logged, returned or configured, so the only thing that can present it is this process.
 * {@link #resolve} compares it in constant time and also requires a loopback peer, as defense in
 * depth. Anything else - a wrong token, the right token from a non-loopback peer, a malformed mode -
 * is treated as if the header were absent. It is not an error, so the header gives a prober no signal.
 *
 * <h2>What a warm request changes</h2>
 *
 * The client type is the run's, not {@code bots.conf}'s; the guard rules are skipped; {@code FC_Total} runs
 * under {@code Warm-Request}; {@code refresh} mode reads past a cached entry without removing it; a soft
 * refresh and an async include are resolved synchronously, so the warmer's delay paces the origin and not
 * just the front door; and the response carries {@code x-frontcache-warm-result}.
 *
 * <h2>The headers must never reach the origin</h2>
 *
 * {@code FCUtils.isIncludedHeader} drops every {@code x-frontcache-warm*} header. If that drop goes,
 * the token lands in the origin app's access log, and whoever reads that log can skip the guard rules
 * and force refreshes. {@code WarmRequestHeaderTest} asserts it.
 */
public final class WarmRequest {

	public static final String MODE_FILL = "fill";
	public static final String MODE_REFRESH = "refresh";

	public static final String RESULT_STORED = "stored";
	public static final String RESULT_FRESH = "fresh";
	public static final String RESULT_NOT_CACHEABLE = "not-cacheable";
	public static final String RESULT_FALLBACK = "fallback";

	/** What {@code RequestContext.getClientTypeRule()} reports for a warm request. */
	public static final String CLIENT_TYPE_RULE = "warmer";

	private static final String TOKEN = newToken();

	private WarmRequest() { }

	private static String newToken()
	{
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);

		StringBuilder hex = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
			hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));

		return hex.toString();
	}

	/**
	 * The token the warmer presents. Public so the core can hand it to a module through
	 * {@code ModuleContext.warmRequestToken()} - the warmer's orchestration is a module's, and a
	 * package-private accessor would work across jars only while both shared a class loader. Nothing else
	 * has any use for it, and it must never be logged.
	 */
	public static String token()
	{
		return TOKEN;
	}

	/**
	 * Marks {@code context} as a warm request when {@code request} carries a valid token from a
	 * loopback peer, and sets the run's client type on it. Does nothing otherwise.
	 *
	 * Must run before the client type is classified and before the guard rules - see
	 * {@code FrontCacheEngine.init()}.
	 *
	 * @return true when the request is a warm request
	 */
	public static boolean resolve(HttpServletRequest request, RequestContext context)
	{
		if (null == request || null == context)
			return false;

		String presented = request.getHeader(FCHeaders.X_FRONTCACHE_WARM);
		if (null == presented)
			return false; // the overwhelmingly common case - one header read and out

		if (!isAuthentic(presented, request.getRemoteAddr()))
			return false;

		String mode = normalizeMode(request.getHeader(FCHeaders.X_FRONTCACHE_WARM_MODE));
		String clientType = normalizeClientType(request.getHeader(FCHeaders.X_FRONTCACHE_WARM_CLIENT_TYPE));
		if (null == mode || null == clientType)
			return false;

		context.setWarmRequest(mode);
		context.setClientType(clientType);
		context.setClientTypeRule(CLIENT_TYPE_RULE);
		return true;
	}

	/** Constant-time token comparison plus the loopback-peer check. */
	static boolean isAuthentic(String presentedToken, String remoteAddr)
	{
		if (null == presentedToken)
			return false;

		boolean tokenMatches = MessageDigest.isEqual(
				TOKEN.getBytes(StandardCharsets.US_ASCII),
				presentedToken.trim().getBytes(StandardCharsets.US_ASCII));

		return tokenMatches && isLoopback(remoteAddr);
	}

	/**
	 * Is this a literal loopback address? Parsed only when it is an IP literal - a name is never
	 * resolved here, since a request's remote address is always a literal and a DNS lookup on the
	 * request path would be its own problem.
	 */
	static boolean isLoopback(String remoteAddr)
	{
		if (null == remoteAddr || remoteAddr.isEmpty())
			return false;

		String addr = remoteAddr.trim();
		if (addr.startsWith("[") && addr.endsWith("]"))
			addr = addr.substring(1, addr.length() - 1);

		boolean literal = -1 < addr.indexOf(':') || addr.chars().allMatch(c -> '.' == c || Character.isDigit(c));
		if (!literal)
			return false;

		try {
			return InetAddress.getByName(addr).isLoopbackAddress();
		} catch (Exception e) {
			return false;
		}
	}

	/** @return {@code fill} | {@code refresh}, or null for anything else */
	public static String normalizeMode(String mode)
	{
		if (null == mode)
			return null;

		String m = mode.trim().toLowerCase();
		return (MODE_FILL.equals(m) || MODE_REFRESH.equals(m)) ? m : null;
	}

	/** @return {@code bot} | {@code guest}, or null for anything else */
	public static String normalizeClientType(String clientType)
	{
		if (null == clientType)
			return null;

		String t = clientType.trim().toLowerCase();
		return (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equals(t) || FCHeaders.REQUEST_CLIENT_TYPE_GUEST.equals(t)) ? t : null;
	}
}
