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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.frontcache.plugin.decide.RequestView;

/**
 * {@link RequestView}s for a predicate's tests: a builder, the all-null view the node's load-time probe passes, and
 * the fixed corpus {@link PluginContract} times predicates over. A view built here hides what the node's view hides -
 * {@code Authorization}, {@code Proxy-Authorization}, {@code Cookie} and every {@code x-frontcache-*} header answer
 * null whatever was set.
 */
public final class RequestViews {

	private RequestViews() {
	}

	/** Whether the node's views hide this header. */
	public static boolean isHidden(String header) {
		if (null == header)
			return true;
		String h = header.toLowerCase(Locale.ROOT);
		return h.equals("authorization") || h.equals("proxy-authorization") || h.equals("cookie")
				|| h.startsWith("x-frontcache-");
	}

	/** A GET of {@code pathAndQuery} ({@code /a/b?x=1}) on {@code www.example.com}, a top-level request. */
	public static Builder get(String pathAndQuery) {
		return request("GET", pathAndQuery);
	}

	public static Builder request(String method, String pathAndQuery) {
		Builder b = new Builder();
		b.method = method;
		int q = (null == pathAndQuery) ? -1 : pathAndQuery.indexOf('?');
		b.uri = (q < 0) ? pathAndQuery : pathAndQuery.substring(0, q);
		b.query = (q < 0) ? null : pathAndQuery.substring(q + 1);
		b.host = "www.example.com";
		b.requestType = "toplevel";
		b.cookieNames = new LinkedHashSet<>();
		return b;
	}

	/**
	 * The view with nothing behind it: every answer null but {@code cookieNames()}, which is empty - what the node's synthetic
	 * probe passes, and what a predicate must answer for rather than throw.
	 */
	public static RequestView empty() {
		return new Builder().build();
	}

	/**
	 * A fixed, varied corpus: methods, shallow and deep paths, queries, hosts, browsers and crawlers, IPv4, IPv6 and
	 * unresolved addresses, client and request types, a few nulls.
	 */
	public static List<RequestView> corpus() {
		String chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
		String safari = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
		String googlebot = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
		String bingbot = "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)";
		List<RequestView> corpus = new ArrayList<>();
		corpus.add(get("/").header("User-Agent", chrome).clientAddress("203.0.113.7").clientType("browser").build());
		corpus.add(get("/index.html").header("User-Agent", safari).clientAddress("198.51.100.23").clientType("browser").build());
		corpus.add(get("/products/shoes/running?size=42&color=red").header("User-Agent", chrome)
				.header("Accept-Language", "de-DE,de;q=0.9").cookie("session").clientAddress("203.0.113.9")
				.clientType("browser").build());
		corpus.add(get("/a/b/c/d/e/f/g/h").header("User-Agent", "curl/8.7.1").clientAddress("192.0.2.1")
				.clientType("guest").build());
		corpus.add(get("/search?q=" + "x".repeat(400)).header("User-Agent", "python-requests/2.32").clientAddress("192.0.2.77")
				.clientType("guest").build());
		corpus.add(get("/robots.txt").header("User-Agent", googlebot).clientAddress("66.249.66.1").clientType("bot").build());
		corpus.add(get("/sitemap.xml").header("User-Agent", bingbot).clientAddress("157.55.39.10").clientType("bot").build());
		corpus.add(get("/").header("User-Agent", googlebot).clientAddress("2001:4860:4801:10::1").clientType("bot").build());
		corpus.add(get("/de/").header("User-Agent", chrome).clientAddress("2001:db8::7").clientType("browser").build());
		corpus.add(request("POST", "/checkout/payment").header("User-Agent", chrome).header("Content-Type",
				"application/x-www-form-urlencoded").cookie("session").cookie("cart").clientAddress("203.0.113.7")
				.clientType("browser").build());
		corpus.add(request("POST", "/api/v1/orders").header("Content-Type", "application/json").clientAddress("198.51.100.200")
				.clientType("guest").build());
		corpus.add(request("HEAD", "/").clientAddress("203.0.113.50").clientType("guest").build());
		corpus.add(request("OPTIONS", "/api/v1/products").header("Origin", "https://shop.example.net")
				.clientAddress("203.0.113.51").build());
		corpus.add(request("DELETE", "/api/v1/cart/7").clientAddress("203.0.113.52").clientType("guest").build());
		corpus.add(get("/include/header.html").clientAddress("203.0.113.7").clientType("browser").requestType("include").build());
		corpus.add(get("/include/recommendations?product=1234").clientAddress("203.0.113.7").clientType("browser")
				.requestType("include-async").build());
		corpus.add(get("/wp-login.php").header("User-Agent", "Mozilla/5.0").clientAddress("192.0.2.200").clientType("guest").build());
		corpus.add(get("/.env").clientAddress("192.0.2.201").clientType("guest").build());
		corpus.add(get("/../../etc/passwd").clientAddress("192.0.2.202").build());
		corpus.add(get("/%E2%9C%93/caf%C3%A9?q=%3Cscript%3E").header("User-Agent", chrome).clientAddress("203.0.113.8").build());
		corpus.add(get("/").host("example.com").header("User-Agent", chrome).clientAddress("203.0.113.10").build());
		corpus.add(get("/").host("WWW.EXAMPLE.COM").header("X-Forwarded-For", "10.0.0.1").clientAddress("203.0.113.11").build());
		corpus.add(get("/p?" + "a=1&".repeat(60)).clientAddress("203.0.113.12").build());
		corpus.add(get("/" + "segment/".repeat(40)).clientAddress("203.0.113.13").build());
		corpus.add(get("/no-address").header("User-Agent", chrome).build());
		corpus.add(get("/no-type").clientAddress("203.0.113.14").build());
		corpus.add(get("/").header("User-Agent", "").clientAddress("203.0.113.15").clientType("guest").build());
		corpus.add(get("/x").header("Authorization", "Basic c2VjcmV0").header("Cookie", "session=s3cr3t")
				.header("x-frontcache-debug", "true").cookie("session").clientAddress("203.0.113.16").build());
		corpus.add(get("").clientAddress("203.0.113.17").build());
		corpus.add(get("/api/v1/products?page=2&per-page=50").header("Accept", "application/json")
				.clientAddress("::ffff:203.0.113.18").clientType("guest").build());
		corpus.add(empty());
		return Collections.unmodifiableList(corpus);
	}

	/** Builds a {@link RequestView}; everything not set is null - but {@code cookieNames()}, which is never null. */
	public static final class Builder {

		private String method;
		private String uri;
		private String query;
		private String host;
		private final TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		private Set<String> cookieNames;
		private String clientAddress;
		private String clientType;
		private String visitorType;
		private String requestType;

		private Builder() {
		}

		public Builder method(String method) {
			this.method = method;
			return this;
		}

		/** The path, without the query string. */
		public Builder uri(String uri) {
			this.uri = uri;
			return this;
		}

		/** The query string without its {@code ?}, or null. */
		public Builder query(String query) {
			this.query = query;
			return this;
		}

		public Builder host(String host) {
			this.host = host;
			return this;
		}

		/** Sets a header - one the node's view hides is accepted here and answers null, as on the node. */
		public Builder header(String name, String value) {
			headers.put(name, value);
			return this;
		}

		/** A cookie the request carries - a predicate sees its name, never a value. */
		public Builder cookie(String name) {
			if (null == cookieNames)
				cookieNames = new LinkedHashSet<>();
			cookieNames.add(name);
			return this;
		}

		public Builder clientAddress(String clientAddress) {
			this.clientAddress = clientAddress;
			return this;
		}

		public Builder clientType(String clientType) {
			this.clientType = clientType;
			return this;
		}

		/** A declared visitor type ({@code customer}); unset, the view's visitor type is its client type. */
		public Builder visitorType(String visitorType) {
			this.visitorType = visitorType;
			return this;
		}

		/** {@code toplevel}, {@code include} or {@code include-async}. */
		public Builder requestType(String requestType) {
			this.requestType = requestType;
			return this;
		}

		public RequestView build() {
			return new View(method, uri, query, host, new TreeMap<>(headers),
					(null == cookieNames) ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(cookieNames)),
					clientAddress, clientType, (null != visitorType) ? visitorType : clientType, requestType);
		}
	}

	private record View(String method, String uri, String query, String host, Map<String, String> headers,
			Set<String> cookieNames, String clientAddress, String clientType, String visitorType, String requestType)
			implements RequestView {

		@Override
		public String header(String name) {
			if (isHidden(name))
				return null;
			return headers.get(name);
		}

		@Override
		public String toString() {
			return (null == method && null == uri) ? "RequestView(empty)"
					: "RequestView(" + method + " " + uri + ((null == query) ? "" : "?" + query) + " from " + clientAddress
							+ ", " + clientType + ", " + requestType + ")";
		}
	}
}
