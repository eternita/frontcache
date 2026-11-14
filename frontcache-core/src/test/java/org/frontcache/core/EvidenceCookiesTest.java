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
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.frontcache.FCConfig;
import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardPredicate;
import org.frontcache.guard.GuardPredicateFactory;
import org.frontcache.plugin.host.ServletFakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The evidence cookie: what {@code cookie-hmac:} verifies and the evidence signer mints. A value is worth something
 * only under its own name, before its expiry, with the node's secret - and, when bound, only for the address or user
 * agent it was bound to.
 */
public class EvidenceCookiesTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef-test";
	static final String OLD_SECRET = "fedcba9876543210fedcba9876543210-old";

	@Before
	public void setUp() {
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, SECRET);
		FCConfig.setProperty(EvidenceCookies.PREVIOUS_SECRET_PROPERTY, "");
	}

	@After
	public void tearDown() {
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
		FCConfig.setProperty(EvidenceCookies.PREVIOUS_SECRET_PROPERTY, "");
	}

	private static long inAnHour() {
		return System.currentTimeMillis() / 1000L + 3600;
	}

	private static boolean holds(String name, String value) {
		return EvidenceCookies.holds(new ServletFakes.Request().cookie(name, value).context(), name);
	}

	@Test
	public void anUnboundValueIsTheOneAnOriginCanWriteWithTheSharedSecret() throws Exception {
		long expiry = inAnHour();
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String byTheOrigin = expiry + "." + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal(("fc_pass|" + expiry).getBytes(StandardCharsets.UTF_8)));

		assertEquals("the documented format, byte for byte", byTheOrigin,
				EvidenceCookies.sign("fc_pass", expiry, null, null, null));
		assertTrue(holds("fc_pass", byTheOrigin));
	}

	@Test
	public void aValueIsWorthNothingUnderAnotherNameAfterItsExpiryOrTampered() {
		String value = EvidenceCookies.sign("fc_pass", inAnHour(), null, null, null);

		assertFalse("another cookie's name", holds("fc_admin", value));
		assertFalse("expired", holds("fc_pass",
				EvidenceCookies.sign("fc_pass", System.currentTimeMillis() / 1000L - 1, null, null, null)));
		String later = (inAnHour() + 86400) + value.substring(value.indexOf('.'));
		assertFalse("expiry moved", holds("fc_pass", later));
		assertFalse("mac changed", holds("fc_pass", value.substring(0, value.length() - 2) + "AA"));
		assertFalse("empty", holds("fc_pass", ""));
		assertFalse("garbage", holds("fc_pass", "abc"));
		assertFalse("no mac", holds("fc_pass", "99999999999."));
		assertFalse("a binding the node does not know", holds("fc_pass",
				value.substring(0, value.indexOf('.')) + ".zz" + value.substring(value.indexOf('.'))));
		assertFalse("too long", holds("fc_pass", value + "x".repeat(300)));
		assertTrue(holds("fc_pass", value));
	}

	@Test
	public void aBoundValueHoldsOnlyForWhatItWasBoundTo() {
		String byIp = EvidenceCookies.sign("fc_pass", inAnHour(), EvidenceCookies.BIND_IP, "203.0.113.7", null);
		assertTrue(EvidenceCookies.holds(new ServletFakes.Request().from("203.0.113.7").cookie("fc_pass", byIp).context(), "fc_pass"));
		assertFalse("another address", EvidenceCookies.holds(
				new ServletFakes.Request().from("198.51.100.9").cookie("fc_pass", byIp).context(), "fc_pass"));

		String byUa = EvidenceCookies.sign("fc_pass", inAnHour(), EvidenceCookies.BIND_UA, null, "Browser/1");
		assertTrue(EvidenceCookies.holds(new ServletFakes.Request().header("User-Agent", "Browser/1")
				.cookie("fc_pass", byUa).context(), "fc_pass"));
		assertFalse("another user agent", EvidenceCookies.holds(new ServletFakes.Request()
				.header("User-Agent", "Scraper/2").cookie("fc_pass", byUa).context(), "fc_pass"));

		String both = EvidenceCookies.sign("fc_pass", inAnHour(), EvidenceCookies.BIND_IP_UA, "203.0.113.7", "Browser/1");
		assertTrue(EvidenceCookies.holds(new ServletFakes.Request().from("203.0.113.7").header("User-Agent", "Browser/1")
				.cookie("fc_pass", both).context(), "fc_pass"));
		assertFalse("the binding cannot be dropped from a value", holds("fc_pass",
				both.substring(0, both.indexOf('.')) + both.substring(both.lastIndexOf('.'))));

		try {
			EvidenceCookies.sign("fc_pass", inAnHour(), EvidenceCookies.BIND_IP, null, null);
			fail("an address binding with no address");
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void thePreviousSecretVerifiesAndIsNeverSignedWith() {
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, OLD_SECRET);
		String old = EvidenceCookies.sign("fc_pass", inAnHour(), null, null, null);

		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, SECRET);
		assertFalse("the old secret is gone", holds("fc_pass", old));

		FCConfig.setProperty(EvidenceCookies.PREVIOUS_SECRET_PROPERTY, OLD_SECRET);
		assertTrue("rotation: the previous one still verifies", holds("fc_pass", old));
		assertTrue(holds("fc_pass", EvidenceCookies.sign("fc_pass", inAnHour(), null, null, null)));
		assertFalse("and is not what signs", old.equals(EvidenceCookies.sign("fc_pass",
				Long.parseLong(old.substring(0, old.indexOf('.'))), null, null, null)));
	}

	@Test
	public void aShortOrMissingSecretIsNoSecret() {
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "too-short");
		assertFalse(EvidenceCookies.isConfigured());
		try {
			EvidenceCookies.sign("fc_pass", inAnHour(), null, null, null);
			fail("nothing is signed without a secret");
		} catch (IllegalStateException expected) {
		}

		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
		assertFalse(EvidenceCookies.isConfigured());
	}

	@Test
	public void thePredicateIsRefusedWithoutASecretAndVerifiesWithOne() {
		GuardPredicate predicate = GuardPredicateFactory.parse("cookie-hmac:fc_pass");
		GuardPredicate negated = GuardPredicateFactory.parse("!cookie-hmac:fc_pass");
		GuardPredicate inBots = GuardPredicateFactory.parse("cookie-hmac:fc_pass", GuardPredicateFactory.Scope.BOTS);
		String value = EvidenceCookies.sign("fc_pass", inAnHour(), null, null, null);

		assertTrue(predicate.test(new ServletFakes.Request().cookie("fc_pass", value).context()));
		assertTrue(inBots.test(new ServletFakes.Request().cookie("fc_pass", value).context()));
		assertFalse("presence is not evidence", predicate.test(new ServletFakes.Request().cookie("fc_pass", "1").context()));
		assertTrue(negated.test(new ServletFakes.Request().context()));
		assertFalse("a probe holds nothing", predicate.test(new org.frontcache.core.RequestContext()));

		for (String refused : new String[] { "cookie-hmac:", "cookie-hmac:bad name", "cookie-hmac:a;b" })
			try {
				GuardPredicateFactory.parse(refused);
				fail(refused);
			} catch (GuardConfigException expected) {
			}

		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
		try {
			GuardPredicateFactory.parse("!cookie-hmac:fc_pass");
			fail("refused without a secret - '!' would match every request");
		} catch (GuardConfigException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(EvidenceCookies.SECRET_PROPERTY));
		}
	}
}
