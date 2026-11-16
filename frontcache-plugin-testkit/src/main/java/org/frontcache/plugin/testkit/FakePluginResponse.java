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
package org.frontcache.plugin.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.serve.PluginResponse;

/**
 * What an endpoint answered - buffered, as the node buffers it, and narrowed as the node narrows it: a header outside
 * {@link PluginResponse#HEADERS} or with a line break, a cookie the manifest does not name or a value that is not a
 * cookie token, and a redirect status that is not one are refused with {@link IllegalArgumentException}; a redirect
 * target that is not a path on the site or the public domain (or an allowed host) becomes {@code /}; a body over
 * {@link #MAX_BODY} fails.
 */
public final class FakePluginResponse implements PluginResponse {

	/** The largest body the node writes; over it the request fails ({@code 503}). */
	public static final int MAX_BODY = 2 * 1024 * 1024;

	private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

	/** A cookie as the response sets it. */
	public record Cookie(String name, String value, Duration maxAge, boolean secure) {
	}

	private final Set<String> cookieNames;
	private final String publicDomain;
	private final Set<String> redirectHosts;
	private final boolean secure;

	private int status = 200;
	private final TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final Map<String, Cookie> cookies = new LinkedHashMap<>();
	private final Body body = new Body();
	private PrintWriter writer;

	/**
	 * @param manifest     the plugin's - which cookies it may set
	 * @param publicDomain {@code front-cache.default-domain}, or null
	 * @param redirectHosts other hosts a redirect may name ({@code front-cache.guard-rules.allowed-redirect-hosts})
	 * @param scheme       the visitor's scheme - a cookie is {@code Secure} on {@code https}
	 */
	public FakePluginResponse(PluginManifest manifest, String publicDomain, Set<String> redirectHosts, String scheme) {
		this.cookieNames = manifest.cookies();
		this.publicDomain = publicDomain;
		this.redirectHosts = Set.copyOf(redirectHosts);
		this.secure = "https".equalsIgnoreCase(scheme);
	}

	// ---- PluginResponse ------------------------------------------------------------------------------------

	@Override
	public void status(int status) {
		this.status = status;
	}

	@Override
	public void header(String name, String value) {
		if (null == name || !HEADERS.contains(name.toLowerCase(Locale.ROOT)))
			throw new IllegalArgumentException("header '" + name + "' is not one a plugin may set - " + HEADERS);
		if (null == value || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
			throw new IllegalArgumentException("header '" + name + "' has no value or a line break in it");
		headers.put(name, value);
	}

	@Override
	public String redirect(int status, String target) {
		if (!REDIRECTS.contains(status))
			throw new IllegalArgumentException("a redirect's status is 301, 302, 303, 307 or 308 - not " + status);
		String checked = allowed(target) ? target : "/";
		this.status = status;
		headers.put("Location", checked);
		return checked;
	}

	@Override
	public void cookie(String name, String value, Duration maxAge) {
		if (null == name || !cookieNames.contains(name))
			throw new IllegalArgumentException("cookie '" + name + "' is not named in the manifest's cookies " + cookieNames);
		if (null == value || !isToken(value))
			throw new IllegalArgumentException("cookie '" + name + "': the value is not a cookie token");
		if (null == maxAge || maxAge.isNegative())
			throw new IllegalArgumentException("cookie '" + name + "': maxAge must be zero or positive");
		cookies.put(name, new Cookie(name, value, maxAge, secure));
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

	// ---- what the test reads ---------------------------------------------------------------------------------

	public int status() {
		return status;
	}

	/** A header the endpoint set ({@code Location} included), or null. Case-insensitive. */
	public String header(String name) {
		return headers.get(name);
	}

	public Map<String, String> headers() {
		return Collections.unmodifiableMap(headers);
	}

	public String contentType() {
		return headers.get("Content-Type");
	}

	/** The redirect's target, or null. */
	public String location() {
		return headers.get("Location");
	}

	public Map<String, Cookie> cookies() {
		return Collections.unmodifiableMap(cookies);
	}

	/** The body's bytes - what the writer wrote included. */
	public byte[] body() {
		flush();
		return body.bytes.toByteArray();
	}

	/** The body as UTF-8 text. */
	public String text() {
		return new String(body(), StandardCharsets.UTF_8);
	}

	/** Whether the endpoint wrote more than {@link #MAX_BODY} - the node then answers {@code 503}. */
	public boolean overLimit() {
		flush();
		return body.overLimit;
	}

	void flush() {
		if (null != writer)
			writer.flush();
	}

	@Override
	public String toString() {
		return status + " " + headers + " (" + body.bytes.size() + " bytes)";
	}

	private boolean allowed(String target) {
		if (null == target || target.isEmpty() || target.indexOf('\\') >= 0 || target.indexOf('\n') >= 0
				|| target.indexOf('\r') >= 0)
			return false;
		if (target.startsWith("/"))
			return !target.startsWith("//");
		try {
			URI uri = new URI(target);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (null == scheme || null == host || null != uri.getRawUserInfo())
				return false;
			if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
				return false;
			String h = host.toLowerCase(Locale.ROOT);
			return h.equalsIgnoreCase(publicDomain) || redirectHosts.contains(h);
		} catch (Exception e) {
			return false;
		}
	}

	/** RFC 6265 cookie-octets. */
	private static boolean isToken(String value) {
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			boolean ok = c == 0x21 || (c >= 0x23 && c <= 0x2B) || (c >= 0x2D && c <= 0x3A) || (c >= 0x3C && c <= 0x5B)
					|| (c >= 0x5D && c <= 0x7E);
			if (!ok)
				return false;
		}
		return true;
	}

	private static final class Body extends OutputStream {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		boolean overLimit;

		@Override
		public synchronized void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			if (bytes.size() + len > MAX_BODY)
			{
				overLimit = true;
				throw new IOException("the response body is over " + MAX_BODY + " bytes - the node answers 503");
			}
			bytes.write(b, off, len);
		}
	}
}
