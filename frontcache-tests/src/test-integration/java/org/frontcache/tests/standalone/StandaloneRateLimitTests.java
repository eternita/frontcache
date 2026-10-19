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
package org.frontcache.tests.standalone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.frontcache.tests.base.TestsBase;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.junit.Before;
import org.junit.Test;

/**
 * Per-IP rate limiting against a running node.
 *
 * The unit tests cover the counter, the parser and the key resolution in isolation; this covers what
 * they cannot - that a rate: rule loaded from conf/guard-rules.conf actually runs on the request
 * path, ahead of cache and origin, and that the 429 carries the headers a client needs.
 *
 * The rule is scoped to /common/rate-limit/ in FRONTCACHE_HOME_STANDALONE/conf/guard-rules.conf.
 * That scoping is not cosmetic: the whole suite reaches this node from 127.0.0.1, so an unscoped
 * rule would count every test in the run as one client and refuse the rest of the suite.
 */
public class StandaloneRateLimitTests extends TestsBase {

	/** must match conf/guard-rules.conf on the standalone test node */
	private static final int LIMIT = 3;
	private static final long WINDOW_MILLIS = 2000L;

	private static final String RATE_LIMITED_PAGE =
			TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/rate-limit/a.jsp";

	@Before
	public void setUp() throws Exception {
		super.setUp();
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
		awaitFreshWindow();
	}

	// ---- the rule on the request path ----------------------------------------------------------

	@Test
	public void theRequestOverTheLimitIsRejectedWith429() throws Exception
	{
		WebResponse refused = burstUntilRefused();

		assertEquals(429, refused.getStatusCode());
		assertTrue("the guard body should say what happened, got: " + refused.getContentAsString(),
				refused.getContentAsString().contains("Too Many Requests"));
	}

	@Test
	public void the429CarriesRetryAfterAndIsNeverCacheable() throws Exception
	{
		WebResponse refused = burstUntilRefused();

		String retryAfter = refused.getResponseHeaderValue("Retry-After");
		assertNotNull("RFC 6585 expects Retry-After on a 429 - a crawler backs off when it sees one", retryAfter);

		int seconds = Integer.parseInt(retryAfter.trim());
		assertTrue("Retry-After was " + seconds + "s, outside the " + WINDOW_MILLIS + "ms window",
				1 <= seconds && seconds <= (WINDOW_MILLIS / 1000) + 1);

		// A cached 429 would pin this client to a rejection long after its window ended. Guard
		// REJECTS used to be sent bare - only redirects carried this.
		String cacheControl = refused.getResponseHeaderValue("Cache-Control");
		assertNotNull("a guard rejection must not be cacheable", cacheControl);
		assertTrue("expected no-store, got: " + cacheControl, cacheControl.contains("no-store"));
	}

	@Test
	public void theCountResetsWhenTheWindowRollsOver() throws Exception
	{
		burstUntilRefused();

		Thread.sleep(WINDOW_MILLIS + 200);

		assertEquals("the next window starts the client from zero", 200, get(RATE_LIMITED_PAGE).getStatusCode());
	}

	@Test
	public void includesDoNotSpendTheVisitorsBudget() throws Exception
	{
		// a.jsp stitches in b.jsp, so each page view is two requests through the engine but must
		// cost the visitor exactly one - otherwise a page's budget would depend on how many
		// fragments it happens to be built from
		for (int i = 0; i < LIMIT; i++)
			assertEquals("page view " + (i + 1) + " of " + LIMIT + " must pass",
					200, get(RATE_LIMITED_PAGE).getStatusCode());
	}

	// ---- what the console and the management API see ---------------------------------------------

	@Test
	public void theRuleIsReportedByTheManagementApiWithItsHits() throws Exception
	{
		burstUntilRefused();

		String json = managementApi("get-guard-rules");

		// The condition is rendered from GuardPredicate.describe(), which is what the console's
		// Guard Rules screen shows - so a console that predates rate limiting renders this correctly
		// with no rebuild.
		assertTrue("rate rule missing from get-guard-rules:\n" + json,
				json.contains("uri~^/common/rate-limit/ ; rate:" + LIMIT + "/2s"));
		assertTrue("rule name missing:\n" + json, json.contains("ratelimit-e2e"));
		assertTrue("the rule should have counted its rejections:\n" + json, json.matches("(?s).*\"hits\"\\s*:\\s*[1-9].*"));
	}

	// ---- helpers ----------------------------------------------------------------------------------

	private WebResponse get(String url) throws Exception {
		Page page = webClient.getPage(url);
		return page.getWebResponse();
	}

	/**
	 * Sends LIMIT+1 requests and returns the refused one.
	 *
	 * Retried, because the window is a slot on a fixed grid rather than a period that starts with
	 * the first request: a burst that straddles a boundary is counted against two slots and
	 * legitimately passes. A straddle can only make the burst PASS when it should fail, never the
	 * reverse, so retrying converges - it never hides a real regression.
	 */
	private WebResponse burstUntilRefused() throws Exception
	{
		for (int attempt = 1; attempt <= 5; attempt++)
		{
			WebResponse last = null;
			boolean straddled = false;

			for (int i = 0; i < LIMIT + 1; i++)
			{
				last = get(RATE_LIMITED_PAGE);

				if (i < LIMIT && 200 != last.getStatusCode())
				{
					// refused early - the previous window had not fully rolled over
					straddled = true;
					break;
				}
			}

			if (!straddled && 429 == last.getStatusCode())
				return last;

			awaitFreshWindow();
		}

		fail("no request was rate-limited after 5 bursts of " + (LIMIT + 1) + " requests - is the"
				+ " ratelimit-e2e rule loaded on the standalone node?");
		return null;
	}

	/** Sleeping past the window length guarantees a slot boundary was crossed, whatever the grid. */
	private void awaitFreshWindow() throws Exception {
		Thread.sleep(WINDOW_MILLIS + 200);
	}

	private String managementApi(String action) throws Exception
	{
		HttpGet get = new HttpGet(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST
				+ "frontcache-io?action=" + action);
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);

		CloseableHttpClient client = HttpClients.createDefault();
		try {
			ClassicHttpResponse response = client.execute(get);
			try {
				assertEquals(200, response.getCode());
				return EntityUtils.toString(response.getEntity());
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

}
