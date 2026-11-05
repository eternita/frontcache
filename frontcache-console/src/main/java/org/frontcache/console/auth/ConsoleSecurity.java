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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.console.service.ConfiguredEdgeScope;
import org.frontcache.console.service.FrontcacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.typesafe.config.Config;

/**
 * The console's four security SPIs, resolved once, as of 2.12.0: {@link ConsoleIdentityProvider} (who may sign in),
 * {@link ConsoleAuthorizer} (what they may do), {@link ConsoleEdgeScope} (which edges they reach, with which key) and
 * {@link ConsoleSsoProvider} (sign-in elsewhere). A console plugin provides any of them as beans of its
 * {@code rootComponents()} - so {@code ConsolePlugin} needed no new method - and the core takes the one bean of each
 * type, or its own default when there is none.
 *
 * <b>Startup is refused</b>, not warned about, when the answer would be weaker than the config asks for:
 * <ul>
 * <li>two beans of one SPI type - which one guards the console must never depend on bean order;</li>
 * <li>a {@code users} or {@code sso} block with no identity provider to read it - falling back to
 *     {@code user} / {@code password} would silently re-admit a removed admin;</li>
 * <li>an {@code sso} block with no SSO provider;</li>
 * <li>a {@code tenants} or {@code tenants-file} key with no edge scope - every tenant would see every edge.</li>
 * </ul>
 */
@Component
public class ConsoleSecurity {

	private static final Logger logger = LoggerFactory.getLogger(ConsoleSecurity.class);

	/** Where a request's scope is kept once resolved - one resolution per request. */
	static final String SCOPE_REQUEST_ATTRIBUTE = "fc.scope";

	/** frontcache.console keys only a plugin's SPI bean can honour, and the SPI type that must exist for each. */
	static final Map<String, Class<?>> SECURITY_KEYS = Map.of(
			"users", ConsoleIdentityProvider.class,
			"sso", ConsoleSsoProvider.class,
			"tenants", ConsoleEdgeScope.class,
			"tenants-file", ConsoleEdgeScope.class);

	private final FrontcacheService frontcacheService;
	private final ConsoleIdentityProvider identityProvider;
	private final ConsoleAuthorizer authorizer;
	private final ConsoleEdgeScope edgeScope;
	private final ConsoleSsoProvider sso;

	@Autowired
	public ConsoleSecurity(FrontcacheService frontcacheService, ListableBeanFactory beans) {
		this(frontcacheService,
				one(beans, ConsoleIdentityProvider.class),
				one(beans, ConsoleAuthorizer.class),
				one(beans, ConsoleEdgeScope.class),
				one(beans, ConsoleSsoProvider.class));
	}

	/**
	 * @param identityProvider null for the single user's
	 * @param authorizer       null for "every principal may do everything"
	 * @param edgeScope        null for the configured edges
	 * @param sso              null for none
	 */
	public ConsoleSecurity(FrontcacheService frontcacheService, ConsoleIdentityProvider identityProvider,
			ConsoleAuthorizer authorizer, ConsoleEdgeScope edgeScope, ConsoleSsoProvider sso) {

		refuseUnservedKeys(frontcacheService.getConsoleConfig(), identityProvider, edgeScope, sso);

		this.frontcacheService = frontcacheService;
		this.identityProvider = null != identityProvider ? identityProvider : new SingleUserIdentityProvider(frontcacheService);
		this.authorizer = null != authorizer ? authorizer : (who, permission, edge) -> true;
		this.edgeScope = null != edgeScope ? edgeScope : new ConfiguredEdgeScope(frontcacheService);
		this.sso = sso;

		if (null != identityProvider || null != authorizer || null != edgeScope || null != sso)
			logger.info(" -- Console security: identity {}, authorizer {}, edge scope {}, SSO {}",
					name(identityProvider), name(authorizer), name(edgeScope), name(sso));
	}

	/** The single-user console's security - every default. */
	public static ConsoleSecurity defaults(FrontcacheService frontcacheService) {
		return new ConsoleSecurity(frontcacheService, null, null, null, null);
	}

	static void refuseUnservedKeys(Config config, ConsoleIdentityProvider identityProvider, ConsoleEdgeScope edgeScope,
			ConsoleSsoProvider sso) {

		if (null == config)
			return;

		List<String> unserved = new ArrayList<String>();
		for (Map.Entry<String, Class<?>> key : SECURITY_KEYS.entrySet())
		{
			if (!config.hasPath(key.getKey()))
				continue;

			Object provided = ConsoleIdentityProvider.class == key.getValue() ? identityProvider
					: ConsoleSsoProvider.class == key.getValue() ? sso
					: edgeScope;

			// an sso block needs an identity provider too: SSO answers who, the plugin's users/roles what
			if (null == provided || ("sso".equals(key.getKey()) && null == identityProvider))
				unserved.add(key.getKey());
		}

		if (!unserved.isEmpty())
			throw new IllegalStateException("frontcache-console.conf sets " + String.join(", ", unserved.stream().sorted().toList())
					+ ", which only a console plugin can enforce, and no plugin does. The console refuses to start"
					+ " rather than run with less protection than its config asks for.");
	}

	private static <T> T one(ListableBeanFactory beans, Class<T> type) {

		Map<String, T> found = beans.getBeansOfType(type);
		if (found.isEmpty())
			return null;

		if (found.size() > 1)
			throw new IllegalStateException("Two console plugins provide a " + type.getSimpleName() + " ("
					+ String.join(", ", found.keySet()) + ") - the console refuses to start rather than pick one");

		return found.values().iterator().next();
	}

	private static String name(Object spi) {
		return null == spi ? "default" : spi.getClass().getName();
	}

	// ---- the SPIs ---------------------------------------------------------------------------------

	public ConsoleIdentityProvider identityProvider() {
		return identityProvider;
	}

	public ConsoleAuthorizer authorizer() {
		return authorizer;
	}

	public ConsoleEdgeScope edgeScope() {
		return edgeScope;
	}

	public Optional<ConsoleSsoProvider> sso() {
		return Optional.ofNullable(sso);
	}

	/** True when a request must be signed in - a login, a {@code users} block or an {@code sso} block is configured. */
	public boolean authRequired() {
		return frontcacheService.isAuthEnabled();
	}

	/** Whether the Edges screen may add and remove edges: the scope model allows it and {@code edge-editing} is on. */
	public boolean edgeEditing() {
		return edgeScope.edgeEditing() && frontcacheService.isEdgeEditingEnabled();
	}

	// ---- per request ------------------------------------------------------------------------------

	/**
	 * The request's principal, as {@code ConsoleAuthFilter} set it - or null on a request it let through
	 * anonymously (the login page).
	 */
	public static ConsolePrincipal principal(HttpServletRequest request) {
		Object principal = request.getAttribute(ConsolePrincipal.REQUEST_ATTRIBUTE);
		return principal instanceof ConsolePrincipal p ? p : null;
	}

	/**
	 * The edges this request may reach. A request with no principal reaches none - only the login page is served
	 * without one, and it needs no edge.
	 */
	public EdgeScope scope(HttpServletRequest request) {

		Object cached = request.getAttribute(SCOPE_REQUEST_ATTRIBUTE);
		if (cached instanceof EdgeScope scope)
			return scope;

		ConsolePrincipal principal = principal(request);
		EdgeScope scope = null == principal ? EdgeScope.empty("none") : edgeScope.scopeFor(principal, request);
		if (null == scope)
			scope = EdgeScope.empty("none");

		request.setAttribute(SCOPE_REQUEST_ATTRIBUTE, scope);
		return scope;
	}

	/**
	 * What a page may offer, for its JSP: {@code ${fcPermits['cache.invalidate']}} is whether this request's principal
	 * holds that permission on the console as a whole (no edge). Set on every request a handler serves, by
	 * {@link ConsolePermissionInterceptor}. A button a principal may not use is hidden through this <b>and</b> refused
	 * by the interceptor - hiding is for the page, the refusal is the rule. A page rendered without it (an error page)
	 * reads every permission as false.
	 */
	public static final String PERMITS_REQUEST_ATTRIBUTE = "fcPermits";

	/**
	 * The request principal's role, for the navbar - a String, because {@link ConsolePrincipal} is a record and EL
	 * reads JavaBeans getters only ({@code ${principal.role}} fails at render time). Absent when the principal has no
	 * role (the single user). Set beside {@link #PERMITS_REQUEST_ATTRIBUTE}.
	 */
	public static final String ROLE_REQUEST_ATTRIBUTE = "fcRole";

	/** The {@link #PERMITS_REQUEST_ATTRIBUTE} view of this request: each permission asked once, then remembered. */
	public Map<String, Boolean> permitsView(HttpServletRequest request) {
		return new PermitsView(this, request);
	}

	private static final class PermitsView extends AbstractMap<String, Boolean> {

		private final ConsoleSecurity security;
		private final HttpServletRequest request;
		private final Map<String, Boolean> asked = new HashMap<String, Boolean>();

		PermitsView(ConsoleSecurity security, HttpServletRequest request) {
			this.security = security;
			this.request = request;
		}

		@Override
		public Boolean get(Object permission) {
			if (!(permission instanceof String id) || id.isBlank())
				return Boolean.FALSE;
			return asked.computeIfAbsent(id, p -> security.permits(request, p, null));
		}

		@Override
		public boolean containsKey(Object permission) {
			return permission instanceof String;
		}

		@Override
		public Set<Map.Entry<String, Boolean>> entrySet() {
			return Collections.unmodifiableMap(asked).entrySet();
		}
	}

	/** Whether this request's principal holds {@code permission}, on {@code edgeURL} when it names one. */
	public boolean permits(HttpServletRequest request, String permission, String edgeURL) {

		if (ConsolePermissions.ANONYMOUS.equals(permission))
			return true;

		ConsolePrincipal principal = principal(request);
		if (null == principal)
			return false;

		// authentication off: today's console, where every request may do everything
		if (principal.isUnauthenticated())
			return true;

		return authorizer.permits(principal, permission, edgeURL, scope(request));
	}

	/**
	 * The navbar's choice of scopes for this request - see {@link ConsoleEdgeScope#menu} - or null when there is
	 * none to offer. Set by {@link ConsolePermissionInterceptor} as {@link #SCOPE_MENU_REQUEST_ATTRIBUTE}.
	 */
	public ScopeMenu scopeMenu(HttpServletRequest request) {

		ConsolePrincipal principal = principal(request);
		if (null == principal || principal.isUnauthenticated())
			return null;

		try {
			return edgeScope.menu(principal, scope(request), request);
		} catch (RuntimeException e) {
			logger.warn("The edge scope's menu failed - the page renders without it", e);
			return null;
		}
	}

	/** The request attribute the navbar reads {@link #scopeMenu} from - absent when there is no choice to make. */
	public static final String SCOPE_MENU_REQUEST_ATTRIBUTE = "fcScopeMenu";
}
