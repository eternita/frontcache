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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.frontcache.io.ActionResponse;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.testkit.FakeManagementRequest;
import org.frontcache.plugin.testkit.PluginHarness;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Claimants confirmed by reverse DNS: what a {@code GuardMatched} of a claim rule leads to, against a fake resolver
 * and a clock the test moves - no network. The vendor's file comes from a loopback HTTP server.
 */
public class ClaimantVerificationTest {

	private static final String GOOGLE = "{\"prefixes\":[{\"ipv4Prefix\":\"66.249.64.0/27\"},{\"ipv6Prefix\":\"2001:4860:4801:10::/64\"}]}";
	private static final String CRAWLER = "66.249.90.1";
	private static final String CRAWLER_HOST = "crawl-66-249-90-1.googlebot.com";

	private final FakeDns dns = new FakeDns();
	private final FakeDns.MovableClock clock = new FakeDns.MovableClock();
	private CrawlerFeederPlugin plugin;
	private PluginHarness harness;

	private HttpServer server;
	private String base;
	private volatile int vendorStatus = 200;
	private final AtomicInteger hits = new AtomicInteger();

	@Before
	public void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			hits.incrementAndGet();
			byte[] body = GOOGLE.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(vendorStatus, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		dns.crawler(CRAWLER, CRAWLER_HOST);
		plugin = new CrawlerFeederPlugin(dns, clock);
		harness = PluginHarness.of(plugin, PluginHarness.manifestOf(CrawlerFeederPlugin.class));
	}

	@After
	public void stop() {
		harness.close();
		server.stop(0);
	}

	private String googlebot(String extra) {
		return "feeds = [ { name = googlebot, url = \"" + base + "/googlebot.json\", every = 1h,"
				+ " domains = [googlebot.com, google.com] } ]\ntimeout = 2s\n" + extra;
	}

	/** Configured and started, the feed fetched. */
	private void startGooglebot(String extra) throws Exception {
		harness.configure(googlebot(extra)).start();
		await("the first fetch", () -> listHas("googlebot", "66.249.64.0/27"));
	}

	private void claim(String rule, String address) {
		harness.deliver(new GuardMatched(clock.millis(), "fc-test-1", "www.example.com", "r-1", rule, "dry-run", 0,
				"www.example.com/", "bot", address));
	}

	private void claimAndWait(String address) throws InterruptedException {
		claim("googlebot-claim", address);
		await("the check of " + address, plugin::idle);
	}

	private boolean listHas(String list, String entry) {
		try {
			return harness.context().readList(list).contains(entry);
		} catch (IOException e) {
			return false;
		}
	}

	private FeederStatusResponse status() {
		return plugin.status(CrawlerFeederPlugin.STATUS);
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (!condition.getAsBoolean())
		{
			if (System.currentTimeMillis() > deadline)
				fail("timed out waiting for " + what);
			Thread.sleep(10);
		}
	}

	// ---- the check itself --------------------------------------------------------------------------------------

	@Test
	public void theCheckIsTheVendorsReverseThenForwardLookup() throws Exception {
		List<String> google = List.of("googlebot.com", "google.com");
		InetAddress address = InetAddress.ofLiteral(CRAWLER);
		ClaimCheck.Verdict ok = ClaimCheck.check(dns, address, google);
		assertTrue(ok.confirmed());
		assertEquals(CRAWLER_HOST, ok.host());

		String[][] refuted = { { "evil.example.com" }, { "googlebot.com" }, { "evilgooglebot.com" },
				{ "googlebot.com.evil.example" }, { "x googlebot.com" }, { "a.googlebot.com\n66.0.0.0/1" } };
		for (String[] name : refuted)
		{
			FakeDns d = new FakeDns().claims("203.0.113.9", name[0], "203.0.113.9");
			ClaimCheck.Verdict v = ClaimCheck.check(d, InetAddress.ofLiteral("203.0.113.9"), google);
			assertEquals(name[0], ClaimCheck.Outcome.REFUTED, v.outcome());
			assertNotNull(v.reason());
		}

		// under the domain, but the name resolves elsewhere: someone set their own PTR
		FakeDns spoofed = new FakeDns().claims("203.0.113.9", "crawl-1.googlebot.com", "66.249.66.1");
		assertEquals(ClaimCheck.Outcome.REFUTED, ClaimCheck.check(spoofed, InetAddress.ofLiteral("203.0.113.9"), google).outcome());

		// no name, a name that resolves to nothing, a resolver that fails: no answer, not a no
		assertEquals(ClaimCheck.Outcome.UNKNOWN, ClaimCheck.check(new FakeDns(), InetAddress.ofLiteral("203.0.113.9"), google).outcome());
		assertEquals(ClaimCheck.Outcome.UNKNOWN, ClaimCheck.check(new FakeDns().claims("203.0.113.9", "crawl-1.googlebot.com"),
				InetAddress.ofLiteral("203.0.113.9"), google).outcome());
		FakeDns failing = new FakeDns();
		failing.names.put("203.0.113.9", FakeDns.FAIL);
		assertEquals(ClaimCheck.Outcome.UNKNOWN, ClaimCheck.check(failing, InetAddress.ofLiteral("203.0.113.9"), google).outcome());

		// a trailing dot and capitals are the same name; IPv6 alike
		FakeDns six = new FakeDns().claims("2001:4860:4801:2::1", "Crawl-2001-4860-4801-2--1.GOOGLEBOT.com.", "2001:4860:4801:2:0::1");
		ClaimCheck.Verdict v6 = ClaimCheck.check(six, InetAddress.ofLiteral("2001:4860:4801:2::1"), google);
		assertTrue(v6.reason(), v6.confirmed());
		assertEquals("crawl-2001-4860-4801-2--1.googlebot.com", v6.host());
	}

	// ---- claimants, through the plugin ---------------------------------------------------------------------------

	@Test
	public void aConfirmedClaimantJoinsItsListBesideTheVendorsRanges() throws Exception {
		startGooglebot("");
		int before = harness.context().readList("googlebot").size();
		claimAndWait(CRAWLER);
		await("the claimant on the list", () -> listHas("googlebot", CRAWLER));

		List<String> list = harness.context().readList("googlebot");
		assertEquals("# managed by plugin crawler-feeder", list.get(0));
		assertTrue("the vendor's ranges stay", list.contains("66.249.64.0/27"));
		assertTrue(list.contains("2001:4860:4801:10::/64"));
		assertTrue(list.stream().anyMatch(l -> l.startsWith("# " + CRAWLER_HOST + " - confirmed ")));
		assertTrue(list.size() > before);

		FeederStatusResponse.FeedStatus g = status().feed("googlebot");
		assertEquals(1, g.getVerified());
		assertEquals(3, g.getBlocks());
		assertEquals(CRAWLER, g.getConfirmed().get(0).address());
		assertEquals(CRAWLER_HOST, g.getConfirmed().get(0).host());
		assertEquals(List.of("googlebot-claim"), g.getRules());
		assertEquals(1, status().getClaimants().getConfirmed());

		// reported again (a claim rule that does not read the list): not looked up again
		int lookups = dns.reverses.get();
		claimAndWait(CRAWLER);
		assertEquals(lookups, dns.reverses.get());

		// a fetch keeps the confirmed claimants
		ActionResponse r = harness.call(FakeManagementRequest.of("crawler-feeder-refresh").writeAuthorized(true));
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, r.getResponseStatus());
		int hit = hits.get();
		await("the refetch", () -> hits.get() > hit && !plugin.isRunning("googlebot"));
		assertTrue(listHas("googlebot", CRAWLER));
	}

	@Test
	public void aClaimantNotConfirmedIsNotListedNorAskedAgainBeforeRetry() throws Exception {
		startGooglebot("retry = 1h");
		dns.claims("203.0.113.9", "scraper.example.net", "203.0.113.9");
		claimAndWait("203.0.113.9");
		assertEquals(1, dns.reverses.get());
		assertFalse(listHas("googlebot", "203.0.113.9"));
		assertEquals(1, status().getClaimants().getRefuted());
		assertEquals(0, status().feed("googlebot").getVerified());
		assertTrue("an unconfirmed claimant is personal data - never in status",
				status().feed("googlebot").getConfirmed().isEmpty());

		for (int i = 0; i < 5; i++)
			claimAndWait("203.0.113.9");
		assertEquals("not looked up again within retry", 1, dns.reverses.get());
		assertEquals(6, status().getClaimants().getClaims());

		// no answer is remembered the same way
		claimAndWait("203.0.113.10");
		claimAndWait("203.0.113.10");
		assertEquals(2, dns.reverses.get());
		assertEquals(1, status().getClaimants().getUnknown());

		clock.advance(Duration.ofMinutes(61));
		plugin.sweep();
		claimAndWait("203.0.113.9");
		assertEquals("after retry it is asked again", 3, dns.reverses.get());

		// another rule's match is no claim
		claim("block-wp", "203.0.113.11");
		await("idle", plugin::idle);
		assertEquals(3, dns.reverses.get());
	}

	@Test
	public void aPrivateOrBrokenAddressIsNeverLookedUp() throws Exception {
		startGooglebot("");
		for (String address : new String[] { "10.0.0.1", "192.168.1.1", "172.16.0.1", "127.0.0.1", "::1", "fe80::1",
				"fd00::1", "100.64.1.1", "0.0.0.0", "224.0.0.1", "not-an-address", "www.google.com", "", "fe80::1%1" })
			claim("googlebot-claim", address);
		claim("googlebot-claim", null);
		await("idle", plugin::idle);
		assertEquals(0, dns.reverses.get());
		assertEquals(15, status().getClaimants().getIgnored());
		assertEquals(15, status().getClaimants().getClaims());
	}

	@Test
	public void aFetchingFeedNeverWritesClaimantsWithoutItsRanges() throws Exception {
		vendorStatus = 500;
		harness.configure(googlebot("")).start();
		await("a failed fetch", () -> null != status().feed("googlebot").getLastError());
		claimAndWait(CRAWLER);
		assertEquals("confirmed", 1, status().feed("googlebot").getVerified());
		assertFalse("but no list of claimants alone - it would drop the vendor's ranges",
				harness.context().listFile("googlebot").exists());

		vendorStatus = 200;
		harness.call(FakeManagementRequest.of("crawler-feeder-refresh").writeAuthorized(true));
		await("the list", () -> listHas("googlebot", "66.249.64.0/27"));
		assertTrue("the fetch writes the confirmed claimant too", listHas("googlebot", CRAWLER));
	}

	@Test
	public void aFeedWithoutUrlKeepsConfirmedClaimantsOnly() throws Exception {
		dns.crawler("5.255.253.1", "5-255-253-1.spider.yandex.com");
		harness.configure("feeds = [ { name = yandexbot, domains = [yandex.com, yandex.ru] } ]").start();
		assertTrue("written at start, so a rule reading it finds a file", harness.context().listFile("yandexbot").isFile());
		assertEquals(0, status().feed("yandexbot").getBlocks());
		assertNull(status().feed("yandexbot").getUrl());
		assertEquals(-1, status().feed("yandexbot").getNextRunSeconds());

		claim("yandexbot-claim", "5.255.253.1");
		await("idle", plugin::idle);
		await("listed", () -> listHas("yandexbot", "5.255.253.1"));
		assertEquals(1, status().feed("yandexbot").getBlocks());
		assertEquals(0, hits.get());

		ActionResponse refresh = harness.call(FakeManagementRequest.of("crawler-feeder-refresh").writeAuthorized(true));
		assertEquals("nothing to fetch", ActionResponse.RESPONSE_STATUS_ERROR, refresh.getResponseStatus());
	}

	@Test
	public void confirmationsRunOutAndAreCheckedAgainFromHalfway() throws Exception {
		startGooglebot("keep = 2h\nretry = 10m");
		claimAndWait(CRAWLER);
		assertTrue(listHas("googlebot", CRAWLER));
		assertEquals(1, dns.reverses.get());

		clock.advance(Duration.ofMinutes(30));
		plugin.sweep();
		await("idle", plugin::idle);
		assertEquals("not before half-way", 1, dns.reverses.get());

		// half-way: checked again; the resolver does not answer now - no reason to drop it
		dns.names.put(CRAWLER, FakeDns.FAIL);
		clock.advance(Duration.ofMinutes(31));
		plugin.sweep();
		await("idle", plugin::idle);
		assertEquals(2, dns.reverses.get());
		assertTrue(listHas("googlebot", CRAWLER));
		plugin.sweep();
		await("idle", plugin::idle);
		assertEquals("an unknown answer is asked again after retry, not every sweep", 2, dns.reverses.get());

		// the confirmation runs out
		clock.advance(Duration.ofMinutes(60));
		plugin.sweep();
		await("idle", plugin::idle);
		assertFalse(listHas("googlebot", CRAWLER));
		assertTrue("the vendor's ranges stay", listHas("googlebot", "66.249.64.0/27"));
		assertEquals(0, status().feed("googlebot").getVerified());
	}

	@Test
	public void aRecheckThatRefutesTakesItOffAtOnceAndOneThatConfirmsRenewsIt() throws Exception {
		dns.crawler("66.249.90.2", "crawl-66-249-90-2.googlebot.com");
		startGooglebot("keep = 2h");
		claimAndWait(CRAWLER);
		claimAndWait("66.249.90.2");
		assertTrue(listHas("googlebot", CRAWLER) && listHas("googlebot", "66.249.90.2"));

		dns.claims(CRAWLER, "reassigned.example.net", CRAWLER);
		clock.advance(Duration.ofMinutes(61));
		plugin.sweep();
		await("idle", plugin::idle);
		await("off the list", () -> !listHas("googlebot", CRAWLER));
		assertTrue(listHas("googlebot", "66.249.90.2"));

		// renewed: still listed past its first two hours
		clock.advance(Duration.ofMinutes(61));
		plugin.sweep();
		await("idle", plugin::idle);
		assertTrue(listHas("googlebot", "66.249.90.2"));
		assertEquals(clock.instant().toString(), status().feed("googlebot").getConfirmed().get(0).confirmed());
	}

	@Test
	public void maxVerifiedGivesWayOldestFirst() throws Exception {
		dns.crawler("66.249.90.2", "crawl-66-249-90-2.googlebot.com").crawler("66.249.90.3", "crawl-66-249-90-3.googlebot.com");
		startGooglebot("max-verified = 2");
		claimAndWait(CRAWLER);
		clock.advance(Duration.ofSeconds(1));
		claimAndWait("66.249.90.2");
		clock.advance(Duration.ofSeconds(1));
		claimAndWait("66.249.90.3");
		await("the newest listed", () -> listHas("googlebot", "66.249.90.3"));
		assertFalse(listHas("googlebot", CRAWLER));
		assertTrue(listHas("googlebot", "66.249.90.2"));
		assertEquals(2, status().feed("googlebot").getVerified());
		assertEquals("66.249.90.3", status().feed("googlebot").getConfirmed().get(0).address());
	}

	@Test
	public void aConfigChangeDropsClaimantsItNoLongerVouchesFor() throws Exception {
		dns.crawler("66.249.90.7", "rate-limited-proxy-66-249-90-7.google.com");
		startGooglebot("");
		claimAndWait(CRAWLER);
		claimAndWait("66.249.90.7");
		assertTrue(listHas("googlebot", "66.249.90.7"));

		harness.configure("feeds = [ { name = googlebot, url = \"" + base + "/googlebot.json\", every = 1h,"
				+ " domains = [googlebot.com] } ]\ntimeout = 2s");
		assertFalse("google.com is no longer one of its domains", listHas("googlebot", "66.249.90.7"));
		assertTrue(listHas("googlebot", CRAWLER));

		harness.configure("feeds = [ { name = googlebot, url = \"" + base + "/googlebot.json\", every = 1h } ]\ntimeout = 2s");
		assertFalse("a feed that checks no claimant lists none", listHas("googlebot", CRAWLER));
		assertTrue(listHas("googlebot", "66.249.64.0/27"));
	}

	@Test
	public void confirmedClaimantsSurviveARestartAndThoseThatRanOutWhileStoppedDoNot() throws Exception {
		dns.crawler("66.249.90.2", "crawl-66-249-90-2.googlebot.com");
		startGooglebot("keep = 2h");
		claimAndWait(CRAWLER);
		clock.advance(Duration.ofMinutes(90));
		claimAndWait("66.249.90.2");
		int fetched = hits.get();
		harness.stop(true);

		clock.advance(Duration.ofMinutes(40));   // the first ran out while stopped
		FakeDns quiet = new FakeDns();
		CrawlerFeederPlugin restarted = new CrawlerFeederPlugin(quiet, clock);
		restarted.configure(PluginHarness.parseConfig(googlebot("keep = 2h"), "conf/crawler-feeder.conf"));
		harness.context().newScheduler();
		restarted.start(harness.context());
		try {
			FeederStatusResponse.FeedStatus g = restarted.status(CrawlerFeederPlugin.STATUS).feed("googlebot");
			assertEquals(1, g.getVerified());
			assertEquals("66.249.90.2", g.getConfirmed().get(0).address());
			await("the expired one off the list", () -> !listHas("googlebot", CRAWLER));
			assertTrue(listHas("googlebot", "66.249.90.2"));
			assertTrue("the ranges were read back, not fetched", listHas("googlebot", "66.249.64.0/27"));
			assertEquals("not due for an hour", fetched, hits.get());
		} finally {
			restarted.stop(true);
			harness.context().cancelScheduledTasks();
		}
	}

	@Test
	public void aFullQueueDropsAndCountsInsteadOfBlocking() throws Exception {
		startGooglebot("");
		CountDownLatch gate = new CountDownLatch(1);
		dns.gate = gate;
		try {
			long start = System.nanoTime();
			for (int i = 0; i < CrawlerFeederPlugin.MAX_PENDING + 100; i++)
				claim("googlebot-claim", "198.51." + (i / 250) + "." + (i % 250 + 1));
			assertTrue("the listener never waits for a lookup", System.nanoTime() - start < 5_000_000_000L);
			assertEquals(100, status().getClaimants().getDropped());
			assertEquals(CrawlerFeederPlugin.MAX_PENDING, status().getClaimants().getPending());
		} finally {
			gate.countDown();
		}
		dns.gate = null;
		await("the queue drained", plugin::idle);
	}

	@Test
	public void verifyChecksAnAddressNow() throws Exception {
		startGooglebot("");
		ActionResponse ungated = harness.call(FakeManagementRequest.of("crawler-feeder-verify").parameter("address", CRAWLER));
		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, ungated.getResponseStatus());
		for (String bad : new String[] { "crawl-66-249-90-1.googlebot.com", "66.249.90", "" })
		{
			ActionResponse r = harness.call(FakeManagementRequest.of("crawler-feeder-verify").parameter("address", bad)
					.writeAuthorized(true));
			assertEquals(bad, ActionResponse.RESPONSE_STATUS_ERROR, r.getResponseStatus());
		}
		ActionResponse noFeed = harness.call(FakeManagementRequest.of("crawler-feeder-verify").parameter("address", CRAWLER)
				.parameter("feed", "bingbot").writeAuthorized(true));
		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, noFeed.getResponseStatus());

		FeederVerifyResponse ok = (FeederVerifyResponse) harness.call(FakeManagementRequest.of("crawler-feeder-verify")
				.parameter("address", CRAWLER).writeAuthorized(true));
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, ok.getResponseStatus());
		assertEquals("confirmed", ok.check("googlebot").getOutcome());
		assertEquals(CRAWLER_HOST, ok.check("googlebot").getHost());
		assertTrue(ok.check("googlebot").isListed());
		assertTrue(listHas("googlebot", CRAWLER));

		dns.claims("203.0.113.9", "scraper.example.net", "203.0.113.9");
		FeederVerifyResponse no = (FeederVerifyResponse) harness.call(FakeManagementRequest.of("crawler-feeder-verify")
				.parameter("address", "203.0.113.9").parameter("feed", "googlebot").writeAuthorized(true));
		assertEquals("refuted", no.check("googlebot").getOutcome());
		assertFalse(no.check("googlebot").isListed());
		assertNotNull(no.check("googlebot").getReason());
	}

	@Test
	public void anOperatorsListIsNeverOverwrittenByAConfirmation() throws Exception {
		harness.context().writeConfFile("lists/yandexbot.txt", "# mine\n5.255.253.0/24\n");
		dns.crawler("5.255.253.1", "5-255-253-1.spider.yandex.com");
		harness.configure("feeds = [ { name = yandexbot, domains = [yandex.com] } ]").start();
		claim("yandexbot-claim", "5.255.253.1");
		await("idle", plugin::idle);
		assertEquals(List.of("# mine", "5.255.253.0/24"), harness.context().readList("yandexbot"));
		assertNotNull("the refusal is in status", status().feed("yandexbot").getWriteError());
	}
}
