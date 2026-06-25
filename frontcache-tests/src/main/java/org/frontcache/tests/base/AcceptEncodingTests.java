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
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URL;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * Accept-Encoding handling.
 *
 * Frontcache normalizes the encoding it advertises to the origin to gzip only (which its
 * internal HttpClient can transparently decode), so it always holds/caches plaintext and
 * NEVER serves an encoding the client did not ask for - in particular it must never hand
 * back brotli (which it cannot produce). These tests assert that contract for every
 * Accept-Encoding the client may send, in both filter and standalone modes.
 *
 * Requests are issued with a raw HttpClient that has content compression DISABLED, so we
 * fully control the request Accept-Encoding and observe the exact Content-Encoding / bytes
 * Frontcache returns (no client-side auto-decompression to hide a wrong encoding).
 *
 */
public abstract class AcceptEncodingTests extends TestsBase {

	// static fixture - deterministic single-byte body "a"
	private static final String STATIC_FIXTURE = "common/static-read/a.txt";
	private static final String STATIC_EXPECTED = "a";

	// cacheable JSP fixture - maxage="-1" (cache forever), renders "a"
	private static final String CACHEABLE_FIXTURE = "common/fc-headers/a.jsp";
	private static final String CACHEABLE_EXPECTED = "a";

	public abstract String getFrontCacheBaseURLDomainFC1();

	@Before
	public void setUp() throws Exception {
		super.setUp();
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	/**
	 * Browser-style client (accepts br) - body must still be correct and, because Frontcache
	 * cannot produce brotli, the response must NOT be br-encoded.
	 */
	@Test
	public void acceptEncodingGzipDeflateBr() throws Exception {
		String body = assertServedDecodable(STATIC_FIXTURE, "gzip, deflate, br");
		assertEquals(STATIC_EXPECTED, body);
	}

	/**
	 * gzip-only client - correct body, never br.
	 */
	@Test
	public void acceptEncodingGzipOnly() throws Exception {
		String body = assertServedDecodable(STATIC_FIXTURE, "gzip");
		assertEquals(STATIC_EXPECTED, body);
	}

	/**
	 * Client explicitly refuses compression (identity) - it must receive uncompressed bytes,
	 * not gzip and not br. This is the case the old pass-through behaviour got wrong.
	 */
	@Test
	public void acceptEncodingIdentity() throws Exception {
		CloseableHttpClient client = HttpClients.custom().disableContentCompression().build();
		try {
			CloseableHttpResponse response = rawGet(client, getFrontCacheBaseURLDomainFC1() + STATIC_FIXTURE, "identity");
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());
				assertNotBrotli(response);
				assertNotCompressed(response); // client refused compression -> must be identity
				assertEquals(STATIC_EXPECTED, bodyAsString(response));
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

	/**
	 * No Accept-Encoding header at all - treated like identity; must be uncompressed and never br.
	 */
	@Test
	public void acceptEncodingAbsent() throws Exception {
		CloseableHttpClient client = HttpClients.custom().disableContentCompression().build();
		try {
			CloseableHttpResponse response = rawGet(client, getFrontCacheBaseURLDomainFC1() + STATIC_FIXTURE, null);
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());
				assertNotBrotli(response);
				assertNotCompressed(response);
				assertEquals(STATIC_EXPECTED, bodyAsString(response));
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

	/**
	 * Unsupported-by-FC encoding requested alone (e.g. zstd) - Frontcache must still answer
	 * with something the client cannot be handed wrongly: it serves identity, never the
	 * unsupported encoding and never br.
	 */
	@Test
	public void acceptEncodingZstdOnly() throws Exception {
		CloseableHttpClient client = HttpClients.custom().disableContentCompression().build();
		try {
			CloseableHttpResponse response = rawGet(client, getFrontCacheBaseURLDomainFC1() + STATIC_FIXTURE, "zstd");
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());
				assertNotBrotli(response);
				Header ce = response.getFirstHeader("Content-Encoding");
				assertFalse("must not return zstd it can't produce",
						ce != null && ce.getValue().toLowerCase().contains("zstd"));
				assertEquals(STATIC_EXPECTED, bodyAsString(response));
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

	/**
	 * A cacheable resource must be stored encoding-neutral (plaintext) so that a later request
	 * from a client that does NOT accept compression is still served correct, decodable bytes.
	 *
	 * First request -> dynamic, second request -> from cache; both decodable by an identity client.
	 */
	@Test
	public void cachedEntryServedDecodableToIdentityClient() throws Exception {
		String url = getFrontCacheBaseURLDomainFC1() + CACHEABLE_FIXTURE;
		CloseableHttpClient client = HttpClients.custom().disableContentCompression().build();
		try {
			// 1st request - populate the cache; identity client
			CloseableHttpResponse first = rawGet(client, url, "identity");
			try {
				assertEquals(200, first.getStatusLine().getStatusCode());
				assertNotBrotli(first);
				assertNotCompressed(first);
				assertEquals(CACHEABLE_EXPECTED, bodyAsString(first).trim());
				assertFalse("first request should be dynamic", isFromCache(first));
			} finally {
				first.close();
			}

			// 2nd request - served from cache; still decodable identity bytes
			CloseableHttpResponse second = rawGet(client, url, "identity");
			try {
				assertEquals(200, second.getStatusLine().getStatusCode());
				assertNotBrotli(second);
				assertNotCompressed(second);
				assertEquals(CACHEABLE_EXPECTED, bodyAsString(second).trim());
				assertTrue("second request should be served from cache", isFromCache(second));
			} finally {
				second.close();
			}
		} finally {
			client.close();
		}
	}

	// ---- helpers ----

	/**
	 * GET with content compression disabled so the raw Content-Encoding / bytes are observable.
	 * Asserts 200, not-brotli, and returns the decoded-as-UTF8 body. Caller asserts the body.
	 */
	private String assertServedDecodable(String path, String acceptEncoding) throws Exception {
		CloseableHttpClient client = HttpClients.custom().disableContentCompression().build();
		try {
			CloseableHttpResponse response = rawGet(client, getFrontCacheBaseURLDomainFC1() + path, acceptEncoding);
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());
				assertNotBrotli(response);
				return bodyAsString(response);
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

	private CloseableHttpResponse rawGet(CloseableHttpClient client, String url, String acceptEncoding) throws Exception {
		HttpHost httpHost = FCUtils.getHttpHost(new URL(url));
		HttpGet httpRequest = new HttpGet(FCUtils.buildRequestURI(url));
		if (null != acceptEncoding)
			httpRequest.addHeader(FCHeaders.ACCEPT_ENCODING, acceptEncoding);
		httpRequest.addHeader(FCHeaders.X_FRONTCACHE_TRACE, "true"); // so trace headers are emitted
		return (CloseableHttpResponse) client.execute(httpHost, httpRequest);
	}

	private String bodyAsString(CloseableHttpResponse response) throws Exception {
		return new String(getBytes(response.getEntity().getContent()), "UTF-8");
	}

	private void assertNotBrotli(CloseableHttpResponse response) {
		Header ce = response.getFirstHeader("Content-Encoding");
		assertFalse("Frontcache must never emit brotli (it cannot produce it): Content-Encoding=" + (ce == null ? null : ce.getValue()),
				ce != null && ce.getValue().toLowerCase().contains("br"));
	}

	private void assertNotCompressed(CloseableHttpResponse response) {
		Header ce = response.getFirstHeader("Content-Encoding");
		assertTrue("client did not accept compression - response must be identity, but Content-Encoding=" + (ce == null ? null : ce.getValue()),
				ce == null || ce.getValue().trim().isEmpty() || ce.getValue().equalsIgnoreCase("identity"));
	}

	private boolean isFromCache(CloseableHttpResponse response) {
		Header trace = response.getFirstHeader(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");
		return trace != null && TestUtils.isRequestFromCache(trace.getValue());
	}

}
