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
package org.frontcache.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.service.ConfiguredEdgeScope;
import org.frontcache.console.service.FrontcacheService;
import org.frontcache.io.ReloadPluginConfigActionResponse;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sun.net.httpserver.HttpServer;

/**
 * The Plugins screen (2.13.0), rendered: the console's webapp on an embedded Jetty, as {@link ConsoleJettyServer}
 * deploys it - JSPs compiled and all - over stub nodes. One node reports a plugin of each kind and state (a bundled
 * observer, a serving plugin, a dropped-in, pinned decide plugin that is over its budget, a plugin directory that is
 * not enabled, one that failed on its config), one predates {@code get-node-stats}, one predates plugins, and one does
 * not answer. The page shows every one of them, escaped; <b>Reload config</b> reaches the node it names and shows its
 * answer; and an edge outside the scope reaches nothing.
 */
public class PluginsScreenTest {

	private static final String USER = "admin";
	private static final String PASSWORD = "pw-of-the-plugins-screen";

	/** A failing plugin's reason, as a dropped-in jar could word it - must never reach the page unescaped. */
	private static final String HOSTILE = "<script>alert('fc')</script>";

	@ClassRule
	public static TemporaryFolder folder = new TemporaryFolder();

	private static Node current;   // 2.13.0, every kind of plugin
	private static Node pre28;     // answers get-node-stats with a ping
	private static Node pre213;    // get-node-stats without plugins
	private static Node outside;   // not one of the console's edges
	private static Server jetty;
	private static String console;
	private static String previousConfig;

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	/** A node answering a fixed body per action, recording every call. */
	static final class Node {
		final HttpServer server;
		final Map<String, String> answers;
		final List<Map<String, String>> calls = Collections.synchronizedList(new ArrayList<>());

		Node(Map<String, String> answers) throws IOException {
			this.answers = answers;
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				if (form.isEmpty() && null != exchange.getRequestURI().getRawQuery())
					form = form(exchange.getRequestURI().getRawQuery());
				calls.add(form);
				String action = String.valueOf(form.get("action"));
				String body = answers.get(action + ":" + form.get("plugin"));
				if (null == body)
					body = answers.getOrDefault(action, "{\"action\":\"ping\",\"responseStatus\":\"OK\"}");
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

		List<Map<String, String>> callsOf(String action) {
			List<Map<String, String>> of = new ArrayList<>();
			synchronized (calls) {
				for (Map<String, String> call : calls)
					if (action.equals(call.get("action")))
						of.add(call);
			}
			return of;
		}
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

	private static final String NODE_STATS = """
			{"action":"get-node-stats","responseStatus":"OK","modules":["alerts"],"plugins":[
			 {"id":"alerts","name":"Alerts","version":"2.13.0","vendor":"Example Vendor","license":"Apache-2.0",
			  "pluginApi":1,"kinds":["observe","manage"],"state":"started","source":"bundled",
			  "eventsDelivered":4242,"eventsDropped":17,"errors":3,
			  "config":"alerts.conf","configStatus":"ok","configLoaded":1763100000000},
			 {"id":"gate","name":"Gate page","version":"1.0.0","pluginApi":1,"kinds":["serve"],"state":"started",
			  "serves":true,"cookies":["fc_gate"],"challenges":["pow"],"served":77,"serveRefused":5,"serveFailed":2},
			 {"id":"geoip","name":"GeoIP","version":"1.2.0","vendor":"Geo Inc","license":"MIT","pluginApi":1,
			  "kinds":["decide"],"requestPath":true,"state":"started","source":"dropped-in","directory":"plugins/geoip",
			  "jars":["geoip-1.2.0.jar 0123abcd"],"pinned":true,"predicates":["country"],
			  "config":"geoip.conf","configStatus":"ok",
			  "decideState":"over-budget","decideReason":"p99 2400 us over the 1000 us budget",
			  "decideBudgetMicros":1000,"decideP99Micros":2400.5,"decideCalls":98765,"decideErrors":4,
			  "decideSwitchedOff":2,"decideRetryAt":1763100060000},
			 {"id":"sitemap","state":"not-enabled","source":"dropped-in","directory":"plugins/sitemap",
			  "jars":["sitemap.jar 99ff"],"config":"sitemap.conf"},
			 {"id":"broken","name":"Broken","pluginApi":1,"kinds":["observe"],"state":"failed",
			  "reason":"%s","config":"broken.conf","configStatus":"refused",
			  "configErrors":["rules[0]: unknown key 'treshold'"]}
			]}""".formatted(HOSTILE.replace("'", "\\u0027"));

	@BeforeClass
	public static void startConsole() throws Exception {
		assumeTrue("FRONTCACHE_CONFIG is set in this environment and would win", null == System.getenv("FRONTCACHE_CONFIG"));

		current = new Node(Map.of(
				"get-node-stats", NODE_STATS,
				"reload-plugin-config:alerts", """
						{"action":"reload-plugin-config","responseStatus":"OK","plugin":"alerts","file":"alerts.conf",
						 "applied":true,"state":"started"}""",
				"reload-plugin-config:broken", """
						{"action":"reload-plugin-config","responseStatus":"ERROR","plugin":"broken","file":"broken.conf",
						 "applied":false,"state":"failed","errors":["rules[0]: unknown key 'treshold'"]}"""));
		pre28 = new Node(Map.of("get-cache-state",
				"{\"action\":\"get-cache-state\",\"responseStatus\":\"OK\",\"cacheStatus\":{\"cached-entries\":\"5\"}}"));
		pre213 = new Node(Map.of("get-node-stats", "{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\"}"));
		outside = new Node(Map.of());

		File conf = folder.newFile("frontcache-console.conf");
		Files.writeString(conf.toPath(), """
				frontcache.console {
				  urls = ["%s", "%s", "%s", "http://127.0.0.1:1/"]
				  apiKey = "key-of-the-plugins-screen"
				  user = %s
				  password = "%s"
				  config-editing = true
				  edge-editing = false
				}
				""".formatted(current.url(), pre28.url(), pre213.url(), USER, PASSWORD), StandardCharsets.UTF_8);

		previousConfig = System.getProperty("org.frontcache.console.config");
		System.setProperty("org.frontcache.console.config", conf.getAbsolutePath());

		jetty = new Server();
		ServerConnector connector = new ServerConnector(jetty);
		connector.setHost("127.0.0.1");
		connector.setPort(0);
		jetty.addConnector(connector);
		WebAppContext webapp = ConsoleJettyServer.webApp(new File("src/main/webapp").getAbsolutePath(), "/", "");
		webapp.setTempDirectory(folder.newFolder("jetty"));
		jetty.setHandler(webapp);
		jetty.start();
		console = "http://127.0.0.1:" + connector.getLocalPort();
	}

	@AfterClass
	public static void stopConsole() throws Exception {
		if (null != jetty)
			jetty.stop();
		for (Node node : new Node[] { current, pre28, pre213, outside })
			if (null != node)
				node.server.stop(0);
		if (null == previousConfig)
			System.clearProperty("org.frontcache.console.config");
		else
			System.setProperty("org.frontcache.console.config", previousConfig);
	}

	@Before
	public void forgetCalls() {
		for (Node node : new Node[] { current, pre28, pre213, outside })
			node.calls.clear();
	}

	private static String basic() {
		return "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> get(String path) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create(console + path)).header("Authorization", basic()).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> post(String path, Map<String, String> params) throws Exception {
		StringBuilder body = new StringBuilder();
		params.forEach((name, value) -> body.append(body.isEmpty() ? "" : "&")
				.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
				.append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
		return HTTP.send(HttpRequest.newBuilder(URI.create(console + path)).header("Authorization", basic())
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static int count(String page, String regex) {
		Matcher m = Pattern.compile(regex).matcher(page);
		int n = 0;
		while (m.find())
			n++;
		return n;
	}

	// ---- the screen -----------------------------------------------------------------------------------------

	@Test
	public void everyPluginOfEveryKindAndStateIsShown() throws Exception {
		HttpResponse<String> response = get("/plugins");
		assertEquals(response.body(), 200, response.statusCode());
		String page = response.body();

		for (String id : List.of("alerts", "gate", "geoip", "sitemap", "broken"))
			assertTrue("plugin " + id + " is shown", page.contains("data-plugin=\"" + id + "\""));

		// what it is
		assertTrue(page.contains("Example Vendor"));
		assertTrue(page.contains("Apache-2.0"));
		assertTrue(page.contains("2.13.0"));
		// the counters
		assertTrue(page.contains("4242"));
		assertTrue(page.contains("17"));
		// a serving plugin
		assertTrue(page.contains("/fc-x/gate/"));
		assertTrue(page.contains("fc_gate"));
		assertTrue(page.contains("77"));
		// the dropped-in decide plugin, over its budget - badged, with its reason, budget, p99 and retry time
		assertTrue(page.contains("plugins/geoip"));
		assertTrue(page.contains("geoip-1.2.0.jar 0123abcd"));
		assertTrue(page.contains(">pinned<"));
		assertEquals("the request-path badge, once in the legend and once on geoip", 2,
				count(page, "fc-request-path|label label-danger\">request path"));
		assertTrue(page.contains("fc-over-budget"));
		assertTrue(page.contains("p99 2400 us over the 1000 us budget"));
		assertTrue(page.contains("2400.5"));
		assertTrue(page.contains("98765"));
		assertTrue(page.contains("ext:country:"));
		assertTrue("the retry time, as a time", page.contains("2025-11-14 06:01:00 UTC"));
		// a plugin directory front-cache.plugins does not list
		assertTrue(page.contains("plugins/sitemap"));
		assertTrue(page.contains(">not-enabled<"));
		// one that failed on its config
		assertTrue(page.contains("rules[0]: unknown key &#039;treshold&#039;"));
		assertTrue(page.contains("fc-config-status\">refused"));
		// the config's last read
		assertTrue(page.contains("accepted 2025-11-14 06:00:00 UTC"));
	}

	@Test
	public void whatANodeSaysIsEscaped() throws Exception {
		String page = get("/plugins").body();
		assertFalse("a plugin's reason is never markup", page.contains(HOSTILE));
		assertTrue(page.contains("&lt;script&gt;alert(&#039;fc&#039;)&lt;/script&gt;"));
	}

	@Test
	public void aNodeWithoutPluginInformationSaysSo() throws Exception {
		String page = get("/plugins").body();
		assertTrue("the pre-2.8 node", page.contains("this node predates <code>get-node-stats</code>"));
		assertTrue("the node that reports no plugins", page.contains("this node runs no plugin, or predates plugins"));
		assertTrue("the node that does not answer", page.contains("http://127.0.0.1:1/ did not answer"));
		assertEquals("the pre-2.8 node is asked get-node-stats, then get-cache-state", 1,
				pre28.callsOf("get-cache-state").size());
	}

	@Test
	public void reloadIsOfferedForEveryLoadedPluginWithAConfig() throws Exception {
		String page = get("/plugins").body();
		// alerts, geoip, broken - not gate (no config), not sitemap (not enabled: nothing loaded to reload)
		assertEquals(3, count(page, "action=\"plugins-reload\""));
		assertFalse(page.contains("name=\"plugin\" value=\"sitemap\""));
		assertFalse(page.contains("name=\"plugin\" value=\"gate\""));
		assertTrue(page.contains("name=\"plugin\" value=\"broken\""));
		assertEquals("the Plugins tab is in the main menu, highlighted", 1,
				count(page, "href=\"plugins\" class=\"active-top\""));
	}

	// ---- the reload -----------------------------------------------------------------------------------------

	@Test
	public void aReloadReachesTheNodeItNamesAndShowsItsAnswer() throws Exception {
		HttpResponse<String> refused = post("/plugins-reload", Map.of("edge", current.url(), "plugin", "broken"));
		assertEquals(refused.body(), 200, refused.statusCode());
		List<Map<String, String>> reloads = current.callsOf("reload-plugin-config");
		assertEquals(1, reloads.size());
		assertEquals("broken", reloads.get(0).get("plugin"));
		assertTrue(refused.body().contains("config NOT reloaded"));
		assertTrue(refused.body().contains("<code>rules[0]: unknown key &#039;treshold&#039;</code></li>"));
		assertTrue("the screen is re-read from the nodes", refused.body().contains("data-plugin=\"geoip\""));

		HttpResponse<String> applied = post("/plugins-reload", Map.of("edge", current.url(), "plugin", "alerts"));
		assertEquals(200, applied.statusCode());
		assertEquals(applied.body(), 1, count(applied.body(), "config reloaded, now\\s+started"));
		assertEquals(2, current.callsOf("reload-plugin-config").size());
		assertEquals("no other node is reloaded", 0,
				pre28.callsOf("reload-plugin-config").size() + pre213.callsOf("reload-plugin-config").size());
	}

	@Test
	public void anEdgeOutsideTheScopeIsReachedByNothing() throws Exception {
		HttpResponse<String> response = post("/plugins-reload", Map.of("edge", outside.url(), "plugin", "alerts"));
		assertEquals(200, response.statusCode());
		assertTrue(response.body(), response.body().contains("not one of this console&#039;s edges"));
		assertEquals("the outside node is never called", List.of(), outside.calls);
		assertEquals(0, current.callsOf("reload-plugin-config").size());
	}

	/** Without config editing (here: a console without credentials) the reload is refused before any node is called. */
	@Test
	public void aConsoleWithoutConfigEditingReloadsNothing() {
		String previous = System.getProperty("org.frontcache.console.config");
		System.clearProperty("org.frontcache.console.config");
		try {
			FrontcacheService service = new FrontcacheService();
			service.addEdge(current.url());
			EdgeScope scope = new ConfiguredEdgeScope(service).everyEdge();
			assertFalse(service.isConfigEditingEnabled());

			ReloadPluginConfigActionResponse answer = service.reloadPluginConfig(scope, current.url(), "alerts");
			assertEquals("ERROR", answer.getResponseStatus());
			assertFalse(answer.isApplied());
			assertEquals("config editing is disabled on this console", answer.getErrorDescription());
			assertEquals(List.of(), current.callsOf("reload-plugin-config"));
		} finally {
			System.setProperty("org.frontcache.console.config", previous);
		}
	}
}
