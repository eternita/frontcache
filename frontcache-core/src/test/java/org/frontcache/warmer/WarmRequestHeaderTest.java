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
package org.frontcache.warmer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.junit.Test;

/**
 * The warm token is the only thing that turns a request into a warm request, so it has to be honoured
 * exactly when it should be - and it must never reach the origin.
 *
 * The second half is the one that would fail silently: the token in the origin app's access log lets
 * whoever reads that log skip the guard rules and force refreshes, and nothing on the node would show
 * it happening.
 */
public class WarmRequestHeaderTest {

	private static HttpServletRequest request(String token, String remoteAddr, String mode, String clientType) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(FCHeaders.X_FRONTCACHE_WARM)).thenReturn(token);
		when(request.getHeader(FCHeaders.X_FRONTCACHE_WARM_MODE)).thenReturn(mode);
		when(request.getHeader(FCHeaders.X_FRONTCACHE_WARM_CLIENT_TYPE)).thenReturn(clientType);
		when(request.getRemoteAddr()).thenReturn(remoteAddr);
		return request;
	}

	@Test
	public void theTokenFromALoopbackPeerMakesAWarmRequestWithTheRunsClientType() {
		RequestContext context = new RequestContext();

		assertTrue(WarmRequest.resolve(request(WarmRequest.token(), "127.0.0.1", "refresh", "guest"), context));

		assertTrue(context.isWarmRequest());
		assertEquals(WarmRequest.MODE_REFRESH, context.getWarmMode());
		assertEquals(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, context.getClientType());
		assertEquals(WarmRequest.CLIENT_TYPE_RULE, context.getClientTypeRule());
	}

	@Test
	public void ipv6LoopbackCountsToo() {
		assertTrue(WarmRequest.resolve(request(WarmRequest.token(), "0:0:0:0:0:0:0:1", "fill", "bot"), new RequestContext()));
		assertTrue(WarmRequest.resolve(request(WarmRequest.token(), "[::1]", "fill", "bot"), new RequestContext()));
	}

	/** A wrong token behaves exactly like no header - no error, no signal, nothing on the context. */
	@Test
	public void aWrongTokenIsTheSameAsNoHeader() {
		for (String token : new String[] { null, "", "x", WarmRequest.token() + "0", WarmRequest.token().substring(1) })
		{
			RequestContext context = new RequestContext();
			assertFalse(WarmRequest.resolve(request(token, "127.0.0.1", "fill", "bot"), context));
			assertFalse(context.isWarmRequest());
			assertNull(context.getClientType());
			assertNull(context.getClientTypeRule());
		}
	}

	/** Defense in depth: even the right token is ignored from anywhere but this machine. */
	@Test
	public void theRightTokenFromANonLoopbackPeerIsIgnored() {
		for (String peer : new String[] { "10.0.0.5", "203.0.113.7", "::ffff:10.0.0.5", "localhost-ish", null, "" })
		{
			RequestContext context = new RequestContext();
			assertFalse("peer " + peer, WarmRequest.resolve(request(WarmRequest.token(), peer, "fill", "bot"), context));
			assertFalse(context.isWarmRequest());
		}
	}

	@Test
	public void aModeOrClientTypeThatIsNotOneIsIgnored() {
		assertFalse(WarmRequest.resolve(request(WarmRequest.token(), "127.0.0.1", "flush", "bot"), new RequestContext()));
		assertFalse(WarmRequest.resolve(request(WarmRequest.token(), "127.0.0.1", "fill", "browser"), new RequestContext()));
		assertFalse(WarmRequest.resolve(request(WarmRequest.token(), "127.0.0.1", null, "bot"), new RequestContext()));
	}

	/** The load-bearing one. */
	@Test
	public void noWarmHeaderEverReachesTheOriginRequest() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		List<String> names = Arrays.asList("Accept", "User-Agent",
				"x-frontcache-warm", "X-Frontcache-Warm-Mode", "x-frontcache-warm-client-type", "X-FRONTCACHE-WARM-RESULT");
		when(request.getHeaderNames()).thenReturn(Collections.enumeration(names));
		for (String name : names)
			when(request.getHeaders(name)).thenReturn(values(name.toLowerCase().startsWith("x-frontcache-warm")
					? WarmRequest.token() : "v"));

		Map<String, List<String>> headers = FCUtils.buildRequestHeaders(request);

		for (String name : headers.keySet())
			assertFalse("forwarded to the origin: " + name, name.toLowerCase().startsWith("x-frontcache-warm"));
		for (List<String> values : headers.values())
			assertFalse("the token was forwarded under another name", values.contains(WarmRequest.token()));
		assertTrue("ordinary headers are still forwarded", headers.containsKey("User-Agent"));
	}

	@Test
	public void theTokenIsSixtyFourHexCharacters() {
		assertTrue(WarmRequest.token().matches("[0-9a-f]{64}"));
	}

	private static Enumeration<String> values(String value) {
		return Collections.enumeration(Collections.singletonList(value));
	}
}
