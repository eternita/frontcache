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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A request to a plugin's own path, as the {@link PluginEndpoint} sees it: a view the node builds, never the servlet
 * request.
 *
 * <h2>What it does not show</h2>
 *
 * {@code Authorization}, {@code Cookie} (a cookie is read through {@link #cookie}, and only one the plugin's manifest
 * names in {@code cookies}) and every {@code x-frontcache-*} header are not among the {@link #header headers}. The
 * body is a stream bounded by {@code front-cache.plugins.<id>.max-body} (default 64 KiB): reading past it throws, and
 * the node answers {@code 413}.
 */
public interface PluginRequest {

	/** {@code GET}, {@code POST}, ... */
	String method();

	/**
	 * The path under the plugin's own prefix, starting with {@code /}: {@code /fc-x/challenge/verify} is
	 * {@code /verify}, and {@code /fc-x/challenge} and {@code /fc-x/challenge/} are both {@code /}.
	 */
	String path();

	/** The query string without its {@code ?}; empty when there is none. */
	String queryString();

	/** The query string's parameters, decoded as UTF-8, in order; empty when there are none. */
	Map<String, List<String>> queryParameters();

	/** The first value of a query parameter, or null. */
	default String parameter(String name) {
		List<String> values = queryParameters().get(name);
		return (null == values || values.isEmpty()) ? null : values.get(0);
	}

	/**
	 * The body as {@code application/x-www-form-urlencoded} parameters, decoded as UTF-8 - read from {@link #body()},
	 * once. Empty when the request has another content type or no body.
	 *
	 * @throws IOException when the body cannot be read, or is over the limit
	 */
	Map<String, List<String>> formParameters() throws IOException;

	/** The body, bounded by the node's limit; an empty stream when there is none. Readable once. */
	InputStream body() throws IOException;

	/** A header's first value, or null - never one of the headers this view hides. Case-insensitive. */
	String header(String name);

	/** The names of the headers this view shows. */
	Set<String> headerNames();

	/** The value of a cookie the plugin's manifest names in {@code cookies}, or null - any other name is null. */
	String cookie(String name);

	/**
	 * The visitor's address as the node resolved it - through {@code front-cache.trusted-proxies} only, never a
	 * header a client could set - or null when it cannot be established. Personal data.
	 */
	String clientAddress();

	/** The node's verdict on the visitor, from {@code bots.conf}: {@code bot}, {@code guest}, ... */
	String clientType();

	/** The request's id, as the request log and every event carry it. */
	String requestId();

	/**
	 * The scheme the visitor used, as the node resolved it ({@code X-Forwarded-Proto} from a trusted proxy,
	 * {@code front-cache.public-scheme}, else the connection's): {@code http} or {@code https}.
	 */
	String scheme();
}
