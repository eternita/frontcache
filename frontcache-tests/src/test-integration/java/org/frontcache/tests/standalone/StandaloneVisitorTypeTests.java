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
package org.frontcache.tests.standalone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.frontcache.core.FCHeaders;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.frontcache.tests.base.TestUtils;
import org.frontcache.tests.base.TestsBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Declared visitor types, end to end on the standalone node, whose {@code bots.conf} declares {@code customer like
 * guest} (cookie {@code fc_e2e_customer}) and {@code monitor-e2e like bot} (User-Agent {@code fc-e2e-monitor}) - the
 * rules naming them above the {@code @type} lines. The filter node behind it declares nothing, and it does not matter:
 * the page's node decides the TTL and which includes resolve.
 *
 * What is pinned: a type with {@code :0} reaches the origin on every request while guests share a cached copy it never
 * overwrites; {@code client="customer"} / {@code client="!customer"} / {@code client="guest"} by is-a; a TTL only a
 * declared type has caches for it alone (not for its base); and the trace header's last column is the leaf while the
 * client-type column keeps the root.
 */
public class StandaloneVisitorTypeTests extends TestsBase {

	private static final String STANDALONE = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST;

	private static final String CHROME = "Mozilla/5.0 (Macintosh) Chrome/140";
	private static final String CUSTOMER_COOKIE = "fc_e2e_customer=1";
	private static final String MONITOR = "fc-e2e-monitor/1.0";

	private CloseableHttpClient client;

	@Before
	public void openClient() {
		client = HttpClients.createDefault();
	}

	@After
	public void closeClient() throws Exception {
		client.close();
	}

	/** a.jsp: maxage="bot:60, guest:60, customer:0" */
	@Test
	public void aTypeWithNoCacheReachesTheOriginWhileGuestsShareTheCachedCopy() throws Exception {
		String page = "standalone/visitor-types/a.jsp";

		Fetched guest = get(page, CHROME, null);
		assertEquals("a", guest.body);
		assertEquals("bot:60, guest:60, customer:0", guest.maxage);
		assertFalse(guest.cached());
		assertTrue("guests share the copy", get(page, CHROME, null).cached());

		for (int i = 0; i < 3; i++)
		{
			Fetched customer = get(page, CHROME, CUSTOMER_COOKIE);
			assertEquals("a", customer.body);
			assertFalse("customer:0 - every customer request reaches the origin: " + customer.trace, customer.cached());
			assertEquals("the client-type column keeps the root: " + customer.trace, "guest", customer.column(-3));
			assertEquals("the deciding rule", "customer-e2e", customer.column(-2));
			assertEquals("the visitor type is the last column", "customer", customer.column(-1));
		}

		assertTrue("a customer never writes the entry, so guests still hit the copy they stored",
				get(page, CHROME, null).cached());
	}

	/** b.jsp: client="customer" (C), client="!customer" (O), client="guest" (G) */
	@Test
	public void includesSelectByIsAAndByExclusion() throws Exception {
		String page = "standalone/visitor-types/b.jsp";

		assertEquals("a guest: not the customer fragment, the others and the guest one", "bOG", get(page, CHROME, null).body);
		assertEquals("a customer is a guest too, so it gets G - and C instead of O", "bCG",
				get(page, CHROME, CUSTOMER_COOKIE).body);
		assertEquals("a bot is not a customer and not a guest", "bO", get(page, "Googlebot/2.1", null).body);
		assertEquals("a type like bot is a bot", "bO", get(page, MONITOR, null).body);
	}

	/** c.jsp: maxage="monitor-e2e:60" - a TTL for the declared type alone, not for bot, its base */
	@Test
	public void aTtlOnlyADeclaredTypeHasCachesForItAlone() throws Exception {
		String page = "standalone/visitor-types/c.jsp";

		Fetched monitor = get(page, MONITOR, null);
		assertEquals("c", monitor.body);
		assertFalse(monitor.cached());
		assertEquals("the visitor type is the last column: " + monitor.trace, "monitor-e2e", monitor.column(-1));
		assertEquals("its client type is its base: " + monitor.trace, "bot", monitor.column(-3));

		assertTrue("stored for the monitor - an entry only a declared type may read is still subject to cache",
				get(page, MONITOR, null).cached());

		assertFalse("bot is the base, but the page named only the monitor, so a bot reaches the origin",
				get(page, "Googlebot/2.1", null).cached());
		assertFalse(get(page, CHROME, null).cached());
		assertTrue("and a bot's request does not take the copy away", get(page, MONITOR, null).cached());
	}

	/** get-bots carries the verdict as written - what the console's Visitor Types screen renders. */
	@Test
	public void getBotsReportsTheDeclaredVerdict() throws Exception {
		HttpGet get = new HttpGet(STANDALONE + "frontcache-io?action=get-bots");
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);
		String body;
		try (ClassicHttpResponse response = client.executeOpen(null, get, null)) {
			body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
		}
		assertTrue(body, body.contains("\"name\":\"customer-e2e\""));
		assertTrue(body, body.contains("\"clientType\":\"customer\""));
		assertTrue(body, body.contains("\"clientType\":\"monitor-e2e\""));
	}

	// ---- helpers --------------------------------------------------------------------------

	private Fetched get(String path, String userAgent, String cookie) throws Exception {
		HttpGet get = new HttpGet(STANDALONE + path);
		get.addHeader("User-Agent", userAgent);
		get.addHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		if (null != cookie)
			get.addHeader("Cookie", cookie);
		try (ClassicHttpResponse response = client.executeOpen(null, get, null)) {
			assertEquals(200, response.getCode());
			Header trace = response.getFirstHeader(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");
			assertNotNull("no trace header for " + path, trace);
			Header maxage = response.getFirstHeader(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
			return new Fetched(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8).trim(), trace.getValue(),
					(null == maxage) ? null : maxage.getValue());
		}
	}

	private static final class Fetched {
		final String body;
		final String trace;
		final String maxage;

		Fetched(String body, String trace, String maxage) {
			this.body = body;
			this.trace = trace;
			this.maxage = maxage;
		}

		boolean cached() {
			return TestUtils.isRequestFromCache(trace);
		}

		/** a column of the trace header, counted from the end (-1 = last), quotes stripped */
		String column(int fromEnd) {
			List<String> columns = new ArrayList<String>();
			StringBuilder current = new StringBuilder();
			boolean quoted = false;
			for (char c : trace.toCharArray())
			{
				if ('"' == c)
					quoted = !quoted;
				else if (' ' == c && !quoted)
				{
					columns.add(current.toString());
					current.setLength(0);
				}
				else
					current.append(c);
			}
			columns.add(current.toString());
			return columns.get(columns.size() + fromEnd);
		}
	}
}
