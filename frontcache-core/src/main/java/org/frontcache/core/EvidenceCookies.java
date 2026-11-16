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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evidence cookies: a cookie whose value a client cannot produce without the node's secret - what the
 * {@code cookie-hmac:<name>} predicate verifies, and what a serving plugin's evidence signer mints. The evidence
 * pattern: the core decides who must prove something and checks the proof; whoever helped the visitor produce it
 * (the origin's own challenge page, or a plugin under {@code /fc-x/}) only asks for a signature.
 *
 * <h2>The value</h2>
 *
 * <pre>
 *   &lt;expiry&gt;.&lt;mac&gt;                     unbound
 *   &lt;expiry&gt;.&lt;binding&gt;.&lt;mac&gt;           bound: binding = ip | ua | ip_ua
 *
 *   expiry = epoch seconds, decimal
 *   mac    = base64url, unpadded, of HMAC-SHA256(secret, message)
 *   message (unbound) = name "|" expiry
 *   message (bound)   = name "|" expiry "|" binding "|" &lt;client address or ""&gt; "|" &lt;User-Agent or ""&gt;
 * </pre>
 *
 * The unbound form is the one an origin's challenge page writes with the shared secret (the visitor-type proposal's
 * §8). The name is in the message, so a value minted for one cookie is worthless under another; a bound value is
 * refused on a request the binding does not hold for, so a pass is not shared. The client address is the one
 * {@link ClientIpResolver} resolves - through trusted proxies only - so a client cannot claim another's.
 *
 * <h2>The secret</h2>
 *
 * {@value #SECRET_PROPERTY}, at least {@value #MIN_SECRET_LENGTH} characters - a shorter one is refused, with an
 * ERROR, because a value and its MAC are what an attacker has to guess the key offline from. Without one there are no
 * evidence cookies: the predicate is refused at load (never "always true", never "always false and silent") and a
 * {@code challenge} rule acts as a dry run. {@value #PREVIOUS_SECRET_PROPERTY} is accepted, never signed with - the
 * overlap that lets a fleet rotate, as {@code front-cache.edge-token.previous} does. Read per call, as
 * {@link EdgeToken} is.
 */
public final class EvidenceCookies {

	public static final String SECRET_PROPERTY = "front-cache.visitor.cookie-secret";

	public static final String PREVIOUS_SECRET_PROPERTY = "front-cache.visitor.cookie-secret.previous";

	public static final int MIN_SECRET_LENGTH = 32;

	/** A cookie name the predicate may name: an RFC 6265 token, short. */
	public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");

	public static final String BIND_IP = "ip";
	public static final String BIND_UA = "ua";
	public static final String BIND_IP_UA = "ip_ua";

	/** Longer values are not looked at: a real one is under 80 characters. */
	static final int MAX_VALUE_LENGTH = 256;

	/** A User-Agent is bound up to this many characters, as the predicates cap what they match. */
	static final int MAX_BOUND_UA_LENGTH = 1024;

	private static final Logger logger = LoggerFactory.getLogger(EvidenceCookies.class);

	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

	private static final ThreadLocal<Mac> HMAC = ThreadLocal.withInitial(() -> {
		try {
			return Mac.getInstance("HmacSHA256");
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is not available", e);
		}
	});

	/** The last too-short value warned about, so a node logs it once rather than per request. */
	private static final AtomicReference<String> warnedShort = new AtomicReference<String>();

	private EvidenceCookies() {
	}

	/** @return whether this node has a usable secret - without one nothing is signed and nothing verifies */
	public static boolean isConfigured() {
		return null != secret(SECRET_PROPERTY);
	}

	/**
	 * @param binding null for an unbound value, else {@link #BIND_IP}, {@link #BIND_UA} or {@link #BIND_IP_UA}
	 * @param clientIp  the resolved client address - required when the binding names it
	 * @param userAgent the request's User-Agent, null as ""
	 * @return the cookie's value
	 * @throws IllegalStateException when the node has no secret
	 * @throws IllegalArgumentException for an unknown binding, or an ip binding without an address
	 */
	public static String sign(String name, long expiryEpochSeconds, String binding, String clientIp, String userAgent) {

		String secret = secret(SECRET_PROPERTY);
		if (null == secret)
			throw new IllegalStateException(SECRET_PROPERTY + " is not set (or shorter than " + MIN_SECRET_LENGTH
					+ " characters) - no evidence cookie can be signed");
		if (null == name || !NAME.matcher(name).matches())
			throw new IllegalArgumentException("not a cookie name: " + name);
		if (null != binding && !BIND_IP.equals(binding) && !BIND_UA.equals(binding) && !BIND_IP_UA.equals(binding))
			throw new IllegalArgumentException("unknown binding: " + binding);
		if (null != binding && binding.startsWith(BIND_IP) && null == clientIp)
			throw new IllegalArgumentException("the client address is unresolved - a pass cannot be bound to it");

		String expiry = Long.toString(expiryEpochSeconds);
		String mac = mac(secret, message(name, expiry, binding, clientIp, userAgent));
		return (null == binding) ? expiry + "." + mac : expiry + "." + binding + "." + mac;
	}

	/**
	 * Whether the request carries a valid, unexpired evidence cookie under {@code name}. Memoized per request and
	 * name; never throws - a request that cannot be read (a recycled one, a probe) holds none.
	 */
	public static boolean holds(RequestContext context, String name) {

		String key = "evidenceCookie." + name;
		Object cached = context.get(key);
		if (cached instanceof Boolean)
			return (Boolean) cached;

		boolean valid = false;
		try {
			List<String> values = context.getCookieValues(name);
			if (!values.isEmpty())
			{
				long now = System.currentTimeMillis() / 1000L;
				for (String value : values)
					if (verify(name, value, now, context))
					{
						valid = true;
						break;
					}
			}
		} catch (RuntimeException e) {
			valid = false; // an evidence check that cannot be made is no evidence
		}
		context.set(key, Boolean.valueOf(valid));
		return valid;
	}

	/** One value against the current secret, then the previous one. */
	static boolean verify(String name, String value, long nowEpochSeconds, RequestContext context) {

		if (null == value || value.length() > MAX_VALUE_LENGTH)
			return false;

		int first = value.indexOf('.');
		int last = value.lastIndexOf('.');
		if (first <= 0 || last == value.length() - 1)
			return false;

		String expiry = value.substring(0, first);
		String binding = (first == last) ? null : value.substring(first + 1, last);
		String presented = value.substring(last + 1);

		if (expiry.length() > 12 || !isDigits(expiry))
			return false;
		if (Long.parseLong(expiry) <= nowEpochSeconds)
			return false;

		String clientIp = null;
		String userAgent = null;
		if (null != binding)
		{
			if (BIND_IP.equals(binding) || BIND_IP_UA.equals(binding))
			{
				clientIp = context.getResolvedClientIP();
				if (null == clientIp)
					return false; // an address-bound pass cannot be checked against no address
			}
			else if (!BIND_UA.equals(binding))
				return false;
			if (BIND_UA.equals(binding) || BIND_IP_UA.equals(binding))
				userAgent = context.getUserAgent();
		}

		String message = message(name, expiry, binding, clientIp, userAgent);
		for (String property : new String[] { SECRET_PROPERTY, PREVIOUS_SECRET_PROPERTY })
		{
			String secret = secret(property);
			if (null != secret && constantTimeEquals(mac(secret, message), presented))
				return true;
		}
		return false;
	}

	private static String message(String name, String expiry, String binding, String clientIp, String userAgent) {

		if (null == binding)
			return name + "|" + expiry;

		String ua = (null == userAgent) ? "" : userAgent;
		if (ua.length() > MAX_BOUND_UA_LENGTH)
			ua = ua.substring(0, MAX_BOUND_UA_LENGTH);
		return name + "|" + expiry + "|" + binding + "|"
				+ (binding.startsWith(BIND_IP) ? clientIp : "") + "|"
				+ (binding.endsWith(BIND_UA) ? ua : "");
	}

	private static String mac(String secret, String message) {
		try {
			Mac mac = HMAC.get();
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return BASE64URL.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 refused the key", e);
		}
	}

	private static boolean constantTimeEquals(String expected, String presented) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				presented.getBytes(StandardCharsets.US_ASCII));
	}

	private static boolean isDigits(String value) {
		if (value.isEmpty())
			return false;
		for (int i = 0; i < value.length(); i++)
			if (value.charAt(i) < '0' || value.charAt(i) > '9')
				return false;
		return true;
	}

	/** @return the property's secret, or null when it is unset or too short (the latter logged once per value) */
	private static String secret(String property) {

		String value = FCConfig.getProperty(property);
		if (null == value)
			return null;
		value = value.trim();
		if (value.isEmpty())
			return null;
		if (value.length() < MIN_SECRET_LENGTH)
		{
			String marker = property + "#" + value.length();
			if (!marker.equals(warnedShort.getAndSet(marker)))
				logger.error(property + " is " + value.length() + " characters - at least " + MIN_SECRET_LENGTH
						+ " are needed, so it is ignored: no evidence cookie is signed or verified with it");
			return null;
		}
		return value;
	}
}
