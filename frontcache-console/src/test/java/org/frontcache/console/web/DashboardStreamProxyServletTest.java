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
package org.frontcache.console.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.frontcache.console.auth.ConsoleEdgeScope;
import org.frontcache.console.auth.ConsolePrincipal;
import org.frontcache.console.auth.ConsoleSecurity;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.service.FrontcacheService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The dashboard stream relay, and specifically its edge allowlist.
 *
 * The Netflix {@code ProxyStreamServlet} this replaced would fetch whatever URL the caller named in
 * {@code origin} <em>and attach the console's API key to it</em>, which made anyone who could load the
 * console page able to point it at an internal host and be handed the shared secret. The replacement
 * only contacts edges the console already knows, and these tests are what pins that.
 */
public class DashboardStreamProxyServletTest {

	private DashboardStreamProxyServlet servlet;

	private FrontcacheService frontcacheService;

	private static Set<String> edges(String... urls) {
		return new LinkedHashSet<String>(Arrays.asList(urls));
	}

	/** The edges the request's scope holds - what the servlet may relay, each with its own key. */
	private volatile EdgeScope scope = EdgeScope.empty("test");

	@Before
	public void setUp() {
		servlet = new DashboardStreamProxyServlet();
		frontcacheService = Mockito.mock(FrontcacheService.class);
		ConsoleEdgeScope edgeScope = new ConsoleEdgeScope() {
			@Override
			public EdgeScope scopeFor(ConsolePrincipal who) {
				return scope;
			}

			@Override
			public EdgeScope everyEdge() {
				return scope;
			}
		};
		ReflectionTestUtils.setField(servlet, "consoleSecurity",
				new ConsoleSecurity(frontcacheService, null, null, edgeScope, null));
	}

	private void scopeHolds(String... urls) {
		scope = EdgeScope.of("test", Arrays.asList(urls), "the-edge-key");
	}

	// ---- the allowlist itself ------------------------------------------------------------------

	@Test
	public void aKnownEdgeResolvesToItsStreamUrl()
	{
		assertEquals("http://edge-1.example.com:8080/fc-dashboard.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/",
						edges("http://edge-1.example.com:8080/")));
	}

	/**
	 * The page hands back the edge URL, but an operator types the edge list into
	 * frontcache-console.conf by hand - so a missing trailing slash must not read as a different host.
	 */
	@Test
	public void trailingSlashesDoNotDecideWhetherAnEdgeIsKnown()
	{
		Set<String> configured = edges("http://edge-1.example.com:8080");

		assertNotNull(DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080", configured));
		assertNotNull(DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/", configured));
		assertNotNull(DashboardStreamProxyServlet.resolveStreamUrl(" http://edge-1.example.com:8080/ ", configured));

		assertEquals("one slash, however it was written",
				"http://edge-1.example.com:8080/fc-dashboard.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080//", configured));
	}

	/**
	 * Older bookmarks and the pre-2.7 page passed the full stream URL. Accept it, on either path, and resolve
	 * the same - the current path, never the legacy one (2.13.0).
	 */
	@Test
	public void theFullStreamUrlAlsoResolves()
	{
		assertEquals("http://edge-1.example.com:8080/fc-dashboard.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/fc-dashboard.stream",
						edges("http://edge-1.example.com:8080/")));
		assertEquals("http://edge-1.example.com:8080/fc-dashboard.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/hystrix.stream",
						edges("http://edge-1.example.com:8080/")));
	}

	@Test
	public void anUnknownHostIsNotResolved()
	{
		Set<String> configured = edges("http://edge-1.example.com:8080/");

		assertNull("a host the console was never told about",
				DashboardStreamProxyServlet.resolveStreamUrl("http://169.254.169.254/latest/meta-data/", configured));
		assertNull("a different port on a known host is a different service",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:9999/", configured));
		assertNull("a prefix match is not a match",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080.evil.test/", configured));
	}

	/**
	 * The path is the servlet's, not the caller's. An allowlist that matched on host alone and then used
	 * the caller's path would be a formality: the same known edge also serves /fc-admin, and the API key
	 * rides along on every proxied request.
	 */
	@Test
	public void aPathOnAKnownEdgeIsNotSmuggledThrough()
	{
		assertNull(DashboardStreamProxyServlet.resolveStreamUrl(
				"http://edge-1.example.com:8080/fc-admin?action=dump-keys",
				edges("http://edge-1.example.com:8080/")));
	}

	@Test
	public void noEdgesMeansNothingResolves()
	{
		assertNull(DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/",
				Collections.<String>emptySet()));
	}

	// ---- what the servlet does with that -------------------------------------------------------

	@Test
	public void aMissingOriginIsABadRequest() throws Exception
	{
		scopeHolds("http://edge-1.example.com:8080/");

		MockHttpServletResponse response = get(null);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
	}

	/**
	 * The one that matters: an unknown origin is refused before anything is opened - the actual defect
	 * being closed - so no edge's key can travel to a host the request's scope does not hold.
	 */
	@Test
	public void anUnknownOriginIsRefusedAndNothingIsOpened() throws Exception
	{
		scopeHolds("http://edge-1.example.com:8080/");

		MockHttpServletRequest request = request("http://169.254.169.254/latest/meta-data/");
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.doGet(request, response);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertFalse("nothing relayed", request.isAsyncStarted());
	}

	/** An edge the console has, but outside this request's scope, is refused exactly as an unknown one (2.12.0). */
	@Test
	public void anEdgeOutsideTheScopeIsRefusedLikeAnUnknownOne() throws Exception
	{
		scopeHolds("http://edge-1.example.com:8080/");

		MockHttpServletResponse unknown = get("http://edge-9.example.com:8080/");
		MockHttpServletResponse inScope = new MockHttpServletResponse();

		assertEquals(HttpServletResponse.SC_FORBIDDEN, unknown.getStatus());
		assertEquals("Unknown edge", unknown.getErrorMessage());
		assertEquals(DashboardStreamProxyServlet.resolveEdge("http://edge-1.example.com:8080/fc-dashboard.stream", scope).apiKey(),
				"the-edge-key");
		assertNull(DashboardStreamProxyServlet.resolveEdge("http://edge-9.example.com:8080/", scope));
		assertEquals(200, inScope.getStatus());
	}

	/** Without a principal - a request the auth filter did not let through - nothing is relayed. */
	@Test
	public void aRequestWithNoPrincipalIsRefused() throws Exception
	{
		scopeHolds("http://edge-1.example.com:8080/");

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fc-dashboard-proxy.stream");
		request.setParameter("origin", "http://edge-1.example.com:8080/");
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.doGet(request, response);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		assertFalse(request.isAsyncStarted());
	}

	/** A request as the auth filter lets it through on a console with authentication off. */
	private static MockHttpServletRequest request(String origin)
	{
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fc-dashboard-proxy.stream");
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.unauthenticated());
		if (null != origin)
			request.setParameter("origin", origin);
		return request;
	}

	private MockHttpServletResponse get(String origin) throws Exception
	{
		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.doGet(request(origin), response);

		return response;
	}

}
