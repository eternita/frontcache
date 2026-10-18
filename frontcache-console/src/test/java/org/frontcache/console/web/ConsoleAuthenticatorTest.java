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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.frontcache.console.service.FrontcacheService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * The one place the console's credential is compared - see docs/archive/console-login-ui.md section 6.3.
 *
 * Both callers are covered here because they are the same comparison in two shapes: the login form
 * (two fields) and {@code Authorization: Basic} (one header). A second copy of a constant-time
 * compare is how one of them silently stops being one.
 */
public class ConsoleAuthenticatorTest {

	private FrontcacheService frontcacheService;

	private ConsoleAuthenticator authenticator;

	@Before
	public void setUp() {
		frontcacheService = Mockito.mock(FrontcacheService.class);
		configured("admin", "s3cret");
		authenticator = new ConsoleAuthenticator(frontcacheService);
	}

	private void configured(String user, String password) {
		when(frontcacheService.isAuthEnabled()).thenReturn(true);
		when(frontcacheService.getUser()).thenReturn(user);
		when(frontcacheService.getPassword()).thenReturn(password);
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder()
				.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	// ---- the form's shape ----------------------------------------------------------------------

	@Test
	public void theConfiguredPairMatches() {
		assertTrue(authenticator.matches("admin", "s3cret"));
	}

	@Test
	public void aWrongHalfDoesNotMatch() {
		assertFalse(authenticator.matches("admin", "wrong"));
		assertFalse(authenticator.matches("root", "s3cret"));
		assertFalse(authenticator.matches("root", "wrong"));
	}

	/** A missing form field is not a credential, and must not throw on the way to "no". */
	@Test
	public void aNullFieldDoesNotMatch() {
		assertFalse(authenticator.matches(null, "s3cret"));
		assertFalse(authenticator.matches("admin", null));
		assertFalse(authenticator.matches(null, null));
	}

	@Test
	public void anEmptyPasswordDoesNotMatchAConfiguredOne() {
		assertFalse(authenticator.matches("admin", ""));
	}

	@Test
	public void aNonAsciiPasswordMatchesOnItsUtf8Bytes() {
		configured("admin", "paßwort—é");

		assertTrue(authenticator.matches("admin", "paßwort—é"));
		assertFalse(authenticator.matches("admin", "passwort-e"));
	}

	/**
	 * An un-credentialed console matches nothing at all. Callers gate on {@code isAuthEnabled()}
	 * first, and this refusing outright is what keeps a console with no password from growing a way
	 * to hold one.
	 */
	@Test
	public void anUnCredentialedConsoleMatchesNothing() {
		when(frontcacheService.isAuthEnabled()).thenReturn(false);
		when(frontcacheService.getUser()).thenReturn(null);
		when(frontcacheService.getPassword()).thenReturn(null);

		assertFalse(authenticator.matches("admin", "s3cret"));
		assertFalse(authenticator.matches(null, null));
		assertFalse(authenticator.matches("", ""));
		assertFalse(authenticator.isAuthorizedBasicHeader(basic("admin", "s3cret")));
	}

	// ---- the header's shape --------------------------------------------------------------------

	@Test
	public void theHeaderIsAcceptedWhateverTheSchemeTokensCase() {
		assertTrue(authenticator.isAuthorizedBasicHeader(basic("admin", "s3cret")));
		assertTrue(authenticator.isAuthorizedBasicHeader(
				"basic " + Base64.getEncoder().encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8))));
	}

	/** Split on the FIRST colon only: a password may contain colons, a username may not. */
	@Test
	public void aPasswordMayContainColons() {
		configured("admin", "a:b:c");

		assertTrue(authenticator.isAuthorizedBasicHeader(basic("admin", "a:b:c")));
	}

	/** A probe must produce "no", not an exception. */
	@Test
	public void aMalformedHeaderIsJustNotAuthorized() {
		String noColon = "Basic "
				+ Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8));

		String[] malformed = { null, "", "Basic", "Basic ", "Bearer abc",
				"Basic !!!not-base64!!!", noColon };

		for (String header : malformed)
			assertFalse("should not authorize: " + header, authenticator.isAuthorizedBasicHeader(header));
	}

}
