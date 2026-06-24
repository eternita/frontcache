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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.frontcache.core.DomainContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 *
 * Both origin-calling Hystrix commands must always advertise "Accept-Encoding: gzip" (and only
 * gzip) to the origin, regardless of what the inbound client sent. Our HttpClient transparently
 * decodes gzip, so Frontcache always receives plaintext it can parse for includes and cache -
 * and never an encoding (br/zstd) it can't decode that would then be cached and served wrongly.
 *
 * These tests capture the actual HttpRequest handed to the origin HttpClient and assert the
 * single normalized Accept-Encoding header.
 *
 */
public class OriginAcceptEncodingTest {

	private RequestContext newContext() {
		RequestContext ctx = new RequestContext();
		ctx.setDomainContext(new DomainContext("test.org", "test-key", "origin.test.org", "80", "443"));
		return ctx;
	}

	/** A CloseableHttpResponse minimal enough for FCUtils.httpResponse2WebComponent to consume. */
	private CloseableHttpResponse okResponse() throws Exception {
		CloseableHttpResponse response = mock(CloseableHttpResponse.class);
		when(response.getStatusLine()).thenReturn(new BasicStatusLine(new ProtocolVersion("HTTP", 1, 1), 200, "OK"));
		when(response.getEntity()).thenReturn(new StringEntity("ok"));
		when(response.getAllHeaders()).thenReturn(new Header[0]);
		return response;
	}

	private String singleAcceptEncoding(HttpRequest request) {
		Header[] ae = request.getHeaders(FCHeaders.ACCEPT_ENCODING);
		assertEquals("exactly one Accept-Encoding header must be sent to origin", 1, ae.length);
		return ae[0].getValue();
	}

	/**
	 * Cacheable path: FC_ThroughCache_HttpClient.run() must send Accept-Encoding: gzip to origin
	 * even though the inbound request advertised brotli.
	 */
	@Test
	public void throughCacheForcesGzipToOrigin() throws Exception {
		RequestContext ctx = newContext();

		// inbound client advertised br - must NOT be forwarded to the origin
		Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();
		requestHeaders.put(FCHeaders.ACCEPT_ENCODING, Arrays.asList("gzip, deflate, br"));

		CloseableHttpResponse response = okResponse(); // build mock before stubbing client.execute
		HttpClient client = mock(HttpClient.class);
		ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
		when(client.execute(any(HttpHost.class), captor.capture())).thenReturn(response);

		new FC_ThroughCache_HttpClient("http://origin.test.org/page.htm", requestHeaders, client, ctx).run();

		assertEquals("gzip", singleAcceptEncoding(captor.getValue()));
	}

	/**
	 * Bypass path: FC_BypassCache.forward() must send Accept-Encoding: gzip to origin even though
	 * the inbound request advertised brotli.
	 */
	@Test
	public void bypassForcesGzipToOrigin() throws Exception {
		RequestContext ctx = newContext();
		ctx.setOriginURL(new URL("http://origin.test.org:80/"));
		ctx.setRequestQueryString("");

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		headers.put(FCHeaders.ACCEPT_ENCODING, Arrays.asList("gzip, deflate, br"));

		CloseableHttpResponse response = okResponse(); // build mock before stubbing client.execute
		HttpClient client = mock(HttpClient.class);
		ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
		when(client.execute(any(HttpHost.class), captor.capture())).thenReturn(response);

		FC_BypassCache cmd = new FC_BypassCache(client, ctx);
		cmd.forward(client, "GET", "/page.htm", null, headers, null);

		assertEquals("gzip", singleAcceptEncoding(captor.getValue()));
	}

	/**
	 * Even when the inbound request advertises an encoding we cannot handle (zstd) and no gzip,
	 * the origin call is still pinned to gzip.
	 */
	@Test
	public void throughCacheForcesGzipEvenWhenClientWantsZstdOnly() throws Exception {
		RequestContext ctx = newContext();

		Map<String, List<String>> requestHeaders = new HashMap<String, List<String>>();
		requestHeaders.put(FCHeaders.ACCEPT_ENCODING, Arrays.asList("zstd"));

		CloseableHttpResponse response = okResponse(); // build mock before stubbing client.execute
		HttpClient client = mock(HttpClient.class);
		ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
		when(client.execute(any(HttpHost.class), captor.capture())).thenReturn(response);

		new FC_ThroughCache_HttpClient("http://origin.test.org/page.htm", requestHeaders, client, ctx).run();

		assertEquals("gzip", singleAcceptEncoding(captor.getValue()));
	}
}
