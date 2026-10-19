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
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.frontcache.core.FCHeaders;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.frontcache.tests.base.EdgeTokens;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code front-cache.edge-token} on the running chain: test -> standalone (:9080) -> filter (:8080)
 * -> app, both nodes configured with {@link EdgeTokens#TEST_EDGE_TOKEN}. See
 * {@code org.frontcache.core.EdgeToken}.
 *
 * Two halves:
 *
 * <ul>
 * <li><b>Enforcement</b> - the {@code edge-only-e2e} rule in FRONTCACHE_HOME_STANDALONE's
 *     guard-rules.conf refuses {@code /common/edge-only/} unless the request carries the token. The
 *     test plays the edge here, by sending the header itself. The rule lives on the standalone node
 *     because the filter node deliberately ships no guard-rules.conf (that keeps get-guard-config's
 *     "file absent" path covered - see ClientTests).</li>
 * <li><b>Sending</b> - {@code /common/edge-token/echo.jsp} prints the token the app received. In filter
 *     mode the app sees the raw servlet request, so that is exactly what the node in front of it put
 *     on the wire: the standalone node's cache-miss and bypass calls, and the filter node's own
 *     include fetch.</li>
 * </ul>
 */
public class StandaloneEdgeTokenTests {

	private static final String STANDALONE = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST;
	private static final String FILTER = TestConfig.FRONTCACHE_FILTER_TEST_BASE_URI_LOCALHOST;

	private static final String EDGE_ONLY_PAGE = "common/edge-only/a.jsp";
	private static final String ECHO_PAGE = "common/edge-token/echo.jsp";
	private static final String PAGE_WITH_ECHO_INCLUDE = "common/edge-token/page.jsp";

	private static final String RECEIVED_TOKEN = "edge-token=[" + EdgeTokens.TEST_EDGE_TOKEN + "]";
	private static final String RECEIVED_NOTHING = "edge-token=[absent]";

	private CloseableHttpClient client;

	@Before
	public void setUp() {
		client = HttpClients.createDefault();
	}

	@After
	public void tearDown() throws Exception {
		client.close();
	}

	// ---- enforcement: the edge-token guard predicate ----------------------------------------------

	@Test
	public void aRequestWithoutTheTokenIsRefused() throws Exception {
		Response response = execute(new HttpGet(STANDALONE + EDGE_ONLY_PAGE));

		assertEquals(403, response.status);
		assertTrue("the rule's body should say why, got: " + response.body, response.body.contains("Reached directly"));
	}

	@Test
	public void aRequestWithAWrongTokenIsRefused() throws Exception {
		HttpGet get = new HttpGet(STANDALONE + EDGE_ONLY_PAGE);
		get.addHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, EdgeTokens.TEST_EDGE_TOKEN + "-not");

		assertEquals(403, execute(get).status);
	}

	@Test
	public void aRequestWithTheTokenIsServed() throws Exception {
		HttpGet get = new HttpGet(STANDALONE + EDGE_ONLY_PAGE);
		get.addHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, EdgeTokens.TEST_EDGE_TOKEN);

		Response response = execute(get);

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains("edge-only"));
	}

	@Test
	public void theRuleIsReportedByTheManagementApiWithItsHits() throws Exception {
		execute(new HttpGet(STANDALONE + EDGE_ONLY_PAGE)); // one refusal, so there is a hit to report

		HttpGet get = new HttpGet(STANDALONE + "frontcache-io?action=get-guard-rules");
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);
		Response response = execute(get);

		assertEquals(200, response.status);
		assertTrue("rule missing from get-guard-rules:\n" + response.body, response.body.contains("edge-only-e2e"));
		assertTrue("the condition is rendered from describe(), which the console's Guard tab shows:\n" + response.body,
				response.body.contains("!edge-token"));
	}

	// ---- sending: what reaches the app behind the chain ---------------------------------------

	/** The control: asked directly, the app receives no token - so the ones below come from a node. */
	@Test
	public void theAppAskedDirectlyReceivesNoToken() throws Exception {
		Response response = execute(new HttpGet(FILTER + ECHO_PAGE));

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains(RECEIVED_NOTHING));
	}

	@Test
	public void aCacheMissThroughTheEdgeCarriesTheToken() throws Exception {
		Response response = execute(new HttpGet(STANDALONE + ECHO_PAGE));

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains(RECEIVED_TOKEN));
	}

	/**
	 * The bypass path is the one that carries no x-frontcache-request-id, so without the token an
	 * edge's relayed POST would look exactly like a client that went around it.
	 */
	@Test
	public void aBypassedPostThroughTheEdgeCarriesTheToken() throws Exception {
		HttpPost post = new HttpPost(STANDALONE + ECHO_PAGE);
		post.setEntity(new StringEntity("a=1", ContentType.APPLICATION_FORM_URLENCODED));

		Response response = execute(post);

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains(RECEIVED_TOKEN));
	}

	/**
	 * A client's own copy stops at the first node: the standalone node drops it and sends its own,
	 * so a forged value never reaches the next hop.
	 */
	@Test
	public void aClientsTokenIsReplacedNotRelayed() throws Exception {
		HttpGet get = new HttpGet(STANDALONE + ECHO_PAGE);
		get.addHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, "forged-by-the-client");

		Response response = execute(get);

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains(RECEIVED_TOKEN));
	}

	/**
	 * A filter-mode node fetches its own includes over HTTP from its origin-host - itself - so a second
	 * layer enforcing edge-token would refuse its own fragments unless that fetch carries the token too.
	 * Asked directly, with no token on the page request, the fragment must still have received one.
	 */
	@Test
	public void aFilterNodesOwnIncludeFetchCarriesTheToken() throws Exception {
		Response response = execute(new HttpGet(FILTER + PAGE_WITH_ECHO_INCLUDE));

		assertEquals(200, response.status);
		assertTrue("got: " + response.body, response.body.contains("page:"));
		assertTrue("got: " + response.body, response.body.contains(RECEIVED_TOKEN));
	}

	// ---- helpers ------------------------------------------------------------------------------

	private static final class Response {
		final int status;
		final String body;

		Response(int status, String body) {
			this.status = status;
			this.body = body;
		}
	}

	private Response execute(HttpUriRequestBase request) throws Exception {
		try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
			String body = (null == response.getEntity()) ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
			return new Response(response.getCode(), body);
		}
	}

}
