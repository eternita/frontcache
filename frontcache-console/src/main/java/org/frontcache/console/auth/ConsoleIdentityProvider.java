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

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who may sign in to the console, as of 2.12.0. A console plugin provides one as a bean of its
 * {@code rootComponents()}; without one, the console's single {@code user} / {@code password} is the only login
 * ({@link SingleUserIdentityProvider}). Two are refused at startup ({@link ConsoleSecurity}).
 *
 * <b>Fail closed.</b> A console config with a {@code users} or {@code sso} block and no plugin providing an identity
 * provider does not start - it must not fall back to {@code user} / {@code password}, which would silently re-admit a
 * removed admin.
 */
public interface ConsoleIdentityProvider {

	/**
	 * The login form's and the Basic header's check - the one place a password is compared. Constant-time, and the
	 * same empty answer for a wrong user and a wrong password.
	 *
	 * @return the principal, or empty when the credentials are not a login
	 */
	Optional<ConsolePrincipal> authenticate(String user, String password);

	/**
	 * Called on every request of a signed-in session, before the request is served: the principal to serve it as -
	 * the same one, a changed one (a role re-read from an identity provider) - or empty to end the session, which
	 * the request then meets as signed out. The default keeps the principal for the session's life.
	 */
	default Optional<ConsolePrincipal> resume(ConsolePrincipal principal, HttpServletRequest request) {
		return Optional.of(principal);
	}

	/** Whether the login page offers the user / password form. False for a console that signs in only by SSO. */
	default boolean formLogin() {
		return true;
	}
}
