/*
 * Copyright 2015 Eternita
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
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.bots.BotRuleEntry;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.client.FrontCacheCluster;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;

/**
 *
 *
 */
public abstract class ClientTests extends TestsBase {

	private String FRONTCACHE_CLUSTER_NODE1 = getFrontCacheBaseURLLocalhost();

	private String FRONTCACHE_CLUSTER_NODE2 = getFrontCacheBaseURLLoopback();

	protected FrontCacheClient frontcacheClient = null;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST),
						new FrontCacheClient(getFrontCacheBaseURLLoopback(), ApiKeys.TEST_API_KEY_LOCALHOST)}
			));

		// clean up
		fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);


		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	public abstract String getFrontCacheBaseURLLocalhost();

	public abstract String getFrontCacheBaseURLLoopback();

	@Test
	public void getFromCacheClient() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);

		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_A);
		assertEquals("a", page.getPage().asNormalizedText());

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI_A);

		assertEquals("a", new String(resp.getContent()));
		return;
	}

	@Test
	public void deepInclude() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/a.jsp");
		assertEquals("abcdef", page.getPage().asNormalizedText());

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/a.jsp");
		assertEquals("a<fc:include url=\"/common/deep-include-cache/b.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/b.jsp");
		assertEquals("b<fc:include url=\"/common/deep-include-cache/c.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/c.jsp");
		assertEquals("c<fc:include url=\"/common/deep-include-cache/d.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/d.jsp");
		assertEquals("d<fc:include url=\"/common/deep-include-cache/e.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/e.jsp");
		assertEquals("e<fc:include url=\"/common/deep-include-cache/f.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/f.jsp");
		assertEquals("f", new String(resp.getContent()));

	}

	@Test
	public void getFromCacheClientNull() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);

		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCacheActionResponse(getFrontCacheBaseURLLocalhost() + TEST_URI_A).getValue();

		assertNull(resp);
		return;
	}


	@Test
	public void getCacheStatusClient() throws Exception {

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);

		Map<String, String> response = frontcacheClient.getCacheState();
		Assert.assertNotNull(response);
		logger.debug("response " + response);
	}

	@Test
	public void getCachedKeysClient() throws Exception {

		final String TEST_URI = "common/fc-client/a.jsp";

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);
		frontcacheClient.removeFromCacheAll(); // clean up

		// the request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());

		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		boolean success = frontcacheClient.getCachedKeys(baos);
		String keys = baos.toString();

		// getCachedKeys must pass the api-key gate (Authorization: Bearer <api-key>)
		Assert.assertTrue(success);
		Assert.assertEquals("getCachedKeys was denied - API key not sent: " + keys, -1, keys.indexOf("Access denied"));
		Assert.assertNotEquals("cached key not listed: " + keys, -1, keys.indexOf(TEST_URI));
		return;
	}

//	@Test
//	public void getCacheStatusCluster() throws Exception {
//
//		FrontCacheClient frontCacheClient1 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE1, ApiKeys.TEST_API_KEY_LOCALHOST);
//		FrontCacheClient frontCacheClient2 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE2, ApiKeys.TEST_API_KEY_LOCALHOST);
//		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(new FrontCacheClient[]{frontCacheClient1, frontCacheClient2}));
//
//		Map<String, String> response = fcCluster.getCacheState().get(frontCacheClient1);
//		Assert.assertNotNull(response);
//		logger.debug("response " + response);
//	}

//	@Test
//	public void getCachedKeysClient() throws Exception {
//
//		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURL());
//
//		CachedKeysActionResponse response = frontcacheClient.getCachedKeys();
//		Assert.assertNotNull(response);
//		Assert.assertEquals("cached keys", response.getAction());
//		logger.debug("response " + response);
//	}
//
//	@Test
//	public void getCachedKeysCluster() throws Exception {
//
//		FrontCacheClient frontCacheClient1 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE1);
//		FrontCacheClient frontCacheClient2 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE2);
//		FrontCacheCluster fcCluster = new FrontCacheCluster(frontCacheClient1, frontCacheClient2);
//
//		CachedKeysActionResponse response = fcCluster.getCachedKeys().get(frontCacheClient1);
//		Assert.assertNotNull(response);
//		Assert.assertEquals("cached keys", response.getAction());
//		logger.debug("response " + response);
//	}

	@Test
	public void invalidationByFilterTestClient() throws Exception {

		final String TEST_URI = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// cache invalidation (both standalone and filter)
		String response = frontcacheClientStandalone.removeFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI); // clean up FC standalone
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		response = frontcacheClientFilter.removeFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI); // clean up FC filter
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		return;
	}

	@Test
	public void invalidationByFilterTestCluster() throws Exception {

		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), ApiKeys.TEST_API_KEY_LOCALHOST);
		final String TEST_URI = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// cache invalidation
		String response = fcCluster.removeFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI).get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		return;
	}

	@Test
	public void invalidationAllTestClient() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST),
						new FrontCacheClient(getFrontCacheBaseURLLoopback(), ApiKeys.TEST_API_KEY_LOCALHOST)}
			));

		fcCluster.removeFromCacheAll(); // clean up

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_A);
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_B);
		assertEquals("b", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		Map<String, String> cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		// cache invalidation
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		return;
	}


	@Test
	public void invalidationAllTestCluster() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
					new FrontCacheClient[]{
							new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST),
							new FrontCacheClient(getFrontCacheBaseURLLoopback(), ApiKeys.TEST_API_KEY_LOCALHOST)}
				));

		// clean up
		String response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

//		fcCluster = new FrontCacheCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), ApiKeys.TEST_API_KEY_LOCALHOST);
//
//		// clean up
//		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_A);
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_B);
		assertEquals("b", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		Map<String, String> cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		// cache invalidation
		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		return;
	}


	@Test
	public void httpHeadersDuplication() throws Exception {
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/e.jsp");
		assertEquals("ef", page.getPage().asNormalizedText());

		List<NameValuePair> headers = page.getWebResponse().getResponseHeaders();
		for (NameValuePair nv : headers)
			System.out.println("HTTP HEADER " + nv.getName() + " -- " + nv.getValue());

		Set<String> names = new HashSet<String>();
		for (NameValuePair nv : headers)
		{
			String name = nv.getName();
			if (names.contains(name))
				fail("HTTP header duplicate name - " + name);

			names.add(name);
		}
		return;
	}

	/**
	 * The console's read-only config views, end to end.
	 *
	 * The suite never exercised these actions before, which is how a domain-keyed shape survived in the
	 * wire format for a release after the feature behind it was retired. Asserted against a live node so
	 * the flattening is proven through real HTTP and real Jackson, not only in a unit test.
	 */
	@Test
	public void consoleConfigViewsReturnFlatCollections() throws Exception {
		Set<String> botKeywords = frontcacheClient.getBotKeywords();
		Assert.assertNotNull("get-bots returned nothing", botKeywords);
		Assert.assertFalse("conf/bots.conf is not empty, so neither should this be", botKeywords.isEmpty());

		// The rules are additive (2.9.0) and must arrive BESIDE the keywords, not instead of them: the
		// console is deployed separately from the fleet and upgrades in either order. Every node has
		// rules, because two of them are built in.
		List<BotRuleEntry> botRules = frontcacheClient.getBotRules();
		Assert.assertNotNull("get-bots returned no rules", botRules);
		Assert.assertFalse("the built-in rules alone make this non-empty", botRules.isEmpty());
		Assert.assertEquals("the built-in no-user-agent rule is evaluated first",
				"no-user-agent", botRules.get(0).getName());
		Assert.assertEquals("the built-in default rule is evaluated last, and always matches",
				"default", botRules.get(botRules.size() - 1).getName());
		Set<String> ruleNames = new HashSet<String>();
		for (BotRuleEntry rule : botRules)
			ruleNames.add(rule.getName());

		// A bare keyword becomes a rule named after itself, so it is visible (and countable) on the
		// console's screen - while botKeywords keeps meaning exactly what it did before 2.9.0.
		Assert.assertTrue("every bare keyword should also appear as a rule: " + botKeywords + " vs " + ruleNames,
				ruleNames.containsAll(botKeywords));
		Assert.assertTrue("rules = 2 built-ins + the keywords + whatever else the file holds",
				botRules.size() >= botKeywords.size() + 2);

		Assert.assertNotNull("get-dynamic-urls returned nothing", frontcacheClient.getDynamicUrlPatterns());
		Assert.assertNotNull("get-fallback-configs returned nothing", frontcacheClient.getFallbackEntries());

		// the pre-2.7 shape has to keep arriving too - a 2.6 console reads it
		@SuppressWarnings("deprecation")
		Map<String, Set<String>> legacyShape = frontcacheClient.getBots();
		Assert.assertNotNull("the legacy domain-keyed field is gone from the wire", legacyShape);
		Assert.assertEquals("one node serves one site, so exactly one entry", 1, legacyShape.size());
		Assert.assertEquals("a 2.6 console's JSPs switch on this literal",
				"default-domain", legacyShape.keySet().iterator().next());
		Assert.assertEquals("both shapes must carry the same data",
				botKeywords, legacyShape.values().iterator().next());
	}

	/**
	 * The resilience config panel, and its pre-2.7 action name. Both must return the file the node is
	 * actually running on - which is conf/resilience.properties in standalone mode and the legacy
	 * the legacy hystrix.* key prefix in filter mode, so this runs against both spellings of the keys.
	 */
	@Test
	public void resilienceConfigIsServedUnderBothActionNames() throws Exception {
		String config = frontcacheClient.getResilienceConfig();

		Assert.assertNotNull("get-resilience-configs returned nothing", config);
		Assert.assertTrue("expected the raw properties file, got: " + config,
				config.contains("command.default.circuitBreaker.errorThresholdPercentage"));

		@SuppressWarnings("deprecation")
		String viaLegacyAccessor = frontcacheClient.getHystrixConfig();
		Assert.assertEquals("the pre-2.7 path must return the same file", config, viaLegacyAccessor);
	}

	/**
	 * The Bot Configs screen renders the file, not the parse of it, which is the whole reason the
	 * action exists beside get-bots: a commented-out rule or a line the parser skipped is visible
	 * only in the raw text. Both e2e nodes ship a conf/bots.conf, and the two files are deliberately
	 * in different formats, so asserting each node's own header also proves the response comes from
	 * that node's disk rather than from anywhere shared.
	 */
	@Test
	public void botsConfigActionReturnsTheRawFileAndNotTheParsedRules() throws Exception {
		org.frontcache.io.GetBotsConfigActionResponse response = frontcacheClient.getBotsConfigActionResponse();

		Assert.assertNotNull("the action must answer", response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());

		String raw = response.getBotsConfig();
		Assert.assertNotNull("the raw file is what the console's Bot Configs screen renders", raw);
		Assert.assertTrue("comments must survive - a parsed view would have dropped them, got: " + raw,
				raw.contains("# Conf for bots"));
		Assert.assertTrue("expected this node's own bots.conf, got: " + raw,
				raw.contains(expectedBotsConfigMarker()));
	}

	/**
	 * The Fallback Config screen renders the file rather than the parsed entry set it used to show as a
	 * table. Same reasoning as the two above: the table had a column per field of an entry, so the
	 * comments saying what a fallback is for - and any entry commented out - were not on the screen at
	 * all. The parsed shape stays on the wire; it is asserted above.
	 */
	@Test
	public void fallbacksConfigActionReturnsTheRawFileAndNotTheParsedEntries() throws Exception {
		org.frontcache.io.GetFallbacksConfigActionResponse response = frontcacheClient.getFallbacksConfigActionResponse();

		Assert.assertNotNull("the action must answer", response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());

		String raw = response.getFallbacksConfig();
		Assert.assertNotNull("the raw file is what the console's Fallback Config screen renders", raw);
		Assert.assertTrue("comments must survive - the parsed entries had dropped them, got: " + raw,
				raw.contains("# record description"));
		Assert.assertTrue("expected the entries too, got: " + raw, raw.contains("fallback1.txt"));
	}

	/**
	 * The Dynamic URLs screen renders the file rather than the parsed pattern set it used to show:
	 * the comments explaining why a URL is never cached, and any pattern commented out, exist only in
	 * the raw text. The parsed shape stays on the wire - it is asserted above - because it is what
	 * anything other than that one screen reads.
	 */
	@Test
	public void dynamicURLsConfigActionReturnsTheRawFileAndNotTheParsedPatterns() throws Exception {
		org.frontcache.io.GetDynamicURLsConfigActionResponse response = frontcacheClient.getDynamicURLsConfigActionResponse();

		Assert.assertNotNull("the action must answer", response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());

		String raw = response.getDynamicURLsConfig();
		Assert.assertNotNull("the raw file is what the console's Dynamic URLs screen renders", raw);
		Assert.assertTrue("comments must survive - the parsed set had dropped them, got: " + raw,
				raw.contains("# Configs for ignore patterns"));
		Assert.assertTrue("expected the patterns too, got: " + raw, raw.contains("^/admin/.*"));
	}

	/**
	 * A line unique to this node's conf/bots.conf - the standalone node is on the 2.9.0 rule format,
	 * the filter node deliberately on bare keywords only.
	 */
	protected abstract String expectedBotsConfigMarker();

	/**
	 * Whether this node's FRONTCACHE_HOME ships conf/guard-rules.conf. The two e2e nodes differ on
	 * purpose - the standalone one carries the rate-limit rule the guard tests exercise, the filter
	 * one carries nothing - so both branches of the get-guard-config action stay covered.
	 */
	protected abstract boolean nodeShipsGuardRules();

	/**
	 * conf/guard-rules.conf is optional, so the action has two shapes: the raw file, or a well-formed
	 * ERROR - never a blown-up action.
	 *
	 * The absent case is here because that is not what used to happen. The handler caught only
	 * IOException while FCConfig signals a missing file by throwing a RuntimeException, so the action
	 * failed outright and the "not found" branch it was written for was unreachable. Nothing called
	 * the action in any test, which is how it stayed that way.
	 */
	@Test
	public void guardConfigActionHandlesBothAPresentAndAMissingFile() throws Exception {
		org.frontcache.io.GetGuardConfigActionResponse response = frontcacheClient.getGuardConfigActionResponse();

		Assert.assertNotNull("the action must answer either way", response);

		if (nodeShipsGuardRules())
		{
			Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
			Assert.assertNotNull("the raw file is what the console's guard config screen renders", response.getGuardConfig());
			Assert.assertTrue("expected the raw file, got: " + response.getGuardConfig(),
					response.getGuardConfig().contains("ratelimit-e2e"));
		}
		else
		{
			Assert.assertEquals("no guard-rules.conf on this node, so: a clean error",
					org.frontcache.io.ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
			Assert.assertEquals("guard-rules.conf not found", response.getErrorDescription());
			Assert.assertNull(response.getGuardConfig());
		}
	}

	// ---- set-config: the console's Save ------------------------------------------------------

	/**
	 * <b>Saving a file unchanged must not change one byte of it.</b>
	 *
	 * This is the CRLF regression test, and it is the one a unit test cannot really make: it needs a
	 * real form-encoded POST, which is where a textarea's line breaks become CRLF. Without the
	 * server-side normalization the first Save rewrites every line ending in the file - a whole-file
	 * diff on a version-controlled FRONTCACHE_HOME, from a save the operator made no edit in.
	 *
	 * conf/bots.conf is used because both e2e nodes ship one, and the standalone node's copy ends
	 * WITHOUT a trailing newline - which is the case that would break if the normalizer helpfully
	 * added one.
	 */
	@Test
	public void savingAConfigFileUnchangedLeavesItByteForByteIdentical() throws Exception {
		String before = frontcacheClient.getBotsConfig();
		Assert.assertNotNull("this node must ship a conf/bots.conf for this test", before);

		org.frontcache.io.SetConfigActionResponse response =
				frontcacheClient.setConfig(org.frontcache.io.ConfigFile.BOTS, before, null, true);

		Assert.assertNotNull("set-config must answer", response);
		Assert.assertEquals("save with no edit must not be an error: " + response.getErrorDescription(),
				org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		Assert.assertFalse("identical content must not be rewritten", response.isWritten());
		Assert.assertTrue("...but it must still be applied - that is how an operator re-applies a file"
				+ " they edited on the box by hand", response.isApplied());

		Assert.assertEquals("the file must be byte-for-byte what it was", before, frontcacheClient.getBotsConfig());
	}

	/**
	 * An edit, applied, and then put back.
	 *
	 * The restore is in a finally because FRONTCACHE_HOME_FILTER/conf and
	 * FRONTCACHE_HOME_STANDALONE/conf are CHECKED IN: a test that writes them and does not restore
	 * leaves the working tree dirty and makes the suite order-dependent.
	 */
	@Test
	public void anEditIsWrittenAppliedAndCanBePutBack() throws Exception {
		String original = frontcacheClient.getBotsConfig();
		Assert.assertNotNull(original);

		String marker = "# e2e-set-config-marker";

		try {
			org.frontcache.io.SetConfigActionResponse response = frontcacheClient.setConfig(
					org.frontcache.io.ConfigFile.BOTS, original + "\n" + marker + "\n", null, true);

			Assert.assertNotNull(response);
			Assert.assertEquals("write refused: " + response.getErrorDescription(),
					org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
			Assert.assertTrue("the file must have been written", response.isWritten());
			Assert.assertTrue("...and applied", response.isApplied());
			Assert.assertNotNull("the previous content must be kept as a backup", response.getBackupFile());
			Assert.assertTrue("expected bots.conf.backup-<stamp>, got " + response.getBackupFile(),
					response.getBackupFile().startsWith("bots.conf.backup-"));

			String after = frontcacheClient.getBotsConfig();
			Assert.assertTrue("the node must be serving the edited file now, got: " + after, after.contains(marker));

			// The apply step ran, so the node re-read the file: the rules it parsed must still be there
			// (a comment line changes nothing) rather than the reload having emptied them.
			Assert.assertFalse("reload-bots must not have left the node with no rules",
					frontcacheClient.getBotRules().isEmpty());

		} finally {
			org.frontcache.io.SetConfigActionResponse restored =
					frontcacheClient.setConfig(org.frontcache.io.ConfigFile.BOTS, original, null, true);

			Assert.assertNotNull("the file must be restorable", restored);
			Assert.assertEquals(original, frontcacheClient.getBotsConfig());
		}
	}

	/**
	 * A broken line refuses the whole save, and the file on disk is untouched.
	 *
	 * {@code browser} was the client type through 2.8 and is exactly the edit someone would make from
	 * memory, so this is both the validation test and a check that the vocabulary really is closed -
	 * it keys expireTimeMap in every L2 entry on disk.
	 */
	@Test
	public void aBrokenLineIsRefusedAndNothingIsWritten() throws Exception {
		String before = frontcacheClient.getBotsConfig();

		org.frontcache.io.SetConfigActionResponse response = frontcacheClient.setConfig(
				org.frontcache.io.ConfigFile.BOTS, before + "\nbad | ua~x | browser\n", null, true);

		Assert.assertNotNull(response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		Assert.assertFalse(response.isWritten());
		Assert.assertEquals("one bad line, one message", 1, response.getValidationErrors().size());
		Assert.assertTrue("the message must name the line, got: " + response.getValidationErrors().get(0),
				response.getValidationErrors().get(0).startsWith("line "));

		Assert.assertEquals("nothing may have been written", before, frontcacheClient.getBotsConfig());
	}

	/**
	 * Someone else changed the file since it was read. Nothing is written, and what is on disk comes
	 * back with the refusal so the console can show what the operator was about to overwrite.
	 */
	@Test
	public void aStaleBaseHashIsRefusedWithTheCurrentContent() throws Exception {
		String before = frontcacheClient.getBotsConfig();

		org.frontcache.io.SetConfigActionResponse response = frontcacheClient.setConfig(
				org.frontcache.io.ConfigFile.BOTS, before + "\n# never lands\n",
				"0000000000000000000000000000000000000000000000000000000000000000", true);

		Assert.assertNotNull(response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		Assert.assertFalse(response.isWritten());
		Assert.assertEquals("the refusal must carry what is on disk", before, response.getCurrentContent());
		Assert.assertEquals(before, frontcacheClient.getBotsConfig());
	}

	// ---- the two reloads that did not exist before --------------------------------------------

	/**
	 * conf/dynamic-urls.conf had no live reload at all: it is read by FCConfig's static initializer,
	 * and the {@code reload} action does not re-read it, so adding a pattern meant restarting the node.
	 */
	@Test
	public void dynamicUrlsCanBeReloadedWithoutARestart() throws Exception {
		org.frontcache.io.ReloadDynamicUrlsActionResponse response = frontcacheClient.reloadDynamicURLs();

		Assert.assertNotNull("reload-dynamic-urls must answer", response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		Assert.assertTrue("both e2e nodes ship patterns, so the count must not be zero - a zero here"
				+ " would mean every one of them failed to parse", 0 < response.getPatternCount());

		// The patterns are still enforced afterwards, i.e. the swap put a live set in place
		Assert.assertFalse("the node must still know its dynamic URLs", frontcacheClient.getDynamicUrlPatterns().isEmpty());
	}

	/**
	 * conf/resilience.properties had none either - it is a cached singleton with two further caches
	 * built from it. The warnings are asserted because they are the honest half of the answer: circuit
	 * state is lost, and a changed thread-pool queue size cannot be applied at all.
	 */
	@Test
	public void resilienceCanBeReloadedWithoutARestart() throws Exception {
		org.frontcache.io.ReloadResilienceActionResponse response = frontcacheClient.reloadResilience();

		Assert.assertNotNull("reload-resilience must answer", response);
		Assert.assertEquals(org.frontcache.io.ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		Assert.assertTrue("the circuit-state reset must be reported, got " + response.getWarnings(),
				response.getWarnings().stream().anyMatch(w -> w.contains("circuit-breaker state")));

		// and the node keeps serving - the reload must not have left it without a config
		Assert.assertNotNull(frontcacheClient.getResilienceConfig());
	}


}
