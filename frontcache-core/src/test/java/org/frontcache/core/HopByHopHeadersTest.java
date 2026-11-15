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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.Test;

/**
 * Hop-by-hop headers (RFC 9110 section 7.6.1) describe the client's connection to this node and never reach the
 * origin. Relaying them broke the origin call: an h2c upgrade - {@code Upgrade: h2c} + {@code HTTP2-Settings}, which
 * the JDK's HttpClient and {@code curl --http2} send on plain http - arrived without the {@code Connection} that came
 * with it, and the origin answered 400 on every forwarded path. The other direction is the origin's connection to this
 * node: its hop-by-hop response headers were stored in the cache entry and replayed to every client.
 */
public class HopByHopHeadersTest {

	private static HttpServletRequest request(Map<String, String> headers) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeaderNames()).thenReturn(Collections.enumeration(headers.keySet()));
		for (Map.Entry<String, String> h : headers.entrySet())
			when(request.getHeaders(h.getKey())).thenReturn(Collections.enumeration(List.of(h.getValue())));
		return request;
	}

	private static Set<String> forwarded(Map<String, String> headers) {
		Set<String> names = new TreeSet<>();
		for (String name : FCUtils.buildRequestHeaders(request(headers)).keySet())
			names.add(name.toLowerCase(Locale.ROOT));
		return names;
	}

	@Test
	public void anH2cUpgradeNeverReachesTheOrigin() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Connection", "Upgrade, HTTP2-Settings");
		headers.put("Upgrade", "h2c");
		headers.put("HTTP2-Settings", "AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA");
		headers.put("User-Agent", "Java-http-client/25");
		headers.put("Cookie", "fc_pass=1.ip.mac");

		Set<String> names = forwarded(headers);

		assertFalse(names.toString(), names.contains("upgrade"));
		assertFalse(names.toString(), names.contains("http2-settings"));
		assertFalse(names.toString(), names.contains("connection"));
		assertTrue("the end-to-end headers still go", names.containsAll(List.of("user-agent", "cookie")));
	}

	@Test
	public void everyHopByHopHeaderIsDroppedWhateverItsCase() {
		Map<String, String> headers = new LinkedHashMap<>();
		for (String name : Arrays.asList("Keep-Alive", "PROXY-CONNECTION", "te", "Trailer", "Transfer-Encoding",
				"UPGRADE", "Http2-Settings", "Proxy-Authorization", "connection"))
			headers.put(name, "x");
		headers.put("Accept", "text/html");

		assertEquals("only the end-to-end header, and the Accept-Encoding the node always sets",
				new TreeSet<>(List.of("accept", "accept-encoding")), forwarded(headers));
	}

	/**
	 * A fixed list, not "and whatever Connection names": honouring those tokens would let a client have this node
	 * strip a header an upstream proxy added - X-Forwarded-For, say - before the origin reads it.
	 */
	@Test
	public void aHeaderTheClientNamesInConnectionIsStillForwarded() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Connection", "X-Forwarded-For, Authorization");
		headers.put("X-Forwarded-For", "198.51.100.7");
		headers.put("Authorization", "Bearer origin-token");

		Set<String> names = forwarded(headers);

		assertTrue(names.toString(), names.containsAll(List.of("x-forwarded-for", "authorization")));
		assertFalse(names.contains("connection"));
	}

	/** The include fetch copies its headers from the same map, so it is covered by the same drop. */
	@Test
	public void theDefaultOverloadAndTheBypassOneDropTheSame() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Upgrade", "h2c");
		headers.put("Accept", "*/*");
		assertFalse(FCUtils.buildRequestHeaders(request(headers), false).containsKey("Upgrade"));
		assertFalse(FCUtils.buildRequestHeaders(request(headers), true).containsKey("Upgrade"));
	}

	private static final List<String> HOP_BY_HOP_RESPONSE = Arrays.asList("Connection", "KEEP-ALIVE", "proxy-connection",
			"TE", "Trailer", "Transfer-Encoding", "Upgrade", "HTTP2-Settings", "Proxy-Authenticate", "Content-Length");

	/** From the origin through the HTTP client (the cached path and the bypass): only end-to-end headers are kept. */
	@Test
	public void anOriginsHopByHopHeadersAreNeitherServedNorCached() {
		Header[] origin = new Header[HOP_BY_HOP_RESPONSE.size() + 3];
		int i = 0;
		for (String name : HOP_BY_HOP_RESPONSE)
			origin[i++] = new BasicHeader(name, "x");
		origin[i++] = new BasicHeader("Content-Type", "text/html");
		origin[i++] = new BasicHeader("Cache-Control", "max-age=60");
		origin[i++] = new BasicHeader("Set-Cookie", "a=b");

		assertEquals(new TreeSet<>(Arrays.asList("Cache-Control", "Content-Type", "Set-Cookie")),
				new TreeSet<>(FCUtils.revertHeaders(origin).keySet()));
	}

	/** From the origin as a wrapped servlet response (filter mode): the same drop. */
	@Test
	public void aFilterModeResponseDropsTheSame() {
		HttpServletResponse response = mock(HttpServletResponse.class);
		List<String> names = new ArrayList<>(HOP_BY_HOP_RESPONSE);
		names.add("Content-Type");
		when(response.getHeaderNames()).thenReturn(names);
		for (String name : names)
			when(response.getHeader(name)).thenReturn("x");

		assertEquals(Collections.singleton("Content-Type"), FCUtils.revertHeaders(response).keySet());
	}

	/**
	 * The engine writes an entry's headers through the same predicate, so an entry cached before the drop - which may
	 * hold one of these - does not replay it.
	 */
	@Test
	public void theWriteOutPredicateRefusesEveryHopByHopHeaderWhateverItsCase() {
		for (String name : HOP_BY_HOP_RESPONSE)
		{
			assertFalse(name, FCUtils.isIncludedHeaderToResponse(name));
			assertFalse(name, FCUtils.isIncludedHeaderToResponse(name.toUpperCase(Locale.ROOT)));
		}
		for (String name : Arrays.asList("Content-Type", "Vary", "ETag", "Location", "x-frontcache-component-maxage"))
			assertTrue(name, FCUtils.isIncludedHeaderToResponse(name));
	}
}
