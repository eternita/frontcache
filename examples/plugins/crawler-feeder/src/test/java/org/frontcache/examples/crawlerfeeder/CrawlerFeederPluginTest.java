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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.frontcache.io.ActionResponse;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.testkit.FakeManagementRequest;
import org.frontcache.plugin.testkit.PluginContract;
import org.frontcache.plugin.testkit.PluginHarness;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The feeder against a loopback HTTP server standing in for the vendors - no network in the tests.
 */
public class CrawlerFeederPluginTest extends PluginContract {

	private static final String GOOGLE = """
			{
			  "creationTime": "2026-11-14T00:00:00.000000",
			  "prefixes": [
			    {"ipv6Prefix": "2001:4860:4801:10::/64"},
			    {"ipv4Prefix": "66.249.64.0/27"},
			    {"ipv4Prefix": "66.249.66.0/27"},
			    {"ipv4Prefix": "66.249.66.0/27"}
			  ]
			}
			""";

	private static final String BING = "{\"creationTime\":\"2026-11-14T00:00:00\",\"prefixes\":[{\"ipv4Prefix\":\"157.55.39.0/24\"},{\"ipv4Prefix\":\"207.46.13.0/24\"}]}";

	private HttpServer server;
	private String base;
	/** path -> [status, body] */
	private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
	private final AtomicInteger hits = new AtomicInteger();

	@Before
	public void startVendor() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			hits.incrementAndGet();
			Object[] answer = answers.getOrDefault(exchange.getRequestURI().getPath(), new Object[] { 404, "" });
			byte[] body = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
			if (302 == (int) answer[0])
				exchange.getResponseHeaders().add("Location", base + "/googlebot.json");
			exchange.sendResponseHeaders((int) answer[0], body.length == 0 ? -1 : body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		answers.put("/googlebot.json", new Object[] { 200, GOOGLE });
		answers.put("/bingbot.json", new Object[] { 200, BING });
	}

	@After
	public void stopVendor() {
		server.stop(0);
	}

	@Override
	protected Class<? extends FrontcachePlugin> pluginClass() {
		return CrawlerFeederPlugin.class;
	}

	/** The contract's own runs fetch nothing - no feed, so no network. */
	@Override
	protected String startConfig() {
		return "feeds = []";
	}

	@Override
	protected List<String> acceptedConfigs() {
		// all loopback: the contract applies them to the running plugin, which fetches at once (port 9 refuses)
		return List.of("feeds = []", "feeds = [ { name = googlebot, url = \"http://127.0.0.1:9/g.json\" } ]",
				"feeds = [ { name = a.b_c-1, url = \"http://localhost:9/a\", every = 1m }, { name = b, url = \"http://[::1]:9/b\", every = 30d } ]\ntimeout = 2s");
	}

	@Override
	protected List<String> refusedConfigs() {
		return List.of(
				"feeds = [ { name = googlebot, url = \"http://example.com/g.json\" } ]",
				"feeds = [ { name = googlebot, url = \"ftp://127.0.0.1/g.json\" } ]",
				"feeds = [ { name = googlebot, url = \"https://user:pw@example.com/g.json\" } ]",
				"feeds = [ { name = Googlebot, url = \"https://example.com/g.json\" } ]",
				"feeds = [ { name = \"../x\", url = \"https://example.com/g.json\" } ]",
				"feeds = [ { name = g, url = \"https://example.com/1\" }, { name = g, url = \"https://example.com/2\" } ]",
				"feeds = [ { name = g, url = \"https://example.com/1\", every = 10s } ]",
				"feeds = [ { name = g, url = \"https://example.com/1\", evry = 1h } ]",
				"feeds = [ { url = \"https://example.com/1\" } ]",
				"feeds = \"googlebot\"",
				"feeds = [ \"googlebot\" ]",
				"timeout = 0s",
				"feed = []");
	}

	private void feeds(String... nameAndPath) throws Exception {
		StringBuilder hocon = new StringBuilder("feeds = [");
		for (int i = 0; i < nameAndPath.length; i += 2)
			hocon.append((0 == i) ? " " : ", ").append("{ name = ").append(nameAndPath[i]).append(", url = \"").append(base).append(nameAndPath[i + 1])
					.append("\", every = 1h }");   // the comma: two objects side by side on one line are MERGED in HOCON
		harness.configure(hocon.append(" ]\ntimeout = 2s").toString());
	}

	private FeederStatusResponse status() throws IOException {
		return (FeederStatusResponse) harness.call("crawler-feeder-status");
	}

	private void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!condition.getAsBoolean())
		{
			if (System.currentTimeMillis() > deadline)
				fail("timed out waiting for " + what + " - " + describe());
			Thread.sleep(20);
		}
	}

	private boolean succeeded(String feed) {
		FeederStatusResponse.FeedStatus f = statusQuietly().feed(feed);
		return null != f && null != f.getLastSuccess();
	}

	private boolean listHas(String list, String entry) {
		try {
			return harness.context().readList(list).contains(entry);
		} catch (IOException e) {
			return false;
		}
	}

	private void refreshAndWait(String feed) throws Exception {
		FeederStatusResponse before = status();
		String lastAttempt = before.feed(feed).getLastAttempt();
		ActionResponse r = harness.call(FakeManagementRequest.of("crawler-feeder-refresh").parameter("feed", feed)
				.writeAuthorized(true));
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, r.getResponseStatus());
		await("a new attempt of " + feed, () -> {
			try {
				String now = status().feed(feed).getLastAttempt();
				return null != now && !now.equals(lastAttempt) && !((CrawlerFeederPlugin) harness.plugin())
						.isRunning(feed);
			} catch (IOException e) {
				return false;
			}
		});
	}

	@Test
	public void itWritesTheVendorsPrefixesIntoTheirLists() throws Exception {
		feeds("googlebot", "/googlebot.json", "bingbot", "/bingbot.json");
		await("both lists", () -> harness.context().listFile("googlebot").isFile()
				&& harness.context().listFile("bingbot").isFile());
		await("both successes", () -> succeeded("googlebot") && succeeded("bingbot"));

		List<String> google = harness.context().readList("googlebot");
		assertEquals("# managed by plugin crawler-feeder", google.get(0));
		assertTrue(google.contains("66.249.64.0/27"));
		assertTrue(google.contains("2001:4860:4801:10::/64"));
		assertEquals("each prefix once", 1, google.stream().filter("66.249.66.0/27"::equals).count());
		assertTrue(harness.context().readList("bingbot").contains("207.46.13.0/24"));

		FeederStatusResponse.FeedStatus g = status().feed("googlebot");
		assertEquals(3, g.getBlocks());
		assertNull(g.getLastError());
		assertEquals("lists/googlebot.txt", g.getList());
		assertTrue("the next fetch is an hour away", g.getNextRunSeconds() > 3_000);
	}

	@Test
	public void aFailedOrStrangeAnswerKeepsTheListItHad() throws Exception {
		feeds("googlebot", "/googlebot.json");
		await("the list", () -> succeeded("googlebot"));
		List<String> good = harness.context().readList("googlebot");

		Object[][] bad = {
				{ 500, "oops" },
				{ 302, "" },
				{ 200, "<html>not json</html>" },
				{ 200, "{\"prefixes\": []}" },
				{ 200, "{\"items\": []}" },
				{ 200, "{\"prefixes\": [{\"ipv4Prefix\": \"66.249.64.0/27\"}, {\"ipv4Prefix\": \"evil.example.com\"}]}" },
				{ 200, "{\"prefixes\": [{\"ipv4Prefix\": \"66.249.64.0/27\"}, {\"ipv4Prefix\": \"10.0.0.0/8\\n# x\"}]}" },
				{ 404, "" } };
		for (Object[] answer : bad)
		{
			answers.put("/googlebot.json", answer);
			refreshAndWait("googlebot");
			FeederStatusResponse.FeedStatus g = status().feed("googlebot");
			assertNotNull("an error for " + answer[0] + " " + answer[1], g.getLastError());
			assertEquals("the list is kept after " + answer[0] + " " + answer[1], good, harness.context().readList("googlebot"));
		}
		assertEquals(bad.length, status().feed("googlebot").getConsecutiveFailures());

		answers.put("/googlebot.json", new Object[] { 200, BING });
		refreshAndWait("googlebot");
		assertNull(status().feed("googlebot").getLastError());
		assertTrue(harness.context().readList("googlebot").contains("157.55.39.0/24"));
		assertFalse(harness.context().readList("googlebot").contains("66.249.64.0/27"));
	}

	@Test
	public void anOperatorsListIsNeverOverwritten() throws Exception {
		harness.context().writeConfFile("lists/googlebot.txt", "# mine\n66.249.64.0/19\n");
		feeds("googlebot", "/googlebot.json");
		await("an attempt", () -> null != statusQuietly().feed("googlebot").getLastError());
		assertEquals(List.of("# mine", "66.249.64.0/19"), harness.context().readList("googlebot"));
	}

	@Test
	public void refreshNeedsTheWriteGate() throws Exception {
		feeds("googlebot", "/googlebot.json");
		ActionResponse r = harness.call(FakeManagementRequest.of("crawler-feeder-refresh"));
		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, r.getResponseStatus());
		ActionResponse unknown = harness.call(FakeManagementRequest.of("crawler-feeder-refresh").parameter("feed", "nope")
				.writeAuthorized(true));
		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, unknown.getResponseStatus());
	}

	@Test
	public void aRestartDoesNotFetchAgainBeforeTheFeedIsDue() throws Exception {
		feeds("googlebot", "/googlebot.json");
		await("the list", () -> succeeded("googlebot"));
		int fetched = hits.get();

		harness.reload();
		Thread.sleep(300);
		assertEquals("not due for an hour", fetched, hits.get());
		assertTrue(status().feed("googlebot").getNextRunSeconds() > 3_000);
		assertEquals(3, status().feed("googlebot").getBlocks());

		// another url for the same list is fetched at once
		answers.put("/other.json", new Object[] { 200, BING });
		feeds("googlebot", "/other.json");
		await("the new url", () -> listHas("googlebot", "157.55.39.0/24"));
	}

	@Test
	public void theDefaultsAreTheVendorsDaily() throws Exception {
		FeederConfig defaults = FeederConfig.parse(PluginHarness.parseConfig("", "conf/crawler-feeder.conf"));
		assertEquals(2, defaults.feeds().size());
		assertEquals("googlebot", defaults.feeds().get(0).name());
		assertEquals(FeederConfig.GOOGLEBOT, defaults.feeds().get(0).url().toString());
		assertEquals("bingbot", defaults.feeds().get(1).name());
		assertEquals(FeederConfig.BINGBOT, defaults.feeds().get(1).url().toString());
		assertEquals(java.time.Duration.ofHours(24), defaults.feeds().get(1).every());
	}

	@Test
	public void theJsonReaderIsStrict() throws Exception {
		assertEquals(List.of("1.2.3.0/24", "::/0"), FeedFetcher.prefixes(
				"{\"prefixes\":[{\"ipv4Prefix\":\"1.2.3.0/24\"},{\"ipv6Prefix\":\"::\\/0\"}],\"x\":[true,false,null,-1.5e3]}"));
		for (String broken : new String[] { "", "{", "{\"prefixes\":[}", "{\"prefixes\":[{\"ipv4Prefix\":1}]}",
				"{\"prefixes\":[]} trailing", "[1,2]", "{\"prefixes\":[{}]}" })
		{
			try {
				FeedFetcher.prefixes(broken);
				fail("accepted " + broken);
			} catch (IOException expected) {
			}
		}
	}

	private String describe() {
		StringBuilder out = new StringBuilder();
		for (FeederStatusResponse.FeedStatus f : statusQuietly().getFeeds())
			out.append(f.getName()).append(": attempt ").append(f.getLastAttempt()).append(", success ")
					.append(f.getLastSuccess()).append(", error ").append(f.getLastError()).append("; ");
		return out.append(hits.get()).append(" requests").toString();
	}

	private FeederStatusResponse statusQuietly() {
		try {
			return status();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
