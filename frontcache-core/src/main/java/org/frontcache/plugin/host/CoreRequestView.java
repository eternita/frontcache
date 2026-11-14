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

import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.RequestContext;
import org.frontcache.plugin.decide.RequestView;

/**
 * The {@link RequestView} a plugin's {@code ext:} predicate gets: read through the request's {@link RequestContext},
 * which memoizes what the guard already computed (client address, client type, cookie names). One per request, kept in
 * the context. Hides {@code Authorization}, {@code Proxy-Authorization}, {@code Cookie} and every
 * {@code x-frontcache-*} header - the warm token and the edge token travel in those. Never throws: a value that cannot be
 * read (a recycled request, a load-time probe) is null.
 */
final class CoreRequestView implements RequestView {

	private static final String KEY = "pluginRequestView";

	private final RequestContext context;

	private CoreRequestView(RequestContext context) {
		this.context = context;
	}

	static CoreRequestView of(RequestContext context) {
		Object cached = context.get(KEY);
		if (cached instanceof CoreRequestView view)
			return view;
		CoreRequestView view = new CoreRequestView(context);
		context.put(KEY, view);
		return view;
	}

	/** Whether the view hides a header. */
	static boolean hidden(String name) {
		if (null == name)
			return true;
		String lower = name.trim().toLowerCase(Locale.ROOT);
		return "authorization".equals(lower) || "proxy-authorization".equals(lower) || "cookie".equals(lower)
				|| lower.startsWith("x-frontcache-");
	}

	@Override
	public String method() {
		HttpServletRequest request = context.getRequest();
		if (null == request)
			return null;
		try {
			return request.getMethod();
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public String uri() {
		return safe(() -> context.getRequestURI());
	}

	@Override
	public String query() {
		String query = safe(() -> context.getRequestQueryString());
		if (null != query && query.startsWith("?"))
			query = query.substring(1);
		return (null == query || query.isEmpty()) ? null : query;
	}

	@Override
	public String host() {
		return safe(() -> context.getFrontCacheHost());
	}

	@Override
	public String header(String name) {
		if (hidden(name))
			return null;
		HttpServletRequest request = context.getRequest();
		if (null == request)
			return null;
		try {
			return request.getHeader(name);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public Set<String> cookieNames() {
		Set<String> names = safe(() -> context.getCookieNames());
		return (null == names) ? Set.of() : java.util.Collections.unmodifiableSet(names);
	}

	@Override
	public String clientAddress() {
		return safe(() -> context.getResolvedClientIP());
	}

	@Override
	public String clientType() {
		return safe(() -> context.getClientType());
	}

	@Override
	public String requestType() {
		return safe(() -> context.getRequestType());
	}

	private static <T> T safe(java.util.function.Supplier<T> value) {
		try {
			return value.get();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
