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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.frontcache.console.service.ConfiguredEdgeScope;
import org.frontcache.console.service.FrontcacheService;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * {@link ConsoleSecurity}: which SPI guards the console, and the startup refusals that keep it from guarding less
 * than its config asks for.
 */
public class ConsoleSecurityTest {

	private static FrontcacheService service(String consoleConf) {
		FrontcacheService service = Mockito.mock(FrontcacheService.class);
		Config config = null == consoleConf ? null : ConfigFactory.parseString(consoleConf);
		when(service.getConsoleConfig()).thenReturn(config);
		return service;
	}

	private static final ConsoleIdentityProvider IDENTITY = (user, password, channel) -> Optional.empty();

	private static final ConsoleEdgeScope NO_EDGES = new ConsoleEdgeScope() {
		@Override
		public EdgeScope scopeFor(ConsolePrincipal who) {
			return EdgeScope.empty("mine");
		}

		@Override
		public EdgeScope everyEdge() {
			return EdgeScope.empty("all");
		}
	};

	private static final ConsoleSsoProvider SSO = new ConsoleSsoProvider() {
		@Override
		public String label() {
			return "Test IdP";
		}

		@Override
		public java.net.URI begin(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpSession preAuth, String next) {
			return java.net.URI.create("https://idp.example.com/");
		}

		@Override
		public Outcome complete(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpSession preAuth) {
			throw new ConsoleSsoException("no");
		}
	};

	// ---- defaults ---------------------------------------------------------------------------------

	@Test
	public void withNoPluginEveryDefaultIsUsed() {
		ConsoleSecurity security = ConsoleSecurity.defaults(service("urls = []"));

		assertTrue(security.identityProvider() instanceof SingleUserIdentityProvider);
		assertTrue(security.edgeScope() instanceof ConfiguredEdgeScope);
		assertTrue(security.authorizer().permits(ConsolePrincipal.named("admin"), ConsolePermissions.CONFIG_WRITE, null));
		assertFalse(security.sso().isPresent());
	}

	@Test
	public void theBeansOfAPluginAreTaken() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("identity", IDENTITY);
		beans.addBean("scope", NO_EDGES);
		beans.addBean("sso", SSO);

		ConsoleSecurity security = new ConsoleSecurity(service("users = []\nsso { issuer = x }\ntenants = []"), beans);

		assertSame(IDENTITY, security.identityProvider());
		assertSame(NO_EDGES, security.edgeScope());
		assertSame(SSO, security.sso().get());
	}

	@Test
	public void twoBeansOfOneTypeRefuseToStart() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("one", IDENTITY);
		beans.addBean("two", (ConsoleIdentityProvider) (user, password, channel) -> Optional.empty());

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new ConsoleSecurity(service(null), beans));
		assertTrue(e.getMessage().contains("ConsoleIdentityProvider"));
	}

	// ---- fail closed ------------------------------------------------------------------------------

	/** A users block with no plugin must not fall back to user/password: that would re-admit a removed admin. */
	@Test
	public void aUsersBlockWithNoIdentityProviderRefusesToStart() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ConsoleSecurity.defaults(service("user = admin\npassword = s3cret\nusers = [ { name = ann } ]")));
		assertTrue(e.getMessage(), e.getMessage().contains("users"));
	}

	@Test
	public void anSsoBlockNeedsAnSsoProviderAndAnIdentityProvider() {
		assertThrows(IllegalStateException.class, () -> ConsoleSecurity.defaults(service("sso { issuer = x }")));
		assertThrows("SSO alone: who, but not what they may do", IllegalStateException.class,
				() -> new ConsoleSecurity(service("sso { issuer = x }"), null, null, null, SSO));
		assertNotNull(new ConsoleSecurity(service("sso { issuer = x }"), IDENTITY, null, null, SSO));
	}

	/** Every tenant would see every edge. */
	@Test
	public void tenantsWithNoEdgeScopeRefuseToStart() {
		assertThrows(IllegalStateException.class, () -> ConsoleSecurity.defaults(service("tenants = []")));
		assertThrows(IllegalStateException.class, () -> ConsoleSecurity.defaults(service("tenants-file = \"/x\"")));
		assertNotNull(new ConsoleSecurity(service("tenants = []"), null, null, NO_EDGES, null));
	}

	@Test
	public void aConfigWithNoneOfThemStartsAsBefore() {
		assertNotNull(ConsoleSecurity.defaults(service("urls = []\nuser = admin\npassword = s3cret")));
		assertNotNull(ConsoleSecurity.defaults(service(null)));
	}

	// ---- per request ------------------------------------------------------------------------------

	@Test
	public void aRequestWithNoPrincipalHoldsNoPermissionAndNoEdge() {
		ConsoleSecurity security = ConsoleSecurity.defaults(service(null));
		MockHttpServletRequest request = new MockHttpServletRequest();

		assertFalse(security.permits(request, ConsolePermissions.VIEW, null));
		assertTrue("the login page's handlers", security.permits(request, ConsolePermissions.ANONYMOUS, null));
		assertTrue(security.scope(request).isEmpty());
	}

	@Test
	public void theAuthorizerIsAskedWithThePrincipalPermissionAndEdge() {
		List<String> asked = new ArrayList<String>();
		ConsoleAuthorizer viewerOnly = (who, permission, edge) -> {
			asked.add(who.name() + " " + permission + " " + edge);
			return ConsolePermissions.VIEW.equals(permission);
		};
		ConsoleSecurity security = new ConsoleSecurity(service(null), null, viewerOnly, null, null);

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.named("bob"));

		assertTrue(security.permits(request, ConsolePermissions.VIEW, null));
		assertFalse(security.permits(request, ConsolePermissions.CACHE_INVALIDATE, "https://n1/"));
		assertEquals(List.of("bob view null", "bob cache.invalidate https://n1/"), asked);
	}

	/** Authentication off is today's console: every request may do everything, whatever an authorizer says. */
	@Test
	public void anUnauthenticatedConsolePermitsEverything() {
		ConsoleSecurity security = new ConsoleSecurity(service(null), null, (who, permission, edge) -> false, null, null);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.unauthenticated());

		assertTrue(security.permits(request, ConsolePermissions.CONFIG_WRITE, null));
	}

	@Test
	public void theScopeIsTheEdgeScopesAnswerForThePrincipalOncePerRequest() {
		List<String> asked = new ArrayList<String>();
		ConsoleEdgeScope perUser = new ConsoleEdgeScope() {
			@Override
			public EdgeScope scopeFor(ConsolePrincipal who) {
				asked.add(who.name());
				return EdgeScope.of(who.name(), List.of("https://" + who.name() + ".example.com/"), "k");
			}

			@Override
			public EdgeScope everyEdge() {
				return EdgeScope.empty("all");
			}
		};
		ConsoleSecurity security = new ConsoleSecurity(service(null), null, null, perUser, null);

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.named("ann"));

		assertEquals("ann", security.scope(request).id());
		assertTrue(security.scope(request).contains("https://ann.example.com"));
		assertFalse(security.scope(request).contains("https://bob.example.com"));
		assertEquals(List.of("ann"), asked);
	}

	/** Edge editing changes the configured list, so a plugin's scope model switches it off. */
	@Test
	public void edgeEditingNeedsTheConfiguredScope() {
		FrontcacheService service = service(null);
		when(service.isEdgeEditingEnabled()).thenReturn(true);

		assertTrue(ConsoleSecurity.defaults(service).edgeEditing());
		assertFalse(new ConsoleSecurity(service, null, null, NO_EDGES, null).edgeEditing());
	}

	/**
	 * A scope model where a principal sees one of several edge sets at a time (2.12.0): the scope is asked with the
	 * request, the authorizer is asked with that scope, and the navbar's menu comes from the same scope model.
	 */
	@Test
	public void aRequestAwareScopeDecidesTheScopeTheAuthorizerAndTheMenuSee() throws Exception {
		ConsoleEdgeScope byParameter = new ConsoleEdgeScope() {
			@Override
			public EdgeScope scopeFor(ConsolePrincipal who) {
				return EdgeScope.empty("default");
			}

			@Override
			public EdgeScope scopeFor(ConsolePrincipal who, jakarta.servlet.http.HttpServletRequest request) {
				String set = request.getParameter("set");
				return null == set ? scopeFor(who) : EdgeScope.of(set, List.of("https://" + set + ".example.com/"), "k-" + set);
			}

			@Override
			public EdgeScope everyEdge() {
				return EdgeScope.empty("all");
			}

			@Override
			public ScopeMenu menu(ConsolePrincipal who, EdgeScope current, jakarta.servlet.http.HttpServletRequest request) {
				return new ScopeMenu(current.id(), List.of(new ScopeMenu.Entry("a", "realtime?set=a", "a".equals(current.id()), false)));
			}
		};
		List<String> asked = new ArrayList<String>();
		ConsoleAuthorizer perScope = new ConsoleAuthorizer() {
			@Override
			public boolean permits(ConsolePrincipal who, String permission, String edge) {
				throw new AssertionError("the scope-aware method is the one asked");
			}

			@Override
			public boolean permits(ConsolePrincipal who, String permission, String edge, EdgeScope scope) {
				asked.add(permission + " in " + scope.id());
				return "a".equals(scope.id());
			}
		};
		ConsoleSecurity security = new ConsoleSecurity(service(null), null, perScope, byParameter, null);

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setParameter("set", "a");
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.named("ann"));
		assertEquals("a", security.scope(request).id());
		assertTrue(security.permits(request, ConsolePermissions.CACHE_INVALIDATE, null));
		assertEquals(List.of("cache.invalidate in a"), asked);

		// the interceptor sets the menu for the navbar
		new ConsolePermissionInterceptor(security).preHandle(request, new org.springframework.mock.web.MockHttpServletResponse(),
				new org.springframework.web.method.HandlerMethod(this, getClass().getMethod("handlerForTheMenu")));
		ScopeMenu menu = (ScopeMenu) request.getAttribute(ConsoleSecurity.SCOPE_MENU_REQUEST_ATTRIBUTE);
		assertEquals("a", menu.getLabel());
		assertTrue(menu.getEntries().get(0).isCurrent());

		MockHttpServletRequest other = new MockHttpServletRequest();
		other.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.named("ann"));
		assertFalse(security.permits(other, ConsolePermissions.CACHE_INVALIDATE, null));
	}

	/** A GET handler for the interceptor to see - it needs view, which the per-scope authorizer grants in "a". */
	public String handlerForTheMenu() {
		return "x";
	}

	/** With no scope model offering a choice, there is no menu - the navbar renders as before. */
	@Test
	public void theDefaultScopeOffersNoMenu() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE, ConsolePrincipal.named("ann"));
		assertEquals(null, ConsoleSecurity.defaults(service(null)).scopeMenu(request));
		assertEquals("signed out: none", null, ConsoleSecurity.defaults(service(null)).scopeMenu(new MockHttpServletRequest()));
	}
}
