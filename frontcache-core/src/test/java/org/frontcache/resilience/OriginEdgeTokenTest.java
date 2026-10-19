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
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.frontcache.FCConfig;
import org.frontcache.core.EdgeToken;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Both origin-calling commands put the edge token on the wire - see {@link EdgeToken}.
 *
 * The bypass path is the one that matters most: it is the only origin call that carries no
 * {@code x-frontcache-request-id}, so without the token an edge's bypassed POST is indistinguishable
 * from a client that went straight to the second layer
 * (docs/direct-access-restriction-proposal.md §3).
 *
 * These capture the request actually handed to the origin HttpClient, like
 * {@link OriginAcceptEncodingTest}.
 */
public class OriginEdgeTokenTest {

	private static final String TOKEN = "e2e-0123456789abcdef0123456789abcdef";
	private static final String ORIGIN = "origin.test.org";

	private final String originalToken = FCConfig.getProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");
	private final String originalOriginHost = FCConfig.getProperty("front-cache.origin-host", "");

	@Before
	public void setUp()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, TOKEN);
		FCConfig.setProperty("front-cache.origin-host", ORIGIN);
	}

	@After
	public void tearDown()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, originalToken);
		FCConfig.setProperty("front-cache.origin-host", originalOriginHost);
	}

	private RequestContext newContext() {
		RequestContext ctx = new RequestContext();
		ctx.setDomain("test.org"); // the command group key
		return ctx;
	}

	private ClassicHttpResponse okResponse() throws Exception {
		ClassicHttpResponse response = mock(ClassicHttpResponse.class);
		when(response.getCode()).thenReturn(200);
		when(response.getReasonPhrase()).thenReturn("OK");
		when(response.getEntity()).thenReturn(new StringEntity("ok"));
		when(response.getHeaders()).thenReturn(new Header[0]);
		return response;
	}

	private ClassicHttpRequest throughCache(String url, Map<String, List<String>> requestHeaders) throws Exception {
		ClassicHttpResponse response = okResponse(); // build mock before stubbing client.executeOpen
		HttpClient client = mock(HttpClient.class);
		ArgumentCaptor<ClassicHttpRequest> captor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
		when(client.executeOpen(any(HttpHost.class), captor.capture(), any())).thenReturn(response);

		new FC_ThroughCache_HttpClient(url, requestHeaders, client, newContext()).run();

		return captor.getValue();
	}

	private ClassicHttpRequest bypass(String verb) throws Exception {
		RequestContext ctx = newContext();
		ctx.setOriginURL(new URL("http://" + ORIGIN + ":80/"));
		ctx.setRequestQueryString("");

		ClassicHttpResponse response = okResponse();
		HttpClient client = mock(HttpClient.class);
		ArgumentCaptor<ClassicHttpRequest> captor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
		when(client.executeOpen(any(HttpHost.class), captor.capture(), any())).thenReturn(response);

		new FC_BypassCache(client, ctx).forward(client, verb, "/page.htm", null,
				new HashMap<String, List<String>>(), null);

		return captor.getValue();
	}

	private static String token(ClassicHttpRequest request) {
		Header[] headers = request.getHeaders(FCHeaders.X_FRONTCACHE_EDGE_TOKEN);
		if (0 == headers.length)
			return null;

		assertEquals("exactly one edge token must be sent", 1, headers.length);
		return headers[0].getValue();
	}

	// ---- the cache-miss path (pages, origin-mode includes, a filter node's own include fetch) ----

	@Test
	public void aCacheMissSendsTheToken() throws Exception {
		assertEquals(TOKEN, token(throughCache("http://" + ORIGIN + "/page.htm", new HashMap<String, List<String>>())));
	}

	/**
	 * buildRequestHeaders already drops an inbound copy; this is the second line - whatever is in the
	 * header map, the value on the wire is this node's own.
	 */
	@Test
	public void aCacheMissSendsItsOwnTokenOverAnythingInTheHeaderMap() throws Exception {
		Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();
		requestHeaders.put(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, Arrays.asList("forged"));

		assertEquals(TOKEN, token(throughCache("http://" + ORIGIN + "/page.htm", requestHeaders)));
	}

	/** An edge-mode include of an absolute URL goes where it points - the token does not follow it. */
	@Test
	public void aFetchFromAnotherHostCarriesNoToken() throws Exception {
		assertNull(token(throughCache("http://third-party.example.net/widget.htm", new HashMap<String, List<String>>())));
	}

	@Test
	public void aNodeWithoutATokenSendsNone() throws Exception {
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");

		assertNull(token(throughCache("http://" + ORIGIN + "/page.htm", new HashMap<String, List<String>>())));
	}

	// ---- the bypass path ---------------------------------------------------------------------

	@Test
	public void aBypassedGetSendsTheToken() throws Exception {
		assertEquals(TOKEN, token(bypass("GET")));
	}

	/** Any other verb takes the same path (POST would need a servlet request for its entity). */
	@Test
	public void aBypassedDeleteSendsTheToken() throws Exception {
		assertEquals(TOKEN, token(bypass("DELETE")));
	}

}
