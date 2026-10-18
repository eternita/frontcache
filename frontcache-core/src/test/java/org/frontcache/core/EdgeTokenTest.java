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
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link EdgeToken} - how a second-layer node tells its own edge from a client that reached it
 * directly. See docs/direct-access-restriction-proposal.md.
 *
 * What is worth pinning: an unconfigured node never answers "valid" (a guard rule reading
 * {@code !edge-token} must not open up or close down by accident), rotation accepts both values, and
 * the token goes to the origin host and nowhere else - page markup and fallbacks.conf can name any
 * host, and a shared secret must not follow them there.
 */
public class EdgeTokenTest {

	private static final String TOKEN = "e2e-0123456789abcdef0123456789abcdef";
	private static final String OLD_TOKEN = "old-0123456789abcdef0123456789abcdef";
	private static final String ORIGIN = "origin.example.com";

	/** Restored rather than blanked: FCConfig is process-wide, and other tests in this fork use it. */
	private final String originalToken = FCConfig.getProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");
	private final String originalPrevious = FCConfig.getProperty(EdgeToken.PREVIOUS_EDGE_TOKEN_PROPERTY, "");
	private final String originalOriginHost = FCConfig.getProperty("front-cache.origin-host", "");

	@Before
	public void setUp()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, TOKEN);
		FCConfig.setProperty(EdgeToken.PREVIOUS_EDGE_TOKEN_PROPERTY, "");
		FCConfig.setProperty("front-cache.origin-host", ORIGIN);
	}

	@After
	public void tearDown()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, originalToken);
		FCConfig.setProperty(EdgeToken.PREVIOUS_EDGE_TOKEN_PROPERTY, originalPrevious);
		FCConfig.setProperty("front-cache.origin-host", originalOriginHost);
	}

	private static HttpServletRequest presenting(String token)
	{
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN)).thenReturn(token);
		return request;
	}

	// ---- verification ------------------------------------------------------------------------

	@Test
	public void theConfiguredTokenIsValid()
	{
		assertTrue(EdgeToken.isConfigured());
		assertTrue(EdgeToken.isValid(presenting(TOKEN)));
	}

	@Test
	public void surroundingWhitespaceIsNotPartOfTheToken()
	{
		assertTrue(EdgeToken.isValid(presenting("  " + TOKEN + " ")));
	}

	@Test
	public void aWrongOrMissingTokenIsNotValid()
	{
		assertFalse(EdgeToken.isValid(presenting(TOKEN + "x")));
		assertFalse(EdgeToken.isValid(presenting("")));
		assertFalse(EdgeToken.isValid(presenting(null)));
		assertFalse(EdgeToken.isValid((HttpServletRequest) null));
	}

	@Test
	public void theTokenIsCaseSensitive()
	{
		assertFalse("a secret is compared byte for byte", EdgeToken.isValid(presenting(TOKEN.toUpperCase())));
	}

	/**
	 * The one that must not regress. With no token there is nothing to compare against, and a node
	 * that answered "valid" would let every client through an edge-token rule - while one that matched
	 * an empty header against an empty token would do the same.
	 */
	@Test
	public void anUnconfiguredNodeNeverAnswersValid()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");

		assertFalse(EdgeToken.isConfigured());
		assertFalse(EdgeToken.isValid(presenting("")));
		assertFalse(EdgeToken.isValid(presenting(TOKEN)));
	}

	@Test
	public void whitespaceIsNotAToken()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "   ");

		assertFalse(EdgeToken.isConfigured());
	}

	// ---- rotation ----------------------------------------------------------------------------

	@Test
	public void thePreviousTokenIsAcceptedDuringARotation()
	{
		FCConfig.setProperty(EdgeToken.PREVIOUS_EDGE_TOKEN_PROPERTY, OLD_TOKEN);

		assertTrue("the new value", EdgeToken.isValid(presenting(TOKEN)));
		assertTrue("an edge not yet re-configured", EdgeToken.isValid(presenting(OLD_TOKEN)));
		assertFalse(EdgeToken.isValid(presenting("something-else")));
	}

	@Test
	public void thePreviousTokenAloneAcceptsNothing()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");
		FCConfig.setProperty(EdgeToken.PREVIOUS_EDGE_TOKEN_PROPERTY, OLD_TOKEN);

		assertFalse("a previous value is only ever accepted beside a current one",
				EdgeToken.isValid(presenting(OLD_TOKEN)));
	}

	@Test
	public void thePreviousTokenIsNeverSent()
	{
		FCConfig.setProperty(EdgeToken.PREVIOUS_EDGE_TOKEN_PROPERTY, OLD_TOKEN);
		HttpGet request = new HttpGet("http://" + ORIGIN + "/page.htm");

		EdgeToken.stamp(request, ORIGIN);

		assertEquals(TOKEN, request.getFirstHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN).getValue());
	}

	// ---- sending -----------------------------------------------------------------------------

	@Test
	public void theTokenIsSentToTheOrigin()
	{
		HttpGet request = new HttpGet("http://" + ORIGIN + "/page.htm");

		EdgeToken.stamp(request, ORIGIN);

		assertEquals(TOKEN, request.getFirstHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN).getValue());
	}

	@Test
	public void theOriginHostIsMatchedCaseInsensitively()
	{
		HttpGet request = new HttpGet("http://" + ORIGIN + "/page.htm");

		EdgeToken.stamp(request, ORIGIN.toUpperCase());

		assertEquals(TOKEN, request.getFirstHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN).getValue());
	}

	/**
	 * An edge-mode include or a fallbacks.conf init URL can name any host. The secret goes to the
	 * origin and nowhere else.
	 */
	@Test
	public void theTokenIsNeverSentToAnotherHost()
	{
		HttpGet request = new HttpGet("https://third-party.example.net/widget.htm");

		EdgeToken.stamp(request, "third-party.example.net");

		assertNull(request.getFirstHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN));
	}

	@Test
	public void anInboundCopyIsReplacedNotAddedTo()
	{
		HttpGet request = new HttpGet("http://" + ORIGIN + "/page.htm");
		request.addHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, "forged");

		EdgeToken.stamp(request, ORIGIN);

		assertEquals(1, request.getHeaders(FCHeaders.X_FRONTCACHE_EDGE_TOKEN).length);
		assertEquals(TOKEN, request.getFirstHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN).getValue());
	}

	@Test
	public void anUnconfiguredNodeSendsNothing()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");
		HttpGet request = new HttpGet("http://" + ORIGIN + "/page.htm");

		EdgeToken.stamp(request, ORIGIN);

		assertNull(request.getFirstHeader(FCHeaders.X_FRONTCACHE_EDGE_TOKEN));
	}

	// ---- never relayed -----------------------------------------------------------------------

	/**
	 * buildRequestHeaders copies everything it does not blacklist. A client's copy of the token must
	 * stop at the first Frontcache it reaches - the node sends its own, or none.
	 */
	@Test
	public void anInboundTokenIsNotForwarded()
	{
		HttpServletRequest request = mock(HttpServletRequest.class);
		List<String> names = Arrays.asList(FCHeaders.X_FRONTCACHE_EDGE_TOKEN, "User-Agent");
		when(request.getHeaderNames()).thenReturn(Collections.enumeration(names));
		when(request.getHeaders(FCHeaders.X_FRONTCACHE_EDGE_TOKEN)).thenReturn(Collections.enumeration(Arrays.asList(TOKEN)));
		when(request.getHeaders("User-Agent")).thenReturn(Collections.enumeration(Arrays.asList("Chrome/140")));

		Map<String, List<String>> headers = FCUtils.buildRequestHeaders(request);

		assertFalse("the token must not be relayed: " + headers.keySet(),
				headers.containsKey(FCHeaders.X_FRONTCACHE_EDGE_TOKEN));
		assertTrue("everything else still travels", headers.containsKey("User-Agent"));
	}

}
