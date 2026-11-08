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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.method.HandlerMethod;

import com.typesafe.config.ConfigFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * What the core hands a {@link ConsoleAuditor} (2.12.0): sign-ins, sign-outs, ended sessions, refusals - of a
 * permission and of an edge outside the scope - and every state-changing handler, with what it names and nothing else.
 */
public class ConsoleAuditTest {

	private static final String PASSWORD = "s3cret-Pa55word";
	private static final String CODE = "the-oidc-code-0123";

	/** Records what it is handed. */
	private static final class Recorder implements ConsoleAuditor {
		final List<ConsoleAuditEvent> events = new ArrayList<ConsoleAuditEvent>();

		@Override
		public void record(ConsoleAuditEvent event) {
			events.add(event);
		}

		ConsoleAuditEvent only(String type) {
			List<ConsoleAuditEvent> found = events.stream().filter(e -> type.equals(e.type())).toList();
			assertEquals(events.toString(), 1, found.size());
			return found.get(0);
		}

		boolean none(String type) {
			return events.stream().noneMatch(e -> type.equals(e.type()));
		}
	}

	private static final class Users implements ConsoleIdentityProvider {
		volatile boolean revoked;

		@Override
		public Optional<ConsolePrincipal> authenticate(String user, String password, Channel channel) {
			if ("locked".equals(user))
				throw new ConsoleSignInThrottledException(30);
			return "ann".equals(user) && PASSWORD.equals(password)
					? Optional.of(new ConsolePrincipal("ann", "admin", Map.of()))
					: Optional.empty();
		}

		@Override
		public Optional<ConsolePrincipal> resume(ConsolePrincipal principal, HttpServletRequest request) {
			if (!revoked)
				return Optional.of(principal);
			ConsoleAuditor.note(request, "reason", "the user's entry changed");
			return Optional.empty();
		}
	}

	private static final class Sso implements ConsoleSsoProvider {
		@Override
		public String label() {
			return "IdP";
		}

		@Override
		public URI begin(HttpServletRequest request, HttpSession preAuth, String next) {
			return URI.create("https://idp.example.com/authorize");
		}

		@Override
		public Outcome complete(HttpServletRequest request, HttpSession preAuth) {
			if (!CODE.equals(request.getParameter("code")) || null == preAuth)
				throw new ConsoleSsoException("The sign-in could not be completed - start it again.");
			return new Outcome(new ConsolePrincipal("sam", "viewer", Map.of("sso.sub", "u-1", "sso.sid", "sid-1")), "/");
		}
	}

	/** One edge in every scope. */
	private static final ConsoleEdgeScope ONE_EDGE = new ConsoleEdgeScope() {
		@Override
		public EdgeScope scopeFor(ConsolePrincipal who) {
			return EdgeScope.of("tenant:acme", List.of("https://n1.acme.example/"), "acme-api-key");
		}

		@Override
		public EdgeScope everyEdge() {
			return scopeFor(null);
		}
	};

	private Recorder recorder;
	private Users users;
	private ConsoleSecurity security;

	@Before
	public void setUp() {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		when(service.getConsoleConfig()).thenReturn(ConfigFactory.parseString("users = []\nsso { issuer = x }\ntenants = []"));
		when(service.isAuthEnabled()).thenReturn(true);
		recorder = new Recorder();
		users = new Users();
		security = new ConsoleSecurity(service, users, null, ONE_EDGE, new Sso(), recorder);
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private void filter(MockHttpServletRequest request) throws Exception {
		ConsoleAuthFilterAccess.filter(security).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
	}

	private void assertNoSecretRecorded() {
		String all = recorder.events.toString();
		for (String secret : List.of(PASSWORD, CODE, "acme-api-key", "JSESSIONID", "Authorization"))
			assertFalse(secret + " reached the audit: " + all, all.contains(secret));
	}

	// ---- sign-in ----------------------------------------------------------------------------------------------

	@Test
	public void aFormSignInIsRecordedWithItsPrincipal() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("user", "ann");
		request.setParameter("password", PASSWORD);
		request.setRemoteAddr("203.0.113.7");
		request.addHeader("User-Agent", "curl/8");

		LoginControllerAccess.controller(security).login(request, new MockHttpServletResponse(), new ModelMap());

		ConsoleAuditEvent event = recorder.only("login.success");
		assertEquals("ann", event.principal().name());
		assertEquals(ConsoleAuditEvent.CHANNEL_FORM, event.channel());
		assertEquals("203.0.113.7", event.remoteAddress());
		assertEquals("curl/8", event.userAgent());
		assertEquals("POST /login", event.request());
		assertNoSecretRecorded();
	}

	@Test
	public void aRefusedFormSignInKeepsTheNameAndNeverThePassword() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("user", "ann");
		request.setParameter("password", PASSWORD + "-wrong");

		LoginControllerAccess.controller(security).login(request, new MockHttpServletResponse(), new ModelMap());

		ConsoleAuditEvent event = recorder.only("login.failure");
		assertNull(event.principal());
		assertEquals("ann", event.details().get("user"));
		assertEquals(ConsoleAuditor.REFUSED, event.outcome());
		assertNoSecretRecorded();
	}

	@Test
	public void aThrottledSignInSaysSo() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setParameter("user", "locked");
		request.setParameter("password", PASSWORD);

		LoginControllerAccess.controller(security).login(request, new MockHttpServletResponse(), new ModelMap());

		assertEquals("throttled", recorder.only("login.failure").details().get("reason"));
	}

	@Test
	public void aRefusedBasicCredentialIsRecordedAndNoCredentialIsNot() throws Exception {
		MockHttpServletRequest none = new MockHttpServletRequest("GET", "/cache-view");
		filter(none);
		assertTrue("a request with no credential is not a sign-in attempt", recorder.events.isEmpty());

		MockHttpServletRequest wrong = new MockHttpServletRequest("GET", "/cache-view");
		wrong.addHeader("Authorization", basic("ann", PASSWORD + "-wrong"));
		filter(wrong);

		ConsoleAuditEvent event = recorder.only("login.failure");
		assertEquals(ConsoleAuditEvent.CHANNEL_BASIC, event.channel());
		assertEquals("ann", event.details().get("user"));
		assertNoSecretRecorded();
	}

	/** A script's every request is a Basic sign-in - recorded as the channel of what it does, not as a sign-in. */
	@Test
	public void aBasicSuccessIsTheChannelNotAnEvent() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cache-view");
		request.addHeader("Authorization", basic("ann", PASSWORD));
		filter(request);

		assertTrue(recorder.events.toString(), recorder.events.isEmpty());
		assertEquals(ConsoleAuditEvent.CHANNEL_BASIC, request.getAttribute(ConsoleAuditEvent.CHANNEL_ATTRIBUTE));
	}

	@Test
	public void aSessionTheProviderEndsIsRecordedWithWhy() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsolePrincipal.SESSION_ATTRIBUTE, new ConsolePrincipal("ann", "admin", Map.of()));
		MockHttpServletRequest first = new MockHttpServletRequest("GET", "/");
		first.setSession(session);
		filter(first);
		assertEquals(ConsoleAuditEvent.CHANNEL_SESSION, first.getAttribute(ConsoleAuditEvent.CHANNEL_ATTRIBUTE));
		assertTrue(recorder.events.isEmpty());

		users.revoked = true;
		MockHttpServletRequest second = new MockHttpServletRequest("GET", "/");
		second.setSession(session);
		filter(second);

		ConsoleAuditEvent event = recorder.only("session.ended");
		assertEquals("ann", event.principal().name());
		assertEquals("the user's entry changed", event.details().get("reason"));
	}

	@Test
	public void ssoSignInIsRecordedWithTheProvidersAttributesAndNeverTheCode() {
		MockHttpServletRequest start = new MockHttpServletRequest("GET", "/login/sso");
		LoginControllerAccess.controller(security).startSso(start, new ModelMap());
		MockHttpSession preAuth = (MockHttpSession) start.getSession(false);

		MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/login/sso/callback");
		forged.setParameter("code", "forged");
		LoginControllerAccess.controller(security).completeSso(forged, new ModelMap());
		assertEquals(ConsoleAuditEvent.CHANNEL_SSO, recorder.only("login.failure").channel());

		MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/sso/callback");
		callback.setQueryString("code=" + CODE + "&state=s1");
		callback.setParameter("code", CODE);
		callback.setSession(preAuth);
		LoginControllerAccess.controller(security).completeSso(callback, new ModelMap());

		ConsoleAuditEvent event = recorder.only("login.success");
		assertEquals(ConsoleAuditEvent.CHANNEL_SSO, event.channel());
		assertEquals("sid-1", event.principal().attributes().get("sso.sid"));
		assertEquals("GET /login/sso/callback", event.request());
		assertNoSecretRecorded();
	}

	@Test
	public void aSignOutIsRecordedAndOneWithNoSessionIsNot() {
		MockHttpServletRequest nobody = new MockHttpServletRequest("POST", "/logout");
		LoginControllerAccess.controller(security).logout(nobody);
		assertTrue(recorder.events.isEmpty());

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsolePrincipal.SESSION_ATTRIBUTE, new ConsolePrincipal("ann", "admin", Map.of()));
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
		request.setSession(session);
		LoginControllerAccess.controller(security).logout(request);

		assertEquals("ann", recorder.only("logout").principal().name());
	}

	// ---- handlers ---------------------------------------------------------------------------------------------

	public static class Handlers {
		@ConsolePermit(ConsolePermissions.CACHE_INVALIDATE)
		@ConsoleAudit(value = "cache.invalidate", parameters = "filter")
		public void invalidate() {
		}

		@ConsolePermit(ConsolePermissions.CONFIG_WRITE)
		public void unnamed() {
		}

		public void read() {
		}

		@ConsolePermit(ConsolePermissions.ANONYMOUS)
		public void login() {
		}
	}

	private static HandlerMethod handler(String name) throws Exception {
		return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
	}

	private MockHttpServletRequest signedIn(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setServletPath(path);
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, new ConsolePrincipal("ann", "admin", Map.of()));
		request.setAttribute(ConsoleAuditEvent.CHANNEL_ATTRIBUTE, ConsoleAuditEvent.CHANNEL_SESSION);
		return request;
	}

	/** What a handler's event holds: its type, its permit's edge, the parameters it names - and nothing else sent. */
	@Test
	public void aStateChangeIsRecordedWithWhatItNamesOnly() throws Exception {
		ConsolePermissionInterceptor interceptor = new ConsolePermissionInterceptor(security);
		MockHttpServletRequest request = signedIn("POST", "/cache-invalidation");
		request.setParameter("edge", "https://n1.acme.example/");
		request.setParameter("filter", "/products/");
		request.setParameter("password", PASSWORD);
		request.setParameter("content", "internal.host.example");
		request.setQueryString("token=" + CODE);
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertTrue(interceptor.preHandle(request, response, handler("invalidate")));
		security.scope(request).client("https://n1.acme.example/");
		ConsoleAuditor.note(request, "entries", 12);
		interceptor.afterCompletion(request, response, handler("invalidate"), null);

		ConsoleAuditEvent event = recorder.only("cache.invalidate");
		assertEquals(ConsolePermissions.CACHE_INVALIDATE, event.permission());
		assertEquals("tenant:acme", event.scopeId());
		assertEquals(ConsoleAuditEvent.CHANNEL_SESSION, event.channel());
		assertEquals(Map.of("edge", "https://n1.acme.example/", "filter", "/products/", "entries", 12), event.details());
		assertEquals(ConsoleAuditor.OK, event.outcome());
		assertEquals(200, event.status());
		assertEquals("POST /cache-invalidation", event.request());
		assertNoSecretRecorded();
		assertFalse(event.toString().contains("internal.host.example"));
	}

	@Test
	public void aHandlerWithNoAuditAnnotationIsRecordedAsItsPermission() throws Exception {
		ConsolePermissionInterceptor interceptor = new ConsolePermissionInterceptor(security);
		MockHttpServletRequest request = signedIn("POST", "/save-config");
		MockHttpServletResponse response = new MockHttpServletResponse();
		interceptor.preHandle(request, response, handler("unnamed"));
		interceptor.afterCompletion(request, response, handler("unnamed"), new IllegalStateException("boom"));

		assertEquals(ConsoleAuditor.FAILED, recorder.only(ConsolePermissions.CONFIG_WRITE).outcome());
	}

	@Test
	public void readsAndTheLoginControllerAreNotActions() throws Exception {
		ConsolePermissionInterceptor interceptor = new ConsolePermissionInterceptor(security);
		MockHttpServletRequest get = signedIn("GET", "/cache-view");
		interceptor.preHandle(get, new MockHttpServletResponse(), handler("read"));
		interceptor.afterCompletion(get, new MockHttpServletResponse(), handler("read"), null);

		MockHttpServletRequest login = new MockHttpServletRequest("POST", "/logout");
		interceptor.preHandle(login, new MockHttpServletResponse(), handler("login"));
		interceptor.afterCompletion(login, new MockHttpServletResponse(), handler("login"), null);

		assertTrue(recorder.events.toString(), recorder.events.isEmpty());
	}

	@Test
	public void aRefusedPermissionIsRecorded() throws Exception {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		ConsoleSecurity refusing = new ConsoleSecurity(service, null, (who, p, e) -> false, ONE_EDGE, null, recorder);
		ConsolePermissionInterceptor interceptor = new ConsolePermissionInterceptor(refusing);
		MockHttpServletRequest request = signedIn("POST", "/cache-invalidation");
		request.setParameter("edge", "https://n1.acme.example/");

		assertFalse(interceptor.preHandle(request, new MockHttpServletResponse(), handler("invalidate")));

		ConsoleAuditEvent event = recorder.only("permission.denied");
		assertEquals(ConsolePermissions.CACHE_INVALIDATE, event.permission());
		assertEquals("https://n1.acme.example/", event.details().get("edge"));
		assertEquals(403, event.status());
		assertTrue("no action event for a handler that did not run", recorder.none("cache.invalidate"));
	}

	/** Another tenant's edge, as the scope refuses it: one edge.denied, and the action itself refused. */
	@Test
	public void anEdgeOutsideTheScopeIsRecordedAndTheActionRefused() throws Exception {
		ConsolePermissionInterceptor interceptor = new ConsolePermissionInterceptor(security);
		MockHttpServletRequest request = signedIn("POST", "/cache-invalidation");
		request.setParameter("edge", "https://n1.globex.example/");
		MockHttpServletResponse response = new MockHttpServletResponse();

		interceptor.preHandle(request, response, handler("invalidate"));
		EdgeScope scope = security.scope(request);
		assertNull(scope.client("https://n1.globex.example/"));
		assertNull(scope.client("  "));
		assertTrue("a question is not a refusal", !scope.contains("https://n2.globex.example/"));
		interceptor.afterCompletion(request, response, handler("invalidate"), null);

		ConsoleAuditEvent denied = recorder.only("edge.denied");
		assertEquals("https://n1.globex.example/", denied.details().get("edge"));
		assertEquals("tenant:acme", denied.scopeId());
		assertEquals(ConsoleAuditor.REFUSED, recorder.only("cache.invalidate").outcome());
	}

	@Test
	public void anAuditorThatThrowsNeverFailsTheAction() throws Exception {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		ConsoleSecurity throwing = new ConsoleSecurity(service, null, null, ONE_EDGE, null, event -> {
			throw new IllegalStateException("disk full");
		});
		ConsolePermissionInterceptor interceptor = new ConsolePermissionInterceptor(throwing);
		MockHttpServletRequest request = signedIn("POST", "/cache-invalidation");
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertTrue(interceptor.preHandle(request, response, handler("invalidate")));
		interceptor.afterCompletion(request, response, handler("invalidate"), null);
	}

	@Test
	public void withoutAnAuditorNothingIsCollected() throws Exception {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		ConsoleSecurity quiet = new ConsoleSecurity(service, null, null, ONE_EDGE, null);
		MockHttpServletRequest request = signedIn("POST", "/x");
		quiet.scope(request).client("https://elsewhere.example/");

		assertFalse(quiet.auditing());
		assertTrue(ConsoleSecurity.refusedEdges(request).isEmpty());
	}

	@Test
	public void theOutcomeFollowsTheStatusAndTheEdges() {
		assertEquals(ConsoleAuditor.OK, ConsolePermissionInterceptor.outcome(200, null, false, Map.of()));
		assertEquals(ConsoleAuditor.REFUSED, ConsolePermissionInterceptor.outcome(400, null, false, Map.of()));
		assertEquals(ConsoleAuditor.REFUSED, ConsolePermissionInterceptor.outcome(200, null, true, Map.of()));
		assertEquals(ConsoleAuditor.FAILED, ConsolePermissionInterceptor.outcome(502, null, false, Map.of()));
		assertEquals(ConsoleAuditor.PARTIAL, ConsolePermissionInterceptor.outcome(200, null, false,
				Map.of("a", ConsoleAuditor.OK, "b", ConsoleAuditor.REFUSED)));
		assertEquals(ConsoleAuditor.FAILED, ConsolePermissionInterceptor.outcome(200, null, false,
				Map.of("a", ConsoleAuditor.REFUSED)));
	}

	@Test
	public void aLongHeaderIsShortened() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
		request.addHeader("User-Agent", "x".repeat(5000));
		assertTrue(ConsoleAuditEvent.about(request, "t").build().userAgent().length() <= ConsoleAuditEvent.MAX_VALUE_LENGTH + 3);
	}
}
