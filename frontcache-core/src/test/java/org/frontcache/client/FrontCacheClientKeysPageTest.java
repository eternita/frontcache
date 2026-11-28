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
package org.frontcache.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link FrontCacheClient#getCachedKeysPage} against each kind of node it can meet: a paging one, one
 * that predates paging (ignores {@code limit} and lists everything), a refusal, an error, a page cut short.
 */
public class FrontCacheClientKeysPageTest {

	private interface Node {
		void answer(Map<String, String> params, HttpExchange exchange) throws IOException;
	}

	private HttpServer server;
	private volatile Node node;
	private final List<Map<String, String>> requests = Collections.synchronizedList(new ArrayList<Map<String, String>>());
	private FrontCacheClient client;

	@Before
	public void setUp() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			Map<String, String> params = new HashMap<String, String>();
			String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			for (String pair : form.split("&"))
				if (pair.contains("="))
					params.put(URLDecoder.decode(pair.substring(0, pair.indexOf('=')), StandardCharsets.UTF_8),
							URLDecoder.decode(pair.substring(pair.indexOf('=') + 1), StandardCharsets.UTF_8));
			requests.add(params);
			try {
				node.answer(params, exchange);
			} catch (IOException e) {
				// the client hung up - the legacy case does exactly that
			} finally {
				exchange.close();
			}
		});
		server.start();
		client = new FrontCacheClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "KEY");
	}

	@After
	public void tearDown() {
		server.stop(0);
	}

	private static void send(HttpExchange exchange, int status, Map<String, String> headers, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/** A paging node over a fixed key list, answering as FrontCacheIOServlet.writeKeysResponse does. */
	private static Node paging(List<String> all) {
		return (params, exchange) -> {
			String after = params.get("after");
			String prefix = params.get("prefix");
			int limit = Integer.parseInt(params.get("limit"));
			List<String> page = new ArrayList<String>();
			for (String key : all)
				if ((null == prefix || key.startsWith(prefix)) && (null == after || key.compareTo(after) > 0))
					page.add(key);
			boolean more = page.size() > limit;
			page = page.subList(0, Math.min(limit, page.size()));
			Map<String, String> headers = new HashMap<String, String>();
			headers.put(FCHeaders.X_FRONTCACHE_KEYS_COUNT, String.valueOf(page.size()));
			headers.put(FCHeaders.X_FRONTCACHE_KEYS_MORE, String.valueOf(more));
			StringBuilder body = new StringBuilder();
			for (String key : page)
				body.append(key).append('\n');
			send(exchange, 200, headers, body.toString());
		};
	}

	@Test
	public void aPageSendsItsParametersAndReadsCountAndMore() throws Exception {
		node = paging(Arrays.asList("/p/a", "/p/b", "/p/c", "/q/a"));

		CachedKeysPage page = client.getCachedKeysPage("/p/", "/p/a", 1);

		assertEquals(Arrays.asList("/p/b"), page.getKeys());
		assertTrue(page.isMore());
		assertEquals("/p/b", page.nextAfter());
		Map<String, String> sent = requests.get(0);
		assertEquals("get-cached-keys", sent.get("action"));
		assertEquals("1", sent.get("limit"));
		assertEquals("/p/", sent.get("prefix"));
		assertEquals("/p/a", sent.get("after"));
	}

	@Test
	public void forEachCachedKeyWalksEveryPageOnce() throws Exception {
		List<String> all = new ArrayList<String>();
		for (int i = 0; i < 23; i++)
			all.add(String.format("/k/%02d", i));
		node = paging(all);

		List<String> seen = new ArrayList<String>();
		assertEquals(23, client.forEachCachedKey(null, 5, seen::add));

		assertEquals(all, seen);
		assertEquals("5 full pages: the 5th says no more", 5, requests.size());
	}

	@Test
	public void aNodeThatPredatesPagingIsReportedAndItsListingIsNotRead() throws Exception {
		StringBuilder everything = new StringBuilder();
		for (int i = 0; i < 200_000; i++)
			everything.append("/legacy/").append(i).append('\n');
		node = (params, exchange) -> send(exchange, 200, new HashMap<String, String>(), everything.toString());

		assertThrows(CachedKeysPage.PagingNotSupportedException.class, () -> client.getCachedKeysPage(null, null, 10));
	}

	@Test
	public void aRefusalIsNotMistakenForAnOldNode() throws Exception {
		node = (params, exchange) -> send(exchange, 200, new HashMap<String, String>(),
				"{\"responseStatus\":\"ERROR\",\"errorDescription\":\"Access denied\"}");

		IOException thrown = assertThrows(IOException.class, () -> client.getCachedKeysPage(null, null, 10));
		assertFalse(thrown instanceof CachedKeysPage.PagingNotSupportedException);
		assertTrue(thrown.getMessage().contains("Access denied"));
	}

	@Test
	public void anHttpErrorIsAnException() throws Exception {
		node = (params, exchange) -> send(exchange, 500, new HashMap<String, String>(), "listing cached keys failed");

		IOException thrown = assertThrows(IOException.class, () -> client.getCachedKeysPage(null, null, 10));
		assertTrue(thrown.getMessage().contains("HTTP 500"));
	}

	@Test
	public void aPageShorterThanItsCountIsRefused() throws Exception {
		Map<String, String> headers = new HashMap<String, String>();
		headers.put(FCHeaders.X_FRONTCACHE_KEYS_COUNT, "3");
		headers.put(FCHeaders.X_FRONTCACHE_KEYS_MORE, "false");
		node = (params, exchange) -> send(exchange, 200, headers, "/a\n/b\n");

		IOException thrown = assertThrows(IOException.class, () -> client.getCachedKeysPage(null, null, 10));
		assertTrue(thrown.getMessage().contains("truncated"));
	}
}
