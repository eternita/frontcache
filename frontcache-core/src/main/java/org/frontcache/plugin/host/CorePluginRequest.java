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
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.RequestContext;
import org.frontcache.plugin.serve.PluginRequest;

/**
 * The node's {@link PluginRequest}: a view over one request to {@code /fc-x/<id>/}, hiding what a plugin must not see
 * - {@code Authorization}, {@code Proxy-Authorization}, {@code Cookie} (a cookie the manifest names is read through
 * {@link #cookie}) and every {@code x-frontcache-*} header, the API key's among them.
 */
final class CorePluginRequest implements PluginRequest {

	/** Thrown when a body is read past the plugin's limit; the node answers 413. */
	static final class BodyTooLarge extends IOException {
		private static final long serialVersionUID = 1L;

		BodyTooLarge(long limit) {
			super("the request body is over " + limit + " bytes");
		}
	}

	private final RequestContext context;
	private final String pluginId;
	private final String path;
	private final Set<String> cookies;
	private final long maxBody;

	private InputStream body;
	private Map<String, List<String>> query;
	private Map<String, List<String>> form;

	CorePluginRequest(RequestContext context, String pluginId, String path, Set<String> cookies, long maxBody) {
		this.context = context;
		this.pluginId = pluginId;
		this.path = path;
		this.cookies = cookies;
		this.maxBody = maxBody;
	}

	RequestContext context() {
		return context;
	}

	String pluginId() {
		return pluginId;
	}

	private HttpServletRequest servlet() {
		return context.getRequest();
	}

	/** @return whether a header is one this view hides */
	static boolean hidden(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return "authorization".equals(lower) || "proxy-authorization".equals(lower) || "cookie".equals(lower)
				|| lower.startsWith("x-frontcache-");
	}

	@Override
	public String method() {
		return (null == servlet()) ? "GET" : servlet().getMethod();
	}

	@Override
	public String path() {
		return path;
	}

	@Override
	public String queryString() {
		String query = context.getRequestQueryString();
		if (null == query)
			return "";
		return query.startsWith("?") ? query.substring(1) : query;
	}

	@Override
	public Map<String, List<String>> queryParameters() {
		if (null == query)
			query = Collections.unmodifiableMap(parse(queryString()));
		return query;
	}

	@Override
	public Map<String, List<String>> formParameters() throws IOException {
		if (null != form)
			return form;

		String contentType = header("Content-Type");
		if (null == contentType
				|| !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded"))
		{
			form = Map.of();
			return form;
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		body().transferTo(bytes);
		form = Collections.unmodifiableMap(parse(bytes.toString(StandardCharsets.UTF_8)));
		return form;
	}

	@Override
	public InputStream body() throws IOException {
		if (null == body)
			body = (null == servlet()) ? InputStream.nullInputStream()
					: new Bounded(servlet().getInputStream(), maxBody);
		return body;
	}

	@Override
	public String header(String name) {
		if (null == name || hidden(name) || null == servlet())
			return null;
		return servlet().getHeader(name);
	}

	@Override
	public Set<String> headerNames() {
		Set<String> names = new LinkedHashSet<>();
		if (null == servlet())
			return names;
		Enumeration<String> all = servlet().getHeaderNames();
		while (null != all && all.hasMoreElements())
		{
			String name = all.nextElement();
			if (!hidden(name))
				names.add(name);
		}
		return Collections.unmodifiableSet(names);
	}

	@Override
	public String cookie(String name) {
		if (null == name || !cookies.contains(name))
			return null;
		List<String> values = context.getCookieValues(name);
		return values.isEmpty() ? null : values.get(0);
	}

	@Override
	public String clientAddress() {
		return context.getResolvedClientIP();
	}

	@Override
	public String clientType() {
		return context.getClientType();
	}

	@Override
	public String visitorType() {
		return context.getVisitorType();
	}

	@Override
	public String requestId() {
		return context.getRequestId();
	}

	@Override
	public String scheme() {
		String scheme = context.getPublicProtocol();
		if (null == scheme)
			scheme = context.getFrontCacheProtocol();
		return (null == scheme) ? "http" : scheme;
	}

	/** {@code a=1&b=2&a=3} as a map of lists, decoded as UTF-8; a malformed pair is kept as written. */
	static Map<String, List<String>> parse(String encoded) {
		Map<String, List<String>> parameters = new LinkedHashMap<>();
		if (null == encoded || encoded.isEmpty())
			return parameters;
		for (String pair : encoded.split("&"))
		{
			if (pair.isEmpty())
				continue;
			int eq = pair.indexOf('=');
			String name = decode((-1 < eq) ? pair.substring(0, eq) : pair);
			String value = (-1 < eq) ? decode(pair.substring(eq + 1)) : "";
			parameters.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
		}
		for (Map.Entry<String, List<String>> e : parameters.entrySet())
			e.setValue(Collections.unmodifiableList(e.getValue()));
		return parameters;
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return value;
		}
	}

	/** A body stream that refuses to be read past the limit. */
	private static final class Bounded extends InputStream {
		private final InputStream in;
		private final long limit;
		private long read;

		Bounded(InputStream in, long limit) {
			this.in = in;
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (-1 != b && ++read > limit)
				throw new BodyTooLarge(limit);
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = in.read(buffer, offset, (int) Math.min(length, limit - read + 1));
			if (0 < n)
			{
				read += n;
				if (read > limit)
					throw new BodyTooLarge(limit);
			}
			return n;
		}
	}
}
