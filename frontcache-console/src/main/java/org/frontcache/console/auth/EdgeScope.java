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
package org.frontcache.console.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.frontcache.client.FrontCacheClient;

/**
 * The edges one request may reach, each with the API key it is called with, in display order - as of 2.12.0, what
 * {@link ConsoleEdgeScope} answers and every controller, the stream proxy and every plugin bean works through.
 *
 * <b>Matching is by {@link #canonical canonical URL}</b> - the trailing slash does not matter - and never by
 * {@link FrontCacheClient#getName()}, which strips the scheme and the port. A client is always built from the
 * CONFIGURED spelling, not the caller's, and with that edge's own key: an edge a caller names that is not in the scope
 * gets no client at all, so no key is ever sent to a host a request chose.
 *
 * {@link #id()} keys whatever the console computes for a scope and keeps - a polled status, a replication run - so
 * that one scope never reads another's.
 *
 * Immutable. One URL with two keys is refused at construction: which key it is called with must never depend on
 * iteration order.
 */
public final class EdgeScope {

	/**
	 * One edge: the URL as configured, its API key (null = the node needs none) and, as of 2.13.0, an optional label
	 * the console shows beside it (an {@code edges} entry's {@code label}; null = none). Two edges differing only in
	 * label are the same edge to {@link #of}.
	 */
	public record Edge(String url, String apiKey, String label) {
		public Edge {
			Objects.requireNonNull(url, "url");
		}

		public Edge(String url, String apiKey) {
			this(url, apiKey, null);
		}

		/** The key is never printed. */
		@Override
		public String toString() {
			return "Edge[" + url + (null == label ? "" : ", " + label) + "]";
		}
	}

	private final String id;

	/** canonical URL -> edge, in display order */
	private final Map<String, Edge> edges;

	/** Told of every edge {@link #client} was asked for and does not hold - null: nobody listens. */
	private final Consumer<String> refusals;

	private EdgeScope(String id, Map<String, Edge> edges) {
		this(id, Collections.unmodifiableMap(edges), null);
	}

	private EdgeScope(String id, Map<String, Edge> edges, Consumer<String> refusals) {
		this.id = Objects.requireNonNull(id, "id");
		this.edges = edges;
		this.refusals = refusals;
	}

	/**
	 * The same scope - same id, same edges - telling {@code refusals} of every edge a caller asks {@link #client} for
	 * and does not get: what the audit records as {@code edge.denied}. Only {@link #client} reports, because it is what
	 * a handler acting on an edge calls; {@link #contains} and {@link #edge} are questions, and an authorizer asking
	 * one is no refusal. A blank URL ("no edge selected") is not reported.
	 */
	public EdgeScope withRefusals(Consumer<String> refusals) {
		return new EdgeScope(id, edges, refusals);
	}

	/**
	 * @param id    the scope's identity - equal scopes share cached results, so two principals who may see different
	 *              edges must never get one id
	 * @param edges in display order
	 * @throws IllegalArgumentException when one URL appears twice with different keys
	 */
	public static EdgeScope of(String id, Collection<Edge> edges) {

		Map<String, Edge> byURL = new LinkedHashMap<String, Edge>();
		for (Edge edge : edges)
		{
			String key = canonical(edge.url());
			if (null == key || key.isEmpty())
				continue;

			Edge previous = byURL.get(key);
			if (null != previous && !Objects.equals(previous.apiKey(), edge.apiKey()))
				throw new IllegalArgumentException("edge " + key + " appears twice in scope '" + id
						+ "' with different API keys");

			if (null == previous)
				byURL.put(key, edge);
		}
		return new EdgeScope(id, byURL);
	}

	/** Every URL with one key - the single-tenant console's scope. */
	public static EdgeScope of(String id, Collection<String> urls, String apiKey) {

		List<Edge> edges = new ArrayList<Edge>();
		for (String url : urls)
			if (null != url)
				edges.add(new Edge(url, apiKey));
		return of(id, edges);
	}

	/** A scope with no edge. */
	public static EdgeScope empty(String id) {
		return new EdgeScope(id, new LinkedHashMap<String, Edge>());
	}

	public String id() {
		return id;
	}

	/** The edge URLs as configured, in display order. */
	public List<String> edgeURLs() {
		List<String> urls = new ArrayList<String>();
		for (Edge edge : edges.values())
			urls.add(edge.url());
		return urls;
	}

	/** The edges, in display order. */
	public List<Edge> edges() {
		return new ArrayList<Edge>(edges.values());
	}

	public boolean contains(String edgeURL) {
		return null != edge(edgeURL);
	}

	/** The edge a request named, or null when it is not in this scope. */
	public Edge edge(String edgeURL) {
		String key = canonical(edgeURL);
		return null == key || key.isEmpty() ? null : edges.get(key);
	}

	/**
	 * A client for the edge a request named, built from the configured spelling with that edge's key - or null when
	 * it is not in this scope.
	 */
	public FrontCacheClient client(String edgeURL) {
		Edge edge = edge(edgeURL);
		if (null == edge)
		{
			if (null != refusals && null != edgeURL && !edgeURL.isBlank())
				refusals.accept(edgeURL.trim());
			return null;
		}
		return new FrontCacheClient(edge.url(), edge.apiKey());
	}

	/** A client for every edge, in display order. */
	public List<FrontCacheClient> clients() {
		List<FrontCacheClient> clients = new ArrayList<FrontCacheClient>();
		for (Edge edge : edges.values())
			clients.add(new FrontCacheClient(edge.url(), edge.apiKey()));
		return clients;
	}

	public int size() {
		return edges.size();
	}

	public boolean isEmpty() {
		return edges.isEmpty();
	}

	/**
	 * An edge URL as it is compared and keyed: trimmed, with one trailing slash. The stream list carries
	 * {@code https://fc-edge.example.com:443/} while {@code urls} may say {@code https://fc-edge.example.com:443} -
	 * comparing the raw forms makes a match fail on a config that reads perfectly correctly.
	 */
	public static String canonical(String edgeURL) {

		if (null == edgeURL)
			return null;

		String canonical = edgeURL.trim();
		if (canonical.isEmpty())
			return canonical;

		return canonical.endsWith("/") ? canonical : canonical + "/";
	}

	/** The id and URLs - never the keys. */
	@Override
	public String toString() {
		return "EdgeScope[" + id + ", " + edges.keySet() + "]";
	}
}
