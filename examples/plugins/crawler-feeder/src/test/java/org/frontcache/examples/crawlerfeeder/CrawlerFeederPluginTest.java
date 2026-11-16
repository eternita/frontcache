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

	private final FakeDns dns = new FakeDns();

	/** The node constructs the plugin with the JDK's resolver; here every lookup is the test's. */
	@Override
	protected PluginHarness newHarness() {
		return PluginHarness.of(new CrawlerFeederPlugin(dns, new FakeDns.MovableClock()),
				PluginHarness.manifestOf(CrawlerFeederPlugin.class));
	}

	/**
	 * The contract's own runs fetch nothing - no url, so no network - and observe: the sample events' {@code prove-it}
	 * is a claim rule here, checked against the fake resolver.
	 */
	@Override
	protected String startConfig() {
		return "feeds = [ { name = claimed, domains = [crawler.example], rules = [prove-it] } ]";
	}

	@Override
	protected List<String> acceptedConfigs() {
		// all loopback: the contract applies them to the running plugin, which fetches at once (port 9 refuses)
		return List.of("feeds = []", "feeds = [ { name = googlebot, url = \"http://127.0.0.1:9/g.json\" } ]",
				"feeds = [ { name = a.b_c-1, url = \"http://localhost:9/a\", every = 1m }, { name = b, url = \"http://[::1]:9/b\", every = 30d } ]\ntimeout = 2s",
				"feeds = [ { name = googlebot, url = \"http://127.0.0.1:9/g.json\", domains = [googlebot.com, Google.COM.] } ]",
				"feeds = [ { name = yandexbot, domains = yandex.com, rules = [\"yandex claim\", fake-yandex] } ]\nkeep = 1h\nretry = 1m\nmax-verified = 10000",
				"keep = 90d\nretry = 7d\nmax-verified = 1");
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
				"feed = []",
				"feeds = [ { name = g } ]",
				"feeds = [ { name = g, every = 1h, domains = [g.example] } ]",
				"feeds = [ { name = g, domains = [com] } ]",
				"feeds = [ { name = g, domains = [\"*.googlebot.com\"] } ]",
				"feeds = [ { name = g, domains = [ 42 ] } ]",
				"feeds = [ { name = g, domains = { a = b } } ]",
				"feeds = [ { name = g, url = \"https://example.com/1\", rules = [g-claim] } ]",
				"feeds = [ { name = g, domains = [g.example], rules = [] } ]",
				"feeds = [ { name = g, domains = [g.example], rules = [\"a | b\"] } ]",
				"feeds = [ { name = g, domains = [a.x, b.x, c.x, d.x, e.x, f.x, g.x, h.x, i.x, j.x, k.x] } ]",
				"keep = 10m",
				"retry = 0s",
				"retry = forever",
				"max-verified = 0",
				"max-verified = 10001");
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
	public void theDefaultsAreTheVendorsDailyEachCheckingItsClaimants() throws Exception {
		FeederConfig defaults = FeederConfig.parse(PluginHarness.parseConfig("", "conf/crawler-feeder.conf"));
		assertEquals(3, defaults.feeds().size());
		FeederConfig.Feed google = defaults.feed("googlebot");
		assertEquals(FeederConfig.GOOGLEBOT, google.url().toString());
		assertEquals(List.of("googlebot.com", "google.com"), google.domains());
		assertEquals(List.of("googlebot-claim"), google.rules());
		FeederConfig.Feed bing = defaults.feed("bingbot");
		assertEquals(FeederConfig.BINGBOT, bing.url().toString());
		assertEquals(List.of("search.msn.com"), bing.domains());
		assertEquals(java.time.Duration.ofHours(24), bing.every());
		FeederConfig.Feed apple = defaults.feed("applebot");
		assertEquals(FeederConfig.APPLEBOT, apple.url().toString());
		assertEquals(List.of("applebot.apple.com"), apple.domains());
		assertEquals(List.of("applebot-claim"), apple.rules());
		assertEquals(java.time.Duration.ofDays(7), defaults.keep());
		assertEquals(java.time.Duration.ofHours(1), defaults.retry());
		assertEquals(1_000, defaults.maxVerified());
		assertEquals(defaults, FeederConfig.defaults());

		// feeds written out: no check unless it names domains; with domains, rules default to <name>-claim
		FeederConfig written = FeederConfig.parse(PluginHarness.parseConfig("feeds = [ { name = g, url = \"https://g.example/x\" },"
				+ " { name = y, domains = [Yandex.COM.] } ]", "conf/crawler-feeder.conf"));
		assertFalse(written.feed("g").verifies());
		assertEquals(List.of(), written.feed("g").rules());
		assertFalse(written.feed("y").fetches());
		assertEquals(List.of("yandex.com"), written.feed("y").domains());
		assertEquals(List.of("y-claim"), written.feed("y").rules());

		// the README's example: the defaults written out, and a crawler without published ranges
		FeederConfig readme = FeederConfig.parse(PluginHarness.parseConfig("""
				feeds = [
				  { name = googlebot, url = "https://developers.google.com/static/search/apis/ipranges/googlebot.json", every = 24h,
				    domains = [googlebot.com, google.com] },
				  { name = bingbot,   url = "https://www.bing.com/toolbox/bingbot.json", domains = [search.msn.com] },
				  { name = applebot,  url = "https://search.developer.apple.com/applebot.json", domains = [applebot.apple.com] },
				  # a crawler that publishes no ranges: a list of confirmed claimants only
				  { name = yandexbot, domains = [yandex.ru, yandex.net, yandex.com], rules = [yandexbot-claim] }
				]
				timeout = 30s        # connecting, and the whole answer
				keep = 7d            # 1h..90d
				retry = 1h           # 1m..7d
				max-verified = 1000  # per list, 1..10000
				""", "conf/crawler-feeder.conf"));
		assertEquals(defaults.feeds(), readme.feeds().subList(0, 3));
		assertEquals(List.of("yandex.ru", "yandex.net", "yandex.com"), readme.feed("yandexbot").domains());
	}

	@Test
	public void itObservesOnlyWhileAFeedChecksClaimants() throws Exception {
		assertEquals(1, harness.listeners().size());
		assertEquals(java.util.Set.of(org.frontcache.plugin.event.GuardMatched.class), harness.listeners().get(0).subscribes());
		harness.configure("feeds = [ { name = googlebot, url = \"http://127.0.0.1:9/g.json\" } ]");
		assertTrue("no feed checks claimants: no event is built for the plugin", harness.listeners().isEmpty());
		harness.configure("feeds = [ { name = y, domains = [y.example] } ]");
		assertEquals(1, harness.listeners().size());
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
