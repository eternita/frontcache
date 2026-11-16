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
package org.frontcache.examples.crawlerfeeder;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches one vendor file and turns it into a list's lines. Everything that is not a good answer is an
 * {@link IOException} - a status other than 200 (a redirect is not followed), a body over {@link #MAX_BYTES}, a body
 * that is not the expected JSON, a file with no prefix at all - and the caller then keeps the list it has.
 */
final class FeedFetcher {

	/** Google's file is about 20 KB; anything near this is not a crawler list. */
	static final int MAX_BYTES = 4 * 1024 * 1024;

	private final HttpClient client;
	private final Duration timeout;
	private final String userAgent;

	FeedFetcher(Duration timeout, String userAgent) {
		this.timeout = timeout;
		this.userAgent = userAgent;
		this.client = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	/** The lines for {@code conf/lists/<name>.txt}: two comments, then every prefix once, in the file's order. */
	List<String> fetch(FeederConfig.Feed feed) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(feed.url())
				.timeout(timeout)
				.header("Accept", "application/json")
				.header("User-Agent", userAgent)
				.GET()
				.build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		byte[] body;
		try (InputStream in = response.body()) {
			if (200 != response.statusCode())
				throw new IOException("HTTP " + response.statusCode() + ((response.statusCode() / 100 == 3)
						? " - a redirect is not followed" : ""));
			body = in.readNBytes(MAX_BYTES + 1);
		}
		if (body.length > MAX_BYTES)
			throw new IOException("the answer is over " + MAX_BYTES + " bytes");

		List<String> prefixes = prefixes(new String(body, StandardCharsets.UTF_8));
		List<String> lines = new ArrayList<>();
		lines.add("# " + feed.name() + ": " + feed.url());
		lines.add("# fetched " + Instant.now() + " - " + prefixes.size() + " prefixes");
		lines.addAll(prefixes);
		return lines;
	}

	/**
	 * The prefixes of {@code {"prefixes":[{"ipv4Prefix":"..."},{"ipv6Prefix":"..."}]}}, each once.
	 *
	 * @throws IOException when the text is not that, or holds no prefix
	 */
	static List<String> prefixes(String json) throws IOException {
		Object root;
		try {
			root = Json.parse(json);
		} catch (IllegalArgumentException e) {
			throw new IOException("the answer is " + e.getMessage());
		}
		if (!(root instanceof Map<?, ?> map) || !(map.get("prefixes") instanceof List<?> entries))
			throw new IOException("the answer has no \"prefixes\" list");
		Set<String> prefixes = new LinkedHashSet<>();
		for (Object entry : entries)
		{
			if (!(entry instanceof Map<?, ?> e))
				throw new IOException("a \"prefixes\" entry is not an object");
			Object prefix = (null != e.get("ipv4Prefix")) ? e.get("ipv4Prefix") : e.get("ipv6Prefix");
			if (!(prefix instanceof String s) || s.isBlank())
				throw new IOException("a \"prefixes\" entry has neither ipv4Prefix nor ipv6Prefix");
			prefixes.add(s.strip());
		}
		if (prefixes.isEmpty())
			throw new IOException("the answer lists no prefix - an empty list is never written");
		return new ArrayList<>(prefixes);
	}

	void close() {
		client.shutdownNow();
	}
}
