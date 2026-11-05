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
package org.frontcache.console.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.ConsoleAuthFilterAccess;
import org.frontcache.console.web.controllers.LoginControllerAccess;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ModelMap;
import org.springframework.web.server.ResponseStatusException;

import com.typesafe.config.ConfigFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Signing in through a console plugin's identity provider and SSO provider (2.12.0): the filter carries the
 * provider's principal, asks it on every request whether the session goes on, and the login controller owns the
 * sessions around the SSO round trip.
 */
public class ConsoleSignInTest {

	/** A provider with two users and a switch that ends every session - "removed in the IdP". */
	private static final class Users implements ConsoleIdentityProvider {
		volatile boolean revoked = false;
		volatile String role = "operator";
		boolean form = true;
		final List<Channel> channels = new ArrayList<Channel>();

		@Override
		public Optional<ConsolePrincipal> authenticate(String user, String password, Channel channel) {
			channels.add(channel);
			if ("locked".equals(user))
				throw new ConsoleSignInThrottledException(42);
			if (("ann".equals(user) && "pw-ann".equals(password)) || ("ci".equals(user) && "pw-ci".equals(password)))
				return Optional.of(new ConsolePrincipal(user, role, java.util.Map.of()));
			return Optional.empty();
		}

		@Override
		public Optional<ConsolePrincipal> resume(ConsolePrincipal principal, HttpServletRequest request) {
			if (revoked)
				return Optional.empty();
			return Optional.of(new ConsolePrincipal(principal.name(), role, principal.attributes()));
		}

		@Override
		public boolean formLogin() {
			return form;
		}
	}

	/** An SSO provider that keeps the next target in the pre-auth session, and accepts code=good. */
	private static final class Sso implements ConsoleSsoProvider {
		final List<HttpSession> completedWith = new ArrayList<HttpSession>();

		@Override
		public String label() {
			return "Example IdP";
		}

		@Override
		public URI begin(HttpServletRequest request, HttpSession preAuth, String next) {
			preAuth.setAttribute("next", next);
			return URI.create("https://idp.example.com/authorize?state=s1");
		}

		@Override
		public Outcome complete(HttpServletRequest request, HttpSession preAuth) {
			completedWith.add(preAuth);
			if (null == preAuth || !"good".equals(request.getParameter("code")))
				throw new ConsoleSsoException("The sign-in could not be completed - start again.");
			return new Outcome(new ConsolePrincipal("sam", "viewer", java.util.Map.of()), (String) preAuth.getAttribute("next"));
		}
	}

	private Users users;
	private Sso sso;
	private ConsoleSecurity security;

	@Before
	public void setUp() {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		when(service.getConsoleConfig()).thenReturn(ConfigFactory.parseString("users = []\nsso { issuer = x }"));
		when(service.isAuthEnabled()).thenReturn(true);
		users = new Users();
		sso = new Sso();
		security = new ConsoleSecurity(service, users, null, null, sso);
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private MockFilterChain filter(MockHttpServletRequest request) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		ConsoleAuthFilterAccess.filter(security).doFilter(request, new MockHttpServletResponse(), chain);
		return chain;
	}

	// ---- the filter -------------------------------------------------------------------------------

	@Test
	public void basicGoesThroughTheProviderAndCarriesItsPrincipal() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.addHeader("Authorization", basic("ci", "pw-ci"));

		assertNotNull("let through", filter(request).getRequest());
		assertEquals(new ConsolePrincipal("ci", "operator", java.util.Map.of()),
				request.getAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE));
		assertNull("Basic creates no session", request.getSession(false));
	}

	@Test
	public void basicIsAskedAsBasic() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.addHeader("Authorization", basic("ci", "pw-ci"));
		filter(request);
		assertEquals(List.of(ConsoleIdentityProvider.Channel.BASIC), users.channels);
	}

	/** A locked user's script gets 429 and Retry-After - not 401, which reads as a wrong password. */
	@Test
	public void aThrottledBasicRequestIsA429() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.addHeader("Authorization", basic("locked", "pw"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		ConsoleAuthFilterAccess.filter(security).doFilter(request, response, chain);

		assertNull(chain.getRequest());
		assertEquals(429, response.getStatus());
		assertEquals("42", response.getHeader("Retry-After"));
		assertNull(response.getHeader("WWW-Authenticate"));
	}

	@Test
	public void theSingleUsersPasswordIsNotALoginWhenAProviderIsConfigured() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.addHeader("Authorization", basic("admin", "admin"));

		assertNull(filter(request).getRequest());
	}

	@Test
	public void theProviderMayChangeTheSessionsPrincipal() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsolePrincipal.SESSION_ATTRIBUTE, new ConsolePrincipal("ann", "operator", java.util.Map.of()));
		users.role = "viewer";

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.setSession(session);

		assertNotNull(filter(request).getRequest());
		assertEquals("viewer", ((ConsolePrincipal) request.getAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE)).role());
		assertEquals("viewer", ((ConsolePrincipal) session.getAttribute(ConsolePrincipal.SESSION_ATTRIBUTE)).role());
	}

	/** Removed in the identity provider: the session ends on its next request, not at its expiry. */
	@Test
	public void theProviderMayEndTheSession() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsolePrincipal.SESSION_ATTRIBUTE, new ConsolePrincipal("ann", "operator", java.util.Map.of()));
		users.revoked = true;

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.setSession(session);

		assertNull("refused", filter(request).getRequest());
		assertTrue(session.isInvalid());
	}

	/** A session the provider did not create - a name, no principal - is not a sign-in. */
	@Test
	public void aSessionWithoutAPrincipalIsNotSignedIn() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute("fc.user", "admin");

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.setSession(session);

		assertNull(filter(request).getRequest());
	}

	@Test
	public void theTwoSsoPathsAreAnonymousAndNothingUnderThem() throws Exception {
		assertNotNull(filter(new MockHttpServletRequest("GET", "/login/sso")).getRequest());
		assertNotNull(filter(new MockHttpServletRequest("GET", "/login/sso/callback")).getRequest());
		assertNull(filter(new MockHttpServletRequest("GET", "/login/sso/anything")).getRequest());
		assertNull(filter(new MockHttpServletRequest("GET", "/login/sso/callback/../../realtime")).getRequest());
	}

	// ---- the login controller ---------------------------------------------------------------------

	@Test
	public void theLoginPageOffersWhatTheProvidersOffer() {
		ModelMap model = new ModelMap();
		assertEquals("login", LoginControllerAccess.controller(security).loginForm(new MockHttpServletRequest("GET", "/login"), model));
		assertEquals(Boolean.TRUE, model.get("formLogin"));
		assertEquals("Example IdP", model.get("ssoLabel"));

		users.form = false;
		model = new ModelMap();
		LoginControllerAccess.controller(security).loginForm(new MockHttpServletRequest("GET", "/login"), model);
		assertEquals(Boolean.FALSE, model.get("formLogin"));
	}

	@Test
	public void aFormLoginIsRefusedWhenTheProviderHasNoForm() {
		users.form = false;
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("user", "ann");
		request.setParameter("password", "pw-ann");

		assertEquals("login", LoginControllerAccess.controller(security).login(request, new MockHttpServletResponse(), new ModelMap()));
		assertNull(request.getSession(false));
	}

	@Test
	public void aThrottledFormLoginIsThePageAgainWith429AndWhy() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("user", "locked");
		request.setParameter("password", "right-or-wrong");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ModelMap model = new ModelMap();

		assertEquals("login", LoginControllerAccess.controller(security).login(request, response, model));

		assertEquals(429, response.getStatus());
		assertEquals("42", response.getHeader("Retry-After"));
		assertTrue(String.valueOf(model.get("error")), String.valueOf(model.get("error")).contains("42 seconds"));
		assertEquals("locked", model.get("submittedUser"));
		assertNull(request.getSession(false));
		assertEquals(List.of(ConsoleIdentityProvider.Channel.FORM), users.channels);
	}

	@Test
	public void aFormLoginKeepsTheProvidersPrincipal() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("user", "ann");
		request.setParameter("password", "pw-ann");

		assertEquals("redirect:/", LoginControllerAccess.controller(security).login(request, new MockHttpServletResponse(), new ModelMap()));
		assertEquals("ann", ((ConsolePrincipal) request.getSession(false).getAttribute(ConsolePrincipal.SESSION_ATTRIBUTE)).name());
		assertEquals("ann", request.getSession(false).getAttribute("fc.user"));
	}

	@Test
	public void ssoStartsInAShortPreAuthSessionWithNoPrincipal() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/sso");
		request.setParameter("next", "//evil.example.com/");

		String view = LoginControllerAccess.controller(security).startSso(request);

		assertEquals("redirect:https://idp.example.com/authorize?state=s1", view);
		HttpSession preAuth = request.getSession(false);
		assertNotNull(preAuth);
		assertEquals(120, preAuth.getMaxInactiveInterval());
		assertNull(preAuth.getAttribute(ConsolePrincipal.SESSION_ATTRIBUTE));
		assertEquals("next is validated before the provider keeps it", "/", preAuth.getAttribute("next"));
	}

	@Test
	public void aSuccessfulCallbackReplacesThePreAuthSession() {
		MockHttpServletRequest start = new MockHttpServletRequest("GET", "/login/sso");
		start.setParameter("next", "/cache-invalidation");
		LoginControllerAccess.controller(security).startSso(start);
		MockHttpSession preAuth = (MockHttpSession) start.getSession(false);

		MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/sso/callback");
		callback.setSession(preAuth);
		callback.setParameter("code", "good");

		String view = LoginControllerAccess.controller(security).completeSso(callback, new ModelMap());

		assertEquals("redirect:/cache-invalidation", view);
		assertTrue("the pre-auth session is gone", preAuth.isInvalid());
		HttpSession signedIn = callback.getSession(false);
		assertNotNull(signedIn);
		assertEquals("sam", ((ConsolePrincipal) signedIn.getAttribute(ConsolePrincipal.SESSION_ATTRIBUTE)).name());
		assertSame(preAuth, sso.completedWith.get(0));
	}

	@Test
	public void aRefusedCallbackSaysWhyAndLeavesNoSession() {
		MockHttpServletRequest start = new MockHttpServletRequest("GET", "/login/sso");
		LoginControllerAccess.controller(security).startSso(start);
		MockHttpSession preAuth = (MockHttpSession) start.getSession(false);

		MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/sso/callback");
		callback.setSession(preAuth);
		callback.setParameter("code", "forged");
		ModelMap model = new ModelMap();

		assertEquals("login", LoginControllerAccess.controller(security).completeSso(callback, model));
		assertEquals("The sign-in could not be completed - start again.", model.get("error"));
		assertTrue(preAuth.isInvalid());
		assertNull(callback.getSession(false));
	}

	/** A callback that arrives in a signed-in session, not a pre-auth one, is not handed that session. */
	@Test
	public void aCallbackOutsideAPreAuthSessionGetsNone() {
		MockHttpSession signedIn = new MockHttpSession();
		signedIn.setAttribute(ConsolePrincipal.SESSION_ATTRIBUTE, ConsolePrincipal.named("ann"));
		MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/sso/callback");
		callback.setSession(signedIn);
		callback.setParameter("code", "good");

		assertEquals("login", LoginControllerAccess.controller(security).completeSso(callback, new ModelMap()));
		assertNull(sso.completedWith.get(0));
		assertFalse("a forged callback does not sign the operator out", signedIn.isInvalid());
	}

	@Test
	public void withNoSsoProviderBothPathsAre404() {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		when(service.isAuthEnabled()).thenReturn(true);
		ConsoleSecurity noSso = ConsoleSecurity.defaults(service);

		ResponseStatusException start = assertThrows(ResponseStatusException.class,
				() -> LoginControllerAccess.controller(noSso).startSso(new MockHttpServletRequest("GET", "/login/sso")));
		assertEquals(404, start.getStatusCode().value());
		assertThrows(ResponseStatusException.class, () -> LoginControllerAccess.controller(noSso)
				.completeSso(new MockHttpServletRequest("GET", "/login/sso/callback"), new ModelMap()));
	}
}
