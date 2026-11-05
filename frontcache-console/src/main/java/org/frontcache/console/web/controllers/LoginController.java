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
package org.frontcache.console.web.controllers;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import org.frontcache.console.auth.ConsoleIdentityProvider;
import org.frontcache.console.auth.ConsolePermissions;
import org.frontcache.console.auth.ConsolePermit;
import org.frontcache.console.auth.ConsolePrincipal;
import org.frontcache.console.auth.ConsoleSecurity;
import org.frontcache.console.auth.ConsoleSignInThrottledException;
import org.frontcache.console.auth.ConsoleSsoProvider;
import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.ConsoleAuthFilter;
import org.frontcache.console.web.ConsoleAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * The console's login screen.
 *
 * The credential is the identity provider's (as of 2.12.0, {@code ConsoleSecurity}): with no console plugin, one
 * user, no roles, read from conf/frontcache-console.conf and compared in {@link ConsoleAuthenticator}, as since
 * 2.9.0. What this adds is the page, the session that remembers a successful sign-in, and a logout - which Basic
 * authentication could not have, because a browser re-sends a cached credential whatever the server does - and,
 * when a plugin provides one, sign-in through an identity provider at {@code /login/sso}.
 */
@Controller
public class LoginController {

	private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

	/**
	 * One message for wrong password, wrong user, both wrong and an empty submission - the same
	 * reasoning the filter's 401 carries: a missing, malformed or wrong credential is all one
	 * answer.
	 */
	static final String WRONG_CREDENTIAL = "Wrong user or password";

	/** Where a login goes when it has nowhere better - IndexController's own redirect. */
	static final String DEFAULT_TARGET = "/";

	/** How long a pre-auth session lives: the round trip to the identity provider and back. */
	static final int PRE_AUTH_SECONDS = 120;

	/** Marks the pre-auth session, so a callback cannot complete a sign-in in some other session. */
	static final String PRE_AUTH_ATTRIBUTE = "fc.sso.pre-auth";

	@Autowired
	private ConsoleSecurity security;

	/** Test seam: the single-user console's security. */
	void setFrontcacheService(FrontcacheService frontcacheService) {
		this.security = ConsoleSecurity.defaults(frontcacheService);
	}

	/** Test seam: any security. */
	void setConsoleSecurity(ConsoleSecurity security) {
		this.security = security;
	}

	@ConsolePermit(ConsolePermissions.ANONYMOUS)
	@RequestMapping(value = "/login", method = RequestMethod.GET)
	public String loginForm(HttpServletRequest request, ModelMap model) {

		if (!security.authRequired() || isSignedIn(request))
			return "redirect:" + DEFAULT_TARGET;

		model.put("next", safeTarget(request.getParameter("next")));
		putSignInOptions(model);

		return "login";
	}

	/**
	 * Failure re-renders the page (a forward, status 200) rather than redirecting to
	 * {@code /login?error=1}: an error carried in a query parameter is bookmarkable, linkable and
	 * reflectable, and the Configs screens already render their POST result in place. It is
	 * deliberately not a 401 either - that is what tempts a browser into its native dialog on top
	 * of this page.
	 */
	@ConsolePermit(ConsolePermissions.ANONYMOUS)
	@RequestMapping(value = "/login", method = RequestMethod.POST)
	public String login(HttpServletRequest request, HttpServletResponse response, ModelMap model) {

		if (!security.authRequired())
			return "redirect:" + DEFAULT_TARGET;

		String user = request.getParameter("user");
		String password = request.getParameter("password");
		String target = safeTarget(request.getParameter("next"));

		Optional<ConsolePrincipal> principal;
		String error = WRONG_CREDENTIAL;
		try {
			principal = security.identityProvider().formLogin()
					? security.identityProvider().authenticate(user, password, ConsoleIdentityProvider.Channel.FORM)
					: Optional.empty();
		} catch (ConsoleSignInThrottledException throttled) {
			// the page again, with 429 and why - never "wrong password" for what may be the right one
			principal = Optional.empty();
			error = throttled.getMessage();
			response.setStatus(429);
			response.setHeader("Retry-After", Long.toString(throttled.retryAfterSeconds()));
		}

		if (principal.isEmpty())
		{
			model.put("error", error);
			// Echoed so a mistyped password does not also clear the user name. The password is
			// never echoed.
			model.put("submittedUser", null == user ? "" : user);
			model.put("next", target);
			putSignInOptions(model);

			return "login";
		}

		signIn(request, principal.get());

		return "redirect:" + target;
	}

	/**
	 * Starts SSO: a fresh PRE-AUTH session - two minutes, no principal, so the filter never takes it for a signed-in
	 * one - in which the provider keeps the flow's state, then the identity provider. 404 without a provider: the
	 * path is anonymous (a fixed entry in the filter's set), and must not be a page that does nothing.
	 */
	@ConsolePermit(ConsolePermissions.ANONYMOUS)
	@RequestMapping(value = "/login/sso", method = RequestMethod.GET)
	public String startSso(HttpServletRequest request) {

		ConsoleSsoProvider sso = security.sso().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

		if (!security.authRequired() || isSignedIn(request))
			return "redirect:" + DEFAULT_TARGET;

		HttpSession existing = request.getSession(false);
		if (null != existing)
			existing.invalidate();

		HttpSession preAuth = request.getSession(true);
		preAuth.setMaxInactiveInterval(PRE_AUTH_SECONDS);
		preAuth.setAttribute(PRE_AUTH_ATTRIBUTE, Boolean.TRUE);

		URI identityProvider = sso.begin(request, preAuth, safeTarget(request.getParameter("next")));

		return "redirect:" + identityProvider.toString();
	}

	/**
	 * Where the identity provider sends the browser back. A GET from another origin, so {@code SameOriginFilter} does
	 * not cover it - the provider's {@code state} check does. The pre-auth session is invalidated whatever the
	 * outcome; on success the real session is created; on refusal the login page says why, with no session.
	 */
	@ConsolePermit(ConsolePermissions.ANONYMOUS)
	@RequestMapping(value = "/login/sso/callback", method = RequestMethod.GET)
	public String completeSso(HttpServletRequest request, ModelMap model) {

		ConsoleSsoProvider sso = security.sso().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

		HttpSession session = request.getSession(false);
		HttpSession preAuth = null != session && Boolean.TRUE.equals(attribute(session, PRE_AUTH_ATTRIBUTE)) ? session : null;

		ConsoleSsoProvider.Outcome outcome;
		try {
			outcome = sso.complete(request, preAuth);
		} catch (ConsoleSsoProvider.ConsoleSsoException refused) {
			// Only the pre-auth session ends (finally): a forged callback arriving in a signed-in browser must
			// not be a way to sign its operator out.
			logger.warn("SSO sign-in refused: {}", refused.getMessage());
			model.put("error", refused.getMessage());
			model.put("next", DEFAULT_TARGET);
			putSignInOptions(model);
			return "login";
		} finally {
			invalidate(preAuth);
		}

		signIn(request, outcome.principal());

		return "redirect:" + safeTarget(outcome.next());
	}

	/**
	 * POST, not GET. A {@code GET /logout} is prefetchable and embeddable - {@code <img
	 * src="/logout">} on any page an operator has open would sign them out - and POST puts it
	 * behind {@code SameOriginFilter} like every other state change in the console, which is the
	 * same reasoning that moved add-edge / remove-edge to POST.
	 *
	 * A logout with no session is not an error.
	 */
	@ConsolePermit(ConsolePermissions.ANONYMOUS)
	@RequestMapping(value = "/logout", method = RequestMethod.POST)
	public String logout(HttpServletRequest request) {

		invalidate(request.getSession(false));

		return "redirect:" + ConsoleAuthFilter.LOGIN_PATH;
	}

	/** What the login page offers: the form, and the SSO button. */
	private void putSignInOptions(ModelMap model) {
		model.put("formLogin", security.identityProvider().formLogin());
		security.sso().ifPresent(sso -> model.put("ssoLabel", sso.label()));
	}

	private boolean isSignedIn(HttpServletRequest request) {

		HttpSession session = request.getSession(false);

		return null != session && attribute(session, ConsolePrincipal.SESSION_ATTRIBUTE) instanceof ConsolePrincipal;
	}

	/**
	 * Where a signed-in session is created: here, after a form login or an SSO callback.
	 *
	 * The old session is invalidated first: session fixation would otherwise hand an attacker who can plant a
	 * session id in an operator's browser a signed-in session - the fix is three lines, and the argument for
	 * skipping it is never worth writing down.
	 *
	 * What goes in is the principal and the name the navbar renders - never the credential.
	 */
	private void signIn(HttpServletRequest request, ConsolePrincipal principal) {

		invalidate(request.getSession(false));

		HttpSession session = request.getSession(true);
		session.setAttribute(ConsolePrincipal.SESSION_ATTRIBUTE, principal);
		session.setAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE, principal.name());
	}

	private static Object attribute(HttpSession session, String name) {
		try {
			return session.getAttribute(name);
		} catch (IllegalStateException invalidated) {
			return null;
		}
	}

	private static void invalidate(HttpSession session) {
		if (null == session)
			return;
		try {
			session.invalidate();
		} catch (IllegalStateException alreadyInvalid) {
			// the same outcome
		}
	}

	/**
	 * Validates where a login is allowed to send the browser.
	 *
	 * {@code next} reaches here from a query parameter and from a form field, so it is caller
	 * input, and handing it to a redirect unchecked is an open redirect on a host an operator
	 * trusts. The check is an <b>allowlist of shapes</b> rather than a denylist of tricks, and
	 * anything that does not fit falls back on the front page:
	 *
	 * <ul>
	 * <li>{@code //evil.com} is protocol-relative - an absolute URL to a browser, despite the
	 *     leading slash;</li>
	 * <li>{@code /\evil.com} is the same thing on a browser that normalizes the backslash;</li>
	 * <li>{@code https:/evil.com} parses as absolute;</li>
	 * <li>CR/LF would split the {@code Location} header;</li>
	 * <li>braces would be expanded as a URI template by Spring's own RedirectView;</li>
	 * <li>{@code /login} would bounce a successful sign-in straight back to the form.</li>
	 * </ul>
	 */
	static String safeTarget(String next) {

		if (null == next || next.isEmpty() || '/' != next.charAt(0))
			return DEFAULT_TARGET;

		if (next.startsWith("//") || next.startsWith("/\\"))
			return DEFAULT_TARGET;

		if (next.startsWith(ConsoleAuthFilter.LOGIN_PATH))
			return DEFAULT_TARGET;

		for (int i = 0; i < next.length(); i++)
		{
			char c = next.charAt(i);
			if (c < 0x20 || '{' == c || '}' == c || '\\' == c)
				return DEFAULT_TARGET;
		}

		try {
			URI uri = new URI(next);
			if (uri.isAbsolute() || null != uri.getHost() || null != uri.getAuthority())
				return DEFAULT_TARGET;
		} catch (URISyntaxException e) {
			return DEFAULT_TARGET;
		}

		return next;
	}

}
