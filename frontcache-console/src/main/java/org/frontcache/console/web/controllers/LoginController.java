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

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.ConsoleAuthFilter;
import org.frontcache.console.web.ConsoleAuthenticator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The console's login screen.
 *
 * The credential is unchanged from 2.9.0: one user, no roles, read from conf/frontcache-console.conf,
 * compared in {@link ConsoleAuthenticator}. What this adds is the page, the session that remembers a
 * successful sign-in, and a logout - which Basic authentication could not have, because a browser re-sends
 * a cached credential whatever the server does.
 */
@Controller
public class LoginController {

	/**
	 * One message for wrong password, wrong user, both wrong and an empty submission - the same
	 * reasoning the filter's 401 carries: a missing, malformed or wrong credential is all one
	 * answer.
	 */
	static final String WRONG_CREDENTIAL = "Wrong user or password";

	/** Where a login goes when it has nowhere better - IndexController's own redirect. */
	static final String DEFAULT_TARGET = "/";

	@Autowired
	private FrontcacheService frontcacheService;

	private ConsoleAuthenticator authenticator;

	/** Test seam, and the lazy build the field needs because @Autowired lands after construction. */
	private ConsoleAuthenticator authenticator() {

		if (null == authenticator)
			authenticator = new ConsoleAuthenticator(frontcacheService);

		return authenticator;
	}

	void setFrontcacheService(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
		this.authenticator = null;
	}

	@RequestMapping(value = "/login", method = RequestMethod.GET)
	public String loginForm(HttpServletRequest request, ModelMap model) {

		if (!frontcacheService.isAuthEnabled() || isSignedIn(request))
			return "redirect:" + DEFAULT_TARGET;

		model.put("next", safeTarget(request.getParameter("next")));

		return "login";
	}

	/**
	 * Failure re-renders the page (a forward, status 200) rather than redirecting to
	 * {@code /login?error=1}: an error carried in a query parameter is bookmarkable, linkable and
	 * reflectable, and the Configs screens already render their POST result in place. It is
	 * deliberately not a 401 either - that is what tempts a browser into its native dialog on top
	 * of this page.
	 */
	@RequestMapping(value = "/login", method = RequestMethod.POST)
	public String login(HttpServletRequest request, ModelMap model) {

		if (!frontcacheService.isAuthEnabled())
			return "redirect:" + DEFAULT_TARGET;

		String user = request.getParameter("user");
		String password = request.getParameter("password");
		String target = safeTarget(request.getParameter("next"));

		if (!authenticator().matches(user, password))
		{
			model.put("error", WRONG_CREDENTIAL);
			// Echoed so a mistyped password does not also clear the user name. The password is
			// never echoed.
			model.put("submittedUser", null == user ? "" : user);
			model.put("next", target);

			return "login";
		}

		signIn(request);

		return "redirect:" + target;
	}

	/**
	 * POST, not GET. A {@code GET /logout} is prefetchable and embeddable - {@code <img
	 * src="/logout">} on any page an operator has open would sign them out - and POST puts it
	 * behind {@code SameOriginFilter} like every other state change in the console, which is the
	 * same reasoning that moved add-edge / remove-edge to POST.
	 *
	 * A logout with no session is not an error.
	 */
	@RequestMapping(value = "/logout", method = RequestMethod.POST)
	public String logout(HttpServletRequest request) {

		HttpSession session = request.getSession(false);

		if (null != session)
			session.invalidate();

		return "redirect:" + ConsoleAuthFilter.LOGIN_PATH;
	}

	private boolean isSignedIn(HttpServletRequest request) {

		HttpSession session = request.getSession(false);

		return null != session && null != session.getAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE);
	}

	/**
	 * The one place a session is created.
	 *
	 * The old session is invalidated first: with a single user, session fixation buys an attacker
	 * who can plant a session id in the operator's browser a session shared with the operator
	 * rather than the reverse - but the fix is three lines, and the argument for skipping it is
	 * never worth writing down.
	 *
	 * What goes in is a marker, not the credential. Nothing needs the password afterwards, and the
	 * user name is what the navbar renders.
	 */
	private void signIn(HttpServletRequest request) {

		HttpSession existing = request.getSession(false);
		if (null != existing)
			existing.invalidate();

		request.getSession(true)
				.setAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE, frontcacheService.getUser());
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
