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
package org.frontcache.console.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.frontcache.console.service.FrontcacheService;

/**
 * The console's single credential, and the ONE place it is compared - see
 * docs/archive/console-login-ui.md section 6.3.
 *
 * Two callers present the same credential in two shapes: {@link ConsoleAuthFilter} reads an
 * {@code Authorization: Basic} header (a script, the container healthcheck, anything automated
 * against a 2.9.0 console), and {@code LoginController} reads two form fields. A second copy of a
 * constant-time comparison is how one of them silently stops being constant-time, so both go
 * through here.
 *
 * Nothing in this class knows about HTTP status codes or sessions - it answers "is this the
 * credential", and the callers decide what to do about the answer.
 */
public final class ConsoleAuthenticator {

	private static final String BASIC_PREFIX = "basic ";

	private final FrontcacheService frontcacheService;

	public ConsoleAuthenticator(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
	}

	/**
	 * True when this is the configured user and password.
	 *
	 * A null candidate is a missing form field, which is not a credential. An un-credentialed
	 * console matches nothing at all: callers gate on {@code isAuthEnabled()} first, and this
	 * refusing outright is what keeps a console with no password from growing a way to hold one.
	 */
	public boolean matches(String user, String password) {

		if (null == user || null == password)
			return false;

		if (!frontcacheService.isAuthEnabled())
			return false;

		// Note the '&' rather than '&&' - short-circuiting on the username would leak whether it
		// was right from the response time.
		return equalsConstantTime(user, frontcacheService.getUser())
				& equalsConstantTime(password, frontcacheService.getPassword());
	}

	/**
	 * A missing, malformed or wrong header is all one answer. Nothing distinguishes "no header"
	 * from "wrong password" to the caller.
	 */
	public boolean isAuthorizedBasicHeader(String header) {

		if (null == header || header.length() <= BASIC_PREFIX.length())
			return false;

		// RFC 7617: the scheme token is case-insensitive.
		if (!BASIC_PREFIX.equalsIgnoreCase(header.substring(0, BASIC_PREFIX.length())))
			return false;

		String decoded;
		try {
			decoded = new String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length()).trim()),
					StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return false; // not base64 - a probe, not a login
		}

		// Split on the FIRST colon only: a password may contain colons, a username may not.
		int separator = decoded.indexOf(':');
		if (separator < 0)
			return false;

		return matches(decoded.substring(0, separator), decoded.substring(separator + 1));
	}

	/**
	 * Constant-time over the bytes, so the number of correct leading characters is not readable
	 * from the response time.
	 */
	private boolean equalsConstantTime(String candidate, String expected) {

		if (null == expected)
			return false;

		return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

}
