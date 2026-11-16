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
package org.frontcache.examples.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.event.ChallengeIssued;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.event.RateLimitExceeded;
import org.frontcache.plugin.event.RequestCompleted;
import org.frontcache.plugin.testkit.FakePluginRequest;
import org.frontcache.plugin.testkit.FakePluginResponse;
import org.frontcache.plugin.testkit.PluginContract;
import org.junit.Test;

public class DebugPluginTest extends PluginContract {

	private static final String SITE = "www.example.com";

	@Override
	protected Class<? extends FrontcachePlugin> pluginClass() {
		return DebugPlugin.class;
	}

	@Override
	protected List<FakePluginRequest> endpointRequests() {
		List<FakePluginRequest> requests = new ArrayList<>(super.endpointRequests());
		requests.add(FakePluginRequest.get("/").from("127.0.0.1"));
		requests.add(FakePluginRequest.get("/debug.css").from("::1"));
		requests.add(FakePluginRequest.get("/nothing").from("127.0.0.1"));
		return requests;
	}

	@Override
	protected List<String> acceptedConfigs() {
		return List.of("", "allow = []", "allow = [\"10.0.0.0/8\", \"2001:db8::/32\", \"192.0.2.1\"]\nrecent = 5",
				"visitors = 10");
	}

	@Override
	protected List<String> refusedConfigs() {
		return List.of("allow = \"127.0.0.1\"", "allow = [\"localhost\"]", "allow = [\"10.0.0.0/33\"]",
				"recent = 0", "visitors = many", "allowed = [\"127.0.0.1/32\"]",
				"allow = [\"127.0.0.1/32\", \"nope\"]\nrecent = 1000");
	}

	@Test
	public void anAddressNotAllowedGetsTheNodesOwn404() throws Exception {
		for (String address : new String[] { "203.0.113.7", "10.0.0.1", "2001:db8::1", null })
		{
			FakePluginResponse page = harness.serve(FakePluginRequest.get("/").from(address));
			assertEquals(String.valueOf(address), 404, page.status());
			assertEquals("Not found\n", page.text());
			assertEquals(404, harness.serve(FakePluginRequest.get("/debug.css").from(address)).status());
		}
		assertEquals("a write is not a read", 404, harness.serve(FakePluginRequest.form("/", "a=1").from("127.0.0.1"))
				.status());
	}

	@Test
	public void theAllowListComesFromTheConfig() throws Exception {
		harness.configure("allow = [\"203.0.113.0/24\"]");
		assertEquals(200, harness.serve(FakePluginRequest.get("/").from("203.0.113.7")).status());
		assertEquals(404, harness.serve(FakePluginRequest.get("/").from("127.0.0.1")).status());
	}

	@Test
	public void thePageShowsTheRequestAsTheNodeSeesItEscaped() throws Exception {
		FakePluginResponse page = harness.serve(FakePluginRequest.get("/?q=%3Cscript%3Ealert(1)%3C/script%3E")
				.from("127.0.0.1").clientType("browser").requestId("req-42").header("User-Agent", "<b>agent</b>")
				.header("Authorization", "Basic c2VjcmV0").header("x-frontcache-debug", "on"));
		assertEquals(200, page.status());
		assertEquals("text/html; charset=utf-8", page.contentType());
		assertTrue(page.header("Content-Security-Policy").startsWith("default-src 'none'"));
		String html = page.text();
		assertTrue(html.contains("127.0.0.1"));
		assertTrue(html.contains("browser"));
		assertTrue(html.contains("req-42"));
		assertTrue(html.contains("&lt;b&gt;agent&lt;/b&gt;"));
		assertFalse(html.contains("<b>agent</b>"));
		assertFalse(html.contains("<script"));
		assertFalse("the view never shows Authorization", html.contains("c2VjcmV0"));
		assertFalse(html.contains("x-frontcache-debug"));
		assertFalse("no inline style or script", html.contains("<style") || html.contains("style=") || html.contains("on"
				+ "click"));
	}

	@Test
	public void theVisitorSeesTheGuardsDecisionsAboutTheirOwnAddressOnly() throws Exception {
		harness.deliver(new GuardMatched(1_000L, "fc-1", SITE, "r-1", "block-wp", "rejected", 403, SITE + "/wp-login.php",
				"guest", "127.0.0.1"));
		harness.deliver(new RateLimitExceeded(2_000L, "fc-1", SITE, "r-2", "search", "search-rate", 60, 60_000,
				SITE + "/search?q=<x>", "127.0.0.1"));
		harness.deliver(new ChallengeIssued(3_000L, "fc-1", SITE, "r-3", "prove-it", "pow", "challenge", SITE + "/cart",
				"guest", "127.0.0.1"));
		harness.deliver(new GuardMatched(4_000L, "fc-1", SITE, "r-4", "someone-else", "rejected", 403, SITE + "/secret",
				"guest", "203.0.113.99"));
		harness.deliver(new RequestCompleted(5_000L, "fc-1", SITE, "r-5", "GET", SITE + "/", "success", true,
				"from-cache", 1, 10, "127.0.0.1", null, null, null, null));   // not subscribed: never delivered

		String html = harness.serve(FakePluginRequest.get("/").from("127.0.0.1")).text();
		assertTrue(html.contains("block-wp"));
		assertTrue(html.contains("search-rate"));
		assertTrue(html.contains("/search?q=&lt;x&gt;"));
		assertTrue(html.contains("prove-it"));
		assertTrue(html.contains("sent to pow"));
		assertTrue("newest first", html.indexOf("prove-it") < html.indexOf("block-wp"));
		assertFalse("another visitor's decisions are not shown", html.contains("someone-else"));
		assertFalse(html.contains("203.0.113.99"));

		// the other visitor sees their own, and none of these
		harness.configure("allow = [\"0.0.0.0/0\", \"::/0\"]");
		String theirs = harness.serve(FakePluginRequest.get("/").from("203.0.113.99")).text();
		assertTrue(theirs.contains("someone-else"));
		assertFalse(theirs.contains("block-wp"));
	}

	@Test
	public void itKeepsTheLastFewPerVisitorAndForgetsTheLeastRecentVisitor() throws Exception {
		harness.configure("recent = 2\nvisitors = 2\nallow = [\"0.0.0.0/0\"]");
		for (int i = 1; i <= 5; i++)
			harness.deliver(new GuardMatched(i, "fc-1", SITE, "r-" + i, "rule-" + i, "dry-run", 0, SITE + "/", null,
					"192.0.2.1"));
		String html = harness.serve(FakePluginRequest.get("/").from("192.0.2.1")).text();
		assertTrue(html.contains("rule-5") && html.contains("rule-4"));
		assertFalse(html.contains("rule-3"));

		harness.deliver(new GuardMatched(6, "fc-1", SITE, "r-6", "b", "dry-run", 0, SITE + "/", null, "192.0.2.2"));
		harness.deliver(new GuardMatched(7, "fc-1", SITE, "r-7", "c", "dry-run", 0, SITE + "/", null, "192.0.2.3"));
		assertEquals(2, ((DebugPlugin) harness.plugin()).decisions().visitorCount());
		assertFalse("192.0.2.1 was the least recently seen", harness.serve(FakePluginRequest.get("/").from("192.0.2.1"))
				.text().contains("rule-5"));
	}

	@Test
	public void theDecisionsSurviveAReload() throws Exception {
		harness.deliver(new GuardMatched(1L, "fc-1", SITE, "r-1", "kept", "rejected", 403, SITE + "/", null, "127.0.0.1"));
		harness.reload();
		assertTrue(harness.serve(FakePluginRequest.get("/").from("127.0.0.1")).text().contains("kept"));
	}

	@Test
	public void addressBlocksAreLiteralsOnly() {
		assertTrue(AddressBlock.parse("10.0.0.0/8").contains("10.200.3.4"));
		assertFalse(AddressBlock.parse("10.0.0.0/8").contains("11.0.0.1"));
		assertFalse("v4 never covers v6", AddressBlock.parse("0.0.0.0/0").contains("::1"));
		assertTrue(AddressBlock.parse("::1/128").contains("0:0:0:0:0:0:0:1"));
		assertTrue(AddressBlock.parse("2001:db8::/32").contains("2001:db8:1::5"));
		assertEquals(null, AddressBlock.parse("localhost"));
		assertEquals(null, AddressBlock.parse("10.0.0.0/33"));
		assertEquals(null, AddressBlock.parse("10.0.0"));
		assertFalse(AddressBlock.parse("127.0.0.1").contains("example.com"));
	}
}
