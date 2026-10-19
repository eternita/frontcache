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
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * What Frontcache tells the origin it will accept - and the one path where that is now the client's
 * choice rather than Frontcache's.
 *
 * On the CACHEABLE path Frontcache must normalize this to {@code gzip}: it caches the bytes, the
 * include processor has to be able to scan them, and an encoding Frontcache cannot decode (br, zstd)
 * would otherwise be cached and replayed to clients that never asked for it. On the BYPASS path the
 * body is streamed straight through, never parsed and never cached, so forcing gzip only means
 * Frontcache decompresses a response and then ships it uncompressed. That path can opt out via
 * {@code front-cache.bypass.passthrough-encoding}.
 */
public class AcceptEncodingNormalizationTest {

	private static HttpServletRequest requestAccepting(String acceptEncoding) {
		HttpServletRequest request = mock(HttpServletRequest.class);

		when(request.getHeaderNames()).thenReturn(enumeration("Accept-Encoding", "Accept", "User-Agent"));
		when(request.getHeaders("Accept-Encoding")).thenReturn(enumeration(acceptEncoding));
		when(request.getHeaders("Accept")).thenReturn(enumeration("text/html"));
		when(request.getHeaders("User-Agent")).thenReturn(enumeration("probe/1.0"));

		return request;
	}

	private static Enumeration<String> enumeration(String... values) {
		return Collections.enumeration(java.util.Arrays.asList(values));
	}

	/** Look a header up case-insensitively - buildRequestHeaders keys by the wire casing. */
	private static List<String> header(Map<String, List<String>> headers, String name) {
		for (Map.Entry<String, List<String>> entry : headers.entrySet())
			if (entry.getKey().equalsIgnoreCase(name))
				return entry.getValue();

		return null;
	}

	@Test
	public void theCacheablePathAlwaysAsksForGzipOnly() {
		Map<String, List<String>> headers =
				FCUtils.buildRequestHeaders(requestAccepting("br, gzip, deflate, zstd"));

		assertEquals("the client's br/zstd must never reach the origin on a cacheable request",
				java.util.Arrays.asList("gzip"), header(headers, FCHeaders.ACCEPT_ENCODING));
	}

	/** The default overload is the cacheable one, so nothing changes for existing callers. */
	@Test
	public void theDefaultOverloadNormalizes() {
		assertEquals(FCUtils.buildRequestHeaders(requestAccepting("br"), true).get(FCHeaders.ACCEPT_ENCODING),
				FCUtils.buildRequestHeaders(requestAccepting("br")).get(FCHeaders.ACCEPT_ENCODING));
	}

	@Test
	public void thePassthroughPathForwardsTheClientsOwnAcceptEncoding() {
		Map<String, List<String>> headers =
				FCUtils.buildRequestHeaders(requestAccepting("br, gzip"), false);

		assertEquals("the client asked for br and nothing here will parse or cache the body",
				java.util.Arrays.asList("br, gzip"), header(headers, FCHeaders.ACCEPT_ENCODING));
	}

	/**
	 * A client that sent no Accept-Encoding must not have one invented for it in passthrough mode -
	 * it asked for identity, and it should get identity.
	 */
	@Test
	public void passthroughAddsNoAcceptEncodingWhenTheClientSentNone() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeaderNames()).thenReturn(enumeration("Accept"));
		when(request.getHeaders("Accept")).thenReturn(enumeration("text/html"));

		assertNull(header(FCUtils.buildRequestHeaders(request, false), FCHeaders.ACCEPT_ENCODING));

		// ...whereas the cacheable path still asks for gzip, because it needs bytes it can read
		assertEquals(java.util.Arrays.asList("gzip"),
				header(FCUtils.buildRequestHeaders(request, true), FCHeaders.ACCEPT_ENCODING));
	}

	/** Whatever the mode, the hop-by-hop headers stay stripped. */
	@Test
	public void hopByHopHeadersAreStrippedInBothModes() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeaderNames()).thenReturn(enumeration("Host", "Connection", "Content-Length", "Accept-Encoding"));
		when(request.getHeaders("Host")).thenReturn(enumeration("www.example.com"));
		when(request.getHeaders("Connection")).thenReturn(enumeration("keep-alive"));
		when(request.getHeaders("Content-Length")).thenReturn(enumeration("42"));
		when(request.getHeaders("Accept-Encoding")).thenReturn(enumeration("gzip"));

		for (boolean normalize : new boolean[] { true, false })
		{
			Map<String, List<String>> headers = FCUtils.buildRequestHeaders(request, normalize);
			for (String stripped : new String[] { "Host", "Connection", "Content-Length" })
				assertTrue("normalize=" + normalize + " must still strip " + stripped,
						null == header(headers, stripped));
		}
	}
}
