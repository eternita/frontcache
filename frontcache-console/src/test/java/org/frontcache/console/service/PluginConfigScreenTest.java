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
package org.frontcache.console.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.model.EdgeConfigFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The Plugin Configs screen's fan-outs: which plugins with a config the scope's edges run, and which edges get a tab
 * for one - those that run it, and those that did not answer; never one that answered without it.
 */
public class PluginConfigScreenTest {

	private Node withAlerts;
	private Node withoutPlugins;

	/** A node answering get-node-stats and get-plugin-config, recording every action it was sent. */
	private static final class Node {
		final HttpServer server;
		final List<Map<String, String>> calls = Collections.synchronizedList(new ArrayList<>());

		Node(String nodeStats, String pluginConfig) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/frontcache-io", exchange -> {
				Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				if (form.isEmpty() && null != exchange.getRequestURI().getRawQuery())
					form = form(exchange.getRequestURI().getRawQuery());
				calls.add(form);
				String body;
				switch (String.valueOf(form.get("action")))
				{
				case "get-node-stats":
					body = nodeStats;
					break;
				case "get-plugin-config":
					body = pluginConfig;
					break;
				default:
					body = "{\"action\":\"ping\",\"responseStatus\":\"OK\"}";
				}
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			});
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
		}

		private static Map<String, String> form(String encoded) {
			Map<String, String> form = new HashMap<>();
			for (String pair : encoded.split("&"))
			{
				int eq = pair.indexOf('=');
				if (0 < eq)
					form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
							URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
			return form;
		}
	}

	@Before
	public void setUp() throws IOException {
		withAlerts = new Node("{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\",\"plugins\":["
				+ "{\"id\":\"alerts\",\"state\":\"started\",\"config\":\"alerts.conf\",\"configStatus\":\"ok\"},"
				+ "{\"id\":\"warmer\",\"state\":\"started\"},"
				+ "{\"id\":\"broken\",\"state\":\"refused\",\"config\":\"broken.conf\"}]}",
				"{\"action\":\"get-plugin-config\",\"responseStatus\":\"OK\",\"plugin\":\"alerts\",\"file\":\"alerts.conf\","
						+ "\"pluginConfig\":\"alerts.threshold = 500\\n\"}");
		withoutPlugins = new Node("{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\",\"plugins\":[]}",
				"{\"action\":\"get-plugin-config\",\"responseStatus\":\"ERROR\",\"errorDescription\":\"no plugin\"}");
	}

	@After
	public void tearDown() {
		withAlerts.server.stop(0);
		withoutPlugins.server.stop(0);
	}

	private EdgeScope scope(String... urls) {
		FrontcacheService service = new FrontcacheService();
		for (String url : urls)
			service.addEdge(url);
		return new ConfiguredEdgeScope(service).everyEdge();
	}

	@Test
	public void thePluginsWithAConfigAreTheOnesSomeEdgeRunsAndWouldWrite() {
		FrontcacheService service = new FrontcacheService();
		Map<String, String> configs = service.getPluginConfigs(scope(withoutPlugins.url(), withAlerts.url()));

		assertEquals("a plugin without a config, and a refused one, are not offered", Map.of("alerts", "alerts.conf"),
				configs);
	}

	@Test
	public void anEdgeWithoutThePluginHasNoTabAndAnUnreachableOneKeepsIt() {
		FrontcacheService service = new FrontcacheService();
		List<EdgeConfigFile> edges = service.getPluginConfigFiles(
				scope(withoutPlugins.url(), withAlerts.url(), "http://127.0.0.1:1/"), "alerts");

		assertEquals(2, edges.size());
		assertEquals(withAlerts.url(), edges.get(0).getUrl());
		assertTrue(edges.get(0).isAvailable());
		assertEquals("alerts.threshold = 500\n", edges.get(0).getContent());
		assertEquals("http://127.0.0.1:1/", edges.get(1).getUrl());
		assertFalse(edges.get(1).isAvailable());
		assertNull(edges.get(1).getContent());

		Map<String, String> call = withAlerts.calls.get(withAlerts.calls.size() - 1);
		assertEquals("get-plugin-config", call.get("action"));
		assertEquals("alerts", call.get("plugin"));
	}

	@Test
	public void noPluginsIsAnEmptyScreen() {
		FrontcacheService service = new FrontcacheService();
		assertTrue(service.getPluginConfigs(scope(withoutPlugins.url(), "http://127.0.0.1:1/")).isEmpty());
	}
}
