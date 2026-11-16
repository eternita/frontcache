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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase X3 of the plugin architecture on a node with no plugin: the reserved {@code /fc-x/} prefix and the
 * {@code challenge} guard action with its provider absent - which is every Community node, and the reason both must
 * be non-events there.
 *
 * <ul>
 * <li>{@code /fc-x/} is the node's own on both nodes - server mode and filter mode - and a path no plugin owns
 *     answers a one-line 404, {@code no-store}, without reaching the origin;</li>
 * <li>a {@code challenge pow} rule with no provider acts as a dry run (the page is served, the rule counts its
 *     match), and {@code challenge pow else reject:429 ...} as its else action.</li>
 * </ul>
 */
public class StandaloneChallengeTests {

	private static final String STANDALONE = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST;
	private static final String FILTER = TestConfig.FRONTCACHE_FILTER_TEST_BASE_URI_LOCALHOST;

	private CloseableHttpClient client;

	@Before
	public void setUp() {
		client = HttpClients.custom()
				.setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build()).build();
	}

	@After
	public void tearDown() throws Exception {
		client.close();
	}

	@Test
	public void thePluginPrefixIsTheNodesOwnInBothModes() throws Exception {
		for (String node : List.of(STANDALONE, FILTER))
			for (HttpUriRequestBase request : List.of(new HttpGet(node + "fc-x/"), new HttpGet(node + "fc-x/challenge/?p=pow"),
					new HttpGet(node + "fc-x"), new HttpPost(node + "fc-x/challenge/verify")))
			{
				Response response = execute(request);
				String what = request.getMethod() + " " + request.getRequestUri() + " on " + node;
				assertEquals(what, 404, response.status);
				assertEquals(what + " - the node's one line, not the origin's 404 page", "404 Not Found\n", response.body);
				assertNotNull(what, response.cacheControl);
				assertTrue(what + ": " + response.cacheControl, response.cacheControl.startsWith("no-store"));
			}
	}

	@Test
	public void aChallengeWithItsProviderAbsentIsADryRun() throws Exception {
		long before = hits("challenge-e2e");

		Response response = execute(new HttpGet(STANDALONE + "common/challenge/a.jsp"));

		assertEquals("served as if the rule were not there", 200, response.status);
		assertTrue("got: " + response.body, response.body.contains("challenge-e2e"));
		assertEquals("the rule counted its match", before + 1, hits("challenge-e2e"));
	}

	@Test
	public void aChallengeWithItsProviderAbsentTakesItsElseAction() throws Exception {
		Response response = execute(new HttpGet(STANDALONE + "common/challenge/else.jsp"));

		assertEquals(429, response.status);
		assertTrue("got: " + response.body, response.body.contains("No challenge here"));
	}

	@Test
	public void theRuleIsReportedWithItsAction() throws Exception {
		HttpGet get = new HttpGet(STANDALONE + "frontcache-io?action=get-guard-rules");
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);
		Response response = execute(get);

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains("challenge pow else reject:429"));
	}

	// ---- helpers ------------------------------------------------------------------------------

	/** A rule's hit count, read from get-guard-rules' JSON. */
	private long hits(String rule) throws Exception {
		HttpGet get = new HttpGet(STANDALONE + "frontcache-io?action=get-guard-rules");
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);
		String body = execute(get).body;
		int at = body.indexOf("\"name\":\"" + rule + "\"");
		assertTrue("no rule " + rule + " in " + body, -1 < at);
		int end = body.indexOf('}', at);
		int hitsAt = body.indexOf("\"hits\":", at);
		assertTrue("no hits for " + rule + " in " + body, -1 < hitsAt && hitsAt < end);
		int from = hitsAt + "\"hits\":".length();
		int to = from;
		while (to < body.length() && Character.isDigit(body.charAt(to)))
			to++;
		return Long.parseLong(body.substring(from, to));
	}

	private static final class Response {
		final int status;
		final String body;
		final String cacheControl;

		Response(int status, String body, String cacheControl) {
			this.status = status;
			this.body = body;
			this.cacheControl = cacheControl;
		}
	}

	private Response execute(HttpUriRequestBase request) throws Exception {
		try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
			String body = (null == response.getEntity()) ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
			Header cacheControl = response.getFirstHeader("Cache-Control");
			return new Response(response.getCode(), body, (null == cacheControl) ? null : cacheControl.getValue());
		}
	}
}
