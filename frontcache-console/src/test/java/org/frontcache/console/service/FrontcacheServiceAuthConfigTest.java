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
package org.frontcache.console.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.frontcache.console.service.FrontcacheService.Credentials;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * How conf/frontcache-console.conf turns into a login.
 *
 * The first test is the one that matters on an upgrade: a file written before this feature existed
 * has neither key, and must parse to "no authentication" rather than to an exception. Reading these
 * keys the way {@code apiKey} is read - a bare {@code getString} - is what would break it, and is
 * exactly what a config still saying {@code siteKey} does after the 2.9.0 api-key rename.
 */
public class FrontcacheServiceAuthConfigTest {

	private static Config config(String hocon) {
		return ConfigFactory.parseString(hocon);
	}

	private static final String PRE_UPGRADE_FILE =
			"urls = [\"https://fc-edge.example.com:443/\"]\n"
			+ "apiKey = some-api-key\n";

	// ---- the upgrade path ----------------------------------------------------------------------

	@Test
	public void aConfigWrittenBeforeThisFeatureParsesToNoAuthentication() {
		assertNull(FrontcacheService.parseCredentials(config(PRE_UPGRADE_FILE)));
	}

	@Test
	public void anEmptyConfigParsesToNoAuthentication() {
		assertNull(FrontcacheService.parseCredentials(config("")));
	}

	// ---- configured ----------------------------------------------------------------------------

	@Test
	public void bothKeysGiveACredential() {
		Credentials credentials = FrontcacheService.parseCredentials(
				config(PRE_UPGRADE_FILE + "user = admin\npassword = s3cret\n"));

		assertEquals("admin", credentials.user());
		assertEquals("s3cret", credentials.password());
	}

	/** A password is taken verbatim - only the username is trimmed. */
	@Test
	public void theUsernameIsTrimmedAndThePasswordIsNot() {
		Credentials credentials = FrontcacheService.parseCredentials(
				config("user = \"  admin  \"\npassword = \" s3cret \"\n"));

		assertEquals("admin", credentials.user());
		assertEquals(" s3cret ", credentials.password());
	}

	@Test
	public void aPasswordMayContainColonsAndSpaces() {
		Credentials credentials = FrontcacheService.parseCredentials(
				config("user = admin\npassword = \"a:b c:d\"\n"));

		assertEquals("a:b c:d", credentials.password());
	}

	// ---- typos are not states ------------------------------------------------------------------

	/**
	 * Half-configured refuses to start. It cannot arise from an un-migrated file - only from a typo -
	 * and resolving a typo to "unprotected" is the worst of the available answers.
	 */
	@Test
	public void userWithoutPasswordRefuses() {
		try {
			FrontcacheService.parseCredentials(config("user = admin\n"));
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("'password'"));
		}
	}

	@Test
	public void passwordWithoutUserRefuses() {
		try {
			FrontcacheService.parseCredentials(config("password = s3cret\n"));
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("'user'"));
		}
	}

	/** "" must never become a credential: it would authenticate an empty password. */
	@Test
	public void anEmptyUserRefuses() {
		try {
			FrontcacheService.parseCredentials(config("user = \"\"\npassword = s3cret\n"));
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("must not"));
		}
	}

	@Test
	public void anEmptyPasswordRefuses() {
		try {
			FrontcacheService.parseCredentials(config("user = admin\npassword = \"\"\n"));
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("must not"));
		}
	}

	/** A username of nothing but spaces is the same typo as an empty one. */
	@Test
	public void aBlankUserRefuses() {
		try {
			FrontcacheService.parseCredentials(config("user = \"   \"\npassword = s3cret\n"));
			fail("expected IllegalStateException");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("must not"));
		}
	}

	// ---- the session cookie's Secure attribute -------------------------------------------------

	/**
	 * Default OFF, and that is the decision rather than an oversight. Defaulting it on means the cookie is
	 * never sent on the plain-HTTP internal consoles that are the common case, and the symptom is a login
	 * page that accepts the password and returns to the login page.
	 */
	@Test
	public void theSessionCookieIsNotSecureUnlessTheFileSaysSo() {
		assertFalse(FrontcacheService.sessionCookieSecure(config(PRE_UPGRADE_FILE)));
		assertFalse(FrontcacheService.sessionCookieSecure(config("")));
		assertFalse(FrontcacheService.sessionCookieSecure(null));
	}

	@Test
	public void theSessionCookieIsSecureWhenTheFileSaysSo() {
		assertTrue(FrontcacheService.sessionCookieSecure(
				config(PRE_UPGRADE_FILE + "session-cookie-secure = true\n")));

		assertFalse(FrontcacheService.sessionCookieSecure(
				config(PRE_UPGRADE_FILE + "session-cookie-secure = false\n")));
	}

}
