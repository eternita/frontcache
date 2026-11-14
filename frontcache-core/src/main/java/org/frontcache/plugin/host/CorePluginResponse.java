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
package org.frontcache.plugin.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.core.FCHeaders;
import org.frontcache.plugin.serve.PluginResponse;

/**
 * The node's {@link PluginResponse}: buffered, so the node decides what reaches the visitor once the endpoint has
 * returned - a 503 when it threw or overflowed the buffer, its answer otherwise - and narrow: an allowlist of headers,
 * the manifest's cookies only, a redirect checked as a guard rule's redirect is.
 */
final class CorePluginResponse implements PluginResponse {

	/** The most a plugin's answer may hold. */
	static final int MAX_BODY = 2 * 1024 * 1024;

	private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

	/** RFC 6265 cookie-octet. */
	private static final Pattern COOKIE_VALUE = Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]{0,4096}");

	private final Set<String> cookies;
	private final boolean secure;
	private final Predicate<String> redirectAllowed;

	private int status = 200;
	/** lower-cased name -> { name as set, value } */
	private final Map<String, String[]> headers = new LinkedHashMap<>();
	private final List<String> setCookies = new ArrayList<>();
	private String location;

	private final Buffer body = new Buffer();
	private PrintWriter writer;

	/**
	 * @param cookies         the cookies the manifest names
	 * @param secure          whether the visitor's scheme is https - a cookie is then {@code Secure}
	 * @param redirectAllowed the guard's own rule for a redirect target
	 */
	CorePluginResponse(Set<String> cookies, boolean secure, Predicate<String> redirectAllowed) {
		this.cookies = cookies;
		this.secure = secure;
		this.redirectAllowed = (null == redirectAllowed) ? t -> null != t && t.startsWith("/") && !t.startsWith("//")
				: redirectAllowed;
	}

	@Override
	public void status(int status) {
		if (status < 100 || status > 599)
			throw new IllegalArgumentException("not an HTTP status: " + status);
		this.status = status;
	}

	int status() {
		return status;
	}

	@Override
	public void header(String name, String value) {
		if (null == name || !HEADERS.contains(name.toLowerCase(Locale.ROOT)))
			throw new IllegalArgumentException("a plugin may not set the header '" + name + "' - allowed: " + HEADERS);
		checkValue(name, value);
		headers.put(name.toLowerCase(Locale.ROOT), new String[] { name, value });
	}

	@Override
	public String redirect(int status, String target) {
		if (!REDIRECT_STATUSES.contains(status))
			throw new IllegalArgumentException("a redirect's status is 301, 302, 303, 307 or 308, not " + status);
		this.status = status;
		this.location = (null != target && redirectAllowed.test(target)) ? target : "/";
		return location;
	}

	@Override
	public void cookie(String name, String value, Duration maxAge) {
		if (null == name || !cookies.contains(name))
			throw new IllegalArgumentException("the manifest does not name the cookie '" + name + "' - it names " + cookies);
		if (null == value || !COOKIE_VALUE.matcher(value).matches())
			throw new IllegalArgumentException("not a cookie value (RFC 6265 cookie-octets, at most 4096)");
		if (null == maxAge || maxAge.isNegative())
			throw new IllegalArgumentException("a cookie's max age is zero (delete it) or more");

		setCookies.add(name + "=" + value + "; Path=/; Max-Age=" + maxAge.getSeconds() + "; HttpOnly; SameSite=Lax"
				+ (secure ? "; Secure" : ""));
	}

	@Override
	public OutputStream outputStream() {
		return body;
	}

	@Override
	public PrintWriter writer() {
		if (null == writer)
			writer = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), false);
		return writer;
	}

	boolean overflowed() {
		if (null != writer)
			writer.flush();
		return body.overflowed;
	}

	/** Writes it to the visitor. @return the body's length */
	long writeTo(HttpServletResponse response, String cacheControl) {
		if (null != writer)
			writer.flush();
		try {
			response.setStatus(status);
			for (String[] header : headers.values())
				response.setHeader(header[0], header[1]);
			if (null != location)
				response.setHeader("Location", location);
			for (String cookie : setCookies)
				response.addHeader("Set-Cookie", cookie);
			response.setHeader(FCHeaders.CACHE_CONTROL, cacheControl);
			response.setHeader("X-Content-Type-Options", "nosniff");
			if (!headers.containsKey("content-type") && 0 < body.size())
				response.setContentType("text/plain; charset=UTF-8");
			response.setContentLength(body.size());
			if (0 < body.size())
				body.writeTo(response.getOutputStream());
		} catch (IOException | RuntimeException e) {
			// the visitor went away - what was answered is in the request log either way
		}
		return body.size();
	}

	private static void checkValue(String name, String value) {
		if (null == value)
			throw new IllegalArgumentException("the header '" + name + "' needs a value");
		for (int i = 0; i < value.length(); i++)
			if ('\r' == value.charAt(i) || '\n' == value.charAt(i))
				throw new IllegalArgumentException("the header '" + name + "' has a line break");
	}

	/** A byte buffer that stops at {@link #MAX_BODY} and remembers that it did. */
	private static final class Buffer extends ByteArrayOutputStream {
		boolean overflowed;

		@Override
		public synchronized void write(int b) {
			if (count + 1 > MAX_BODY)
				overflowed = true;
			else
				super.write(b);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			if (count + len > MAX_BODY)
				overflowed = true;
			else
				super.write(b, off, len);
		}
	}
}
