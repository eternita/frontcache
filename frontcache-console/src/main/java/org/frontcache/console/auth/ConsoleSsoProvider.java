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

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Sign-in through an external identity provider, as of 2.12.0 - served by the core's {@code LoginController} at two
 * fixed, exact anonymous paths, {@code /login/sso} and {@code /login/sso/callback}, which answer 404 when no console
 * plugin provides one. <b>A plugin cannot add an anonymous path</b>: the core names these two and owns the sessions
 * around them; the provider only fills in the protocol.
 *
 * The flow: {@code GET /login/sso} creates a short-lived PRE-AUTH session (no principal) and asks {@link #begin} where
 * to send the browser; the provider keeps what it needs to check the answer (state, nonce, a PKCE verifier, the
 * {@code next} it was given) in that session. {@code GET /login/sso/callback} asks {@link #complete} who came back,
 * then invalidates the pre-auth session whatever the outcome and, on success, creates the real one.
 */
public interface ConsoleSsoProvider {

	/** The login page's button: "Sign in with {label}". */
	String label();

	/**
	 * @param preAuth the new pre-auth session, to keep the flow's state in
	 * @param next    where to go after sign-in - already validated as a relative console path
	 * @return where to send the browser
	 */
	URI begin(HttpServletRequest request, HttpSession preAuth, String next);

	/**
	 * @param preAuth the pre-auth session {@link #begin} wrote, or null when the callback arrived without one
	 * @return who signed in, and where to go
	 * @throws ConsoleSsoException when the answer is refused - the message is shown on the login page, so it says
	 *         what to do and nothing an attacker can use
	 */
	Outcome complete(HttpServletRequest request, HttpSession preAuth);

	/** A successful sign-in: the principal and the {@code next} {@link #begin} was given. */
	record Outcome(ConsolePrincipal principal, String next) {
	}

	/** A refused callback, shown on the login page. */
	class ConsoleSsoException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public ConsoleSsoException(String message) {
			super(message);
		}

		public ConsoleSsoException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
