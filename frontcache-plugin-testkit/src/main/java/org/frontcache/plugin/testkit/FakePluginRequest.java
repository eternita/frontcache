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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.plugin.serve.PluginRequest;

/**
 * A request to a plugin's own path, as the node's view shows it - so an endpoint can be called in-process
 * ({@link PluginHarness#serve}). Narrowed as the node narrows it: {@code Authorization}, {@code Proxy-Authorization},
 * {@code Cookie} and {@code x-frontcache-*} are not among the headers; {@link #cookie} answers only a name the
 * plugin's manifest lists in {@code cookies} (the harness binds the manifest); the body is bounded
 * ({@link #DEFAULT_MAX_BODY}, reading past it throws) and readable once.
 *
 * <pre>
 * FakePluginRequest.get("/?p=pow&amp;return=/cart").from("203.0.113.7").header("Accept-Language", "de")
 * FakePluginRequest.form("/verify", "answer=42&amp;token=abc")
 * </pre>
 */
public final class FakePluginRequest implements PluginRequest {

	/** {@code front-cache.plugins.<id>.max-body}'s default: 64 KiB. */
	public static final int DEFAULT_MAX_BODY = 64 * 1024;

	private static final AtomicInteger IDS = new AtomicInteger();

	private String method = "GET";
	private String path = "/";
	private String query = "";
	private byte[] body = new byte[0];
	private int maxBody = DEFAULT_MAX_BODY;
	private final TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final Map<String, String> cookies = new LinkedHashMap<>();
	private Set<String> visibleCookies = Set.of();
	private String clientAddress = "203.0.113.7";
	private String clientType = "guest";
	private String requestId = "test-request-" + IDS.incrementAndGet();
	private String scheme = "https";
	private InputStream bodyStream;
	private Map<String, List<String>> form;

	private FakePluginRequest() {
	}

	/** A GET of a path under the plugin's prefix: {@code /}, {@code /verify?x=1}. */
	public static FakePluginRequest get(String pathAndQuery) {
		return request("GET", pathAndQuery);
	}

	public static FakePluginRequest request(String method, String pathAndQuery) {
		FakePluginRequest r = new FakePluginRequest();
		r.method = method;
		String target = (null == pathAndQuery || pathAndQuery.isEmpty()) ? "/" : pathAndQuery;
		int q = target.indexOf('?');
		r.path = (q < 0) ? target : target.substring(0, q);
		r.query = (q < 0) ? "" : target.substring(q + 1);
		if (!r.path.startsWith("/"))
			r.path = "/" + r.path;
		return r;
	}

	/** A POST with a body of the given content type. */
	public static FakePluginRequest post(String pathAndQuery, String contentType, String body) {
		return request("POST", pathAndQuery).header("Content-Type", contentType).body(body);
	}

	/** A POST of {@code application/x-www-form-urlencoded} parameters. */
	public static FakePluginRequest form(String pathAndQuery, String urlEncodedForm) {
		return post(pathAndQuery, "application/x-www-form-urlencoded", urlEncodedForm);
	}

	// ---- building -------------------------------------------------------------------------------------------

	/** Sets a header - one the node's view hides is accepted here and is not shown, as on the node. */
	public FakePluginRequest header(String name, String value) {
		headers.put(name, value);
		return this;
	}

	/** A cookie the visitor sent; {@link #cookie} shows it only when the plugin's manifest names it. */
	public FakePluginRequest cookie(String name, String value) {
		cookies.put(name, value);
		return this;
	}

	public FakePluginRequest body(String body) {
		return body(body.getBytes(StandardCharsets.UTF_8));
	}

	public FakePluginRequest body(byte[] body) {
		this.body = body.clone();
		return this;
	}

	/** The body limit ({@code front-cache.plugins.<id>.max-body}). */
	public FakePluginRequest maxBody(int bytes) {
		this.maxBody = bytes;
		return this;
	}

	/** The visitor's resolved address; null for one the node could not establish. */
	public FakePluginRequest from(String clientAddress) {
		this.clientAddress = clientAddress;
		return this;
	}

	public FakePluginRequest clientType(String clientType) {
		this.clientType = clientType;
		return this;
	}

	public FakePluginRequest requestId(String requestId) {
		this.requestId = requestId;
		return this;
	}

	/** {@code http} or {@code https}. */
	public FakePluginRequest scheme(String scheme) {
		this.scheme = scheme;
		return this;
	}

	/** The cookies the plugin's manifest names - what {@link #cookie} may show. {@link PluginHarness#serve} sets it. */
	public FakePluginRequest visibleCookies(Set<String> names) {
		this.visibleCookies = Set.copyOf(names);
		return this;
	}

	// ---- PluginRequest --------------------------------------------------------------------------------------

	@Override
	public String method() {
		return method;
	}

	@Override
	public String path() {
		return path;
	}

	@Override
	public String queryString() {
		return query;
	}

	@Override
	public Map<String, List<String>> queryParameters() {
		return decode(query);
	}

	@Override
	public synchronized Map<String, List<String>> formParameters() throws IOException {
		if (null != form)
			return form;
		String type = headers.get("Content-Type");
		if (null == type || !type.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded"))
			return form = Map.of();
		byte[] bytes = body().readAllBytes();
		return form = decode(new String(bytes, StandardCharsets.UTF_8));
	}

	@Override
	public synchronized InputStream body() throws IOException {
		if (null == bodyStream)
			bodyStream = new Bounded(body, maxBody);
		return bodyStream;
	}

	@Override
	public String header(String name) {
		return RequestViews.isHidden(name) ? null : headers.get(name);
	}

	@Override
	public Set<String> headerNames() {
		Set<String> names = new LinkedHashSet<>();
		for (String name : headers.keySet())
			if (!RequestViews.isHidden(name))
				names.add(name);
		return Collections.unmodifiableSet(names);
	}

	@Override
	public String cookie(String name) {
		return visibleCookies.contains(name) ? cookies.get(name) : null;
	}

	@Override
	public String clientAddress() {
		return clientAddress;
	}

	@Override
	public String clientType() {
		return clientType;
	}

	@Override
	public String requestId() {
		return requestId;
	}

	@Override
	public String scheme() {
		return scheme;
	}

	@Override
	public String toString() {
		return method + " " + path + (query.isEmpty() ? "" : "?" + query) + " from " + clientAddress;
	}

	/** {@code a=1&b=2}, decoded as UTF-8, in order. */
	static Map<String, List<String>> decode(String text) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		if (null == text || text.isEmpty())
			return out;
		for (String pair : text.split("&"))
		{
			if (pair.isEmpty())
				continue;
			int eq = pair.indexOf('=');
			String name = URLDecoder.decode((eq < 0) ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = (eq < 0) ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
		}
		return out;
	}

	/** The body stream: reading past the limit throws, as the node's does (it answers 413). */
	private static final class Bounded extends InputStream {
		private final byte[] body;
		private final int limit;
		private int pos;

		Bounded(byte[] body, int limit) {
			this.body = body;
			this.limit = limit;
		}

		private IOException overLimit() {
			return new IOException("the request body is over the limit of " + limit + " bytes - the node answers 413");
		}

		@Override
		public synchronized int read() throws IOException {
			if (pos >= body.length)
				return -1;
			if (pos >= limit)
				throw overLimit();
			return body[pos++] & 0xFF;
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) throws IOException {
			if (pos >= body.length)
				return -1;
			if (pos >= limit)
				throw overLimit();
			int n = Math.min(len, Math.min(body.length, limit) - pos);
			System.arraycopy(body, pos, b, off, n);
			pos += n;
			return n;
		}
	}
}
