/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.junit.Test;

/**
 * Regression tests for bodyless origin responses on the cacheable path.
 *
 * A conditional request (If-None-Match / If-Modified-Since) forwarded to the origin can yield a
 * 304 Not Modified with no entity; 204 No Content is likewise bodyless. HttpClient returns null
 * from getEntity() in those cases. FCUtils.httpResponse2WebComponent used to call
 * response.getEntity().getContent() unguarded, so a 304/204 threw a NullPointerException that
 * escaped FC_ThroughCache_HttpClient.run() as a Hystrix FAILURE - the client then got the default
 * fallback (a ~107-byte error string) with a 200 instead of the real bodyless response.
 *
 * This was hit constantly on heavily-crawled, conditionally-cached URLs like /robots.txt.
 */
public class OriginNotModifiedTest {

	private RequestContext newContext() {
		RequestContext ctx = new RequestContext();
		ctx.setDomainContext(new DomainContext("test.org", "test-key", "origin.test.org", "80", "443"));
		return ctx;
	}

	/** A mock CloseableHttpResponse with the given status code and NO entity (bodyless response). */
	private CloseableHttpResponse bodylessResponse(int statusCode, String reason) {
		CloseableHttpResponse response = mock(CloseableHttpResponse.class);
		when(response.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), statusCode, reason));
		when(response.getEntity()).thenReturn(null); // 304 / 204 carry no entity
		when(response.getAllHeaders()).thenReturn(new Header[0]);
		return response;
	}

	/**
	 * The exact failing scenario: the cacheable origin command gets a 304 with no body. run() must
	 * relay it (status 304, empty body) instead of throwing NPE -> FAILURE -> fallback.
	 */
	@Test
	public void throughCacheRelays304NotModifiedWithoutNPE() throws Exception {
		RequestContext ctx = newContext();
		Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();

		CloseableHttpResponse response = bodylessResponse(304, "Not Modified");
		HttpClient client = mock(HttpClient.class);
		when(client.execute(any(HttpHost.class), any(HttpRequest.class))).thenReturn(response);

		// before the fix this threw NullPointerException
		WebResponse resp = new FC_ThroughCache_HttpClient(
				"http://origin.test.org/robots.txt", requestHeaders, client, ctx).run();

		assertEquals("304 status must be relayed to the client", 304, resp.getStatusCode());
		assertEquals("304 carries no body", 0, resp.getContent().length);
		assertFalse("a 304 must NOT be treated as a hystrix fallback", ctx.isHystrixFallback());
	}

	/** Same guard covers 204 No Content (also bodyless) - exercised directly on FCUtils. */
	@Test
	public void httpResponse2WebComponentHandles204NoContent() throws Exception {
		RequestContext ctx = newContext();

		WebResponse resp = FCUtils.httpResponse2WebComponent(
				"http://origin.test.org/x", bodylessResponse(204, "No Content"), ctx);

		assertEquals(204, resp.getStatusCode());
		assertEquals(0, resp.getContent().length);
	}
}
