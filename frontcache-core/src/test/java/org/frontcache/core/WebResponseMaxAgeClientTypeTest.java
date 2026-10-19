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
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.frontcache.cache.CacheProcessor;
import org.junit.After;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The {@code maxAge="[bot|guest:]30d"} client-type split.
 *
 * The split is the whole point of the prefix: a page worth caching for a crawler is often exactly
 * the page you must not serve a signed-in visitor from cache. A prefix therefore has to mean "this
 * long for that client type, and NOT cacheable for the other one".
 *
 * <h2>The defect this pins</h2>
 *
 * The {@code guest:} branch of {@code setExpireTime} tested {@code REQUEST_CLIENT_TYPE_BOT} - the
 * same constant as the branch above it - so it could never be taken. A {@code guest:} prefix fell
 * through to the no-prefix default and cached for BOTH client types: the exact opposite of what it
 * asked for, and the failure mode the feature exists to prevent. Nothing caught it because every
 * fixture in the repo, unit and e2e, uses {@code bot:}; {@code guestPrefixIsNotCacheableForBots} is
 * the case that had no coverage at all.
 *
 * <h2>The list form</h2>
 *
 * {@code maxAge="bot:30d, guest:1d"} - two live TTLs, one per client type. The four tests above are
 * the compatibility contract for it: the list is not a second syntax bolted on beside the prefixes,
 * it is the general form the prefixes are a one-entry case of, so they must all stay green
 * untouched. The rule the list generalizes is {@code guestPrefixCachesForGuestsOnly}'s: a client
 * type absent from the value is NOT cached.
 */
public class WebResponseMaxAgeClientTypeTest {

	private ListAppender<ILoggingEvent> appender;

	private Logger webResponseLogger;

	@After
	public void tearDown() {
		if (null != appender && null != webResponseLogger)
			webResponseLogger.detachAppender(appender);
	}

	private static Map<String, Long> expireTimes(String maxAge) {
		return new WebResponse("http://example.com/p.html", "body".getBytes(), maxAge, null)
				.getExpireTimeMap();
	}

	/** A future timestamp, i.e. "cacheable, expiring then" - the parser turns 30d into one. */
	private static void assertCacheable(String what, Long expireTime) {
		assertTrue(what + " must be cacheable, was " + expireTime,
				CacheProcessor.CACHE_FOREVER == expireTime || expireTime > System.currentTimeMillis());
	}

	/** The branch that always worked. Kept so a fix to the other one cannot quietly break this. */
	@Test
	public void botPrefixCachesForBotsOnly() {
		Map<String, Long> expireTimes = expireTimes("bot:30d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("a bot: prefix must leave guests uncached",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/**
	 * The defect, from the guest side: this returned a cacheable timestamp for BOTH types, so a
	 * fragment marked guest-only was also served to crawlers from cache.
	 */
	@Test
	public void guestPrefixCachesForGuestsOnly() {
		Map<String, Long> expireTimes = expireTimes("guest:30d");

		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
		assertEquals("a guest: prefix must leave bots uncached",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
	}

	/**
	 * Stated separately because it is the half the bug inverted, and the half that matters: the
	 * point of guest:30d is that a crawler must NOT be served this from cache.
	 */
	@Test
	public void guestPrefixIsNotCacheableForBots() {
		Map<String, Long> expireTimes = expireTimes("guest:30d");

		assertTrue("guest:30d must be dynamic for bots",
				!FCUtils.isWebComponentCacheableForClientType(expireTimes, FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertTrue("guest:30d must be cacheable for guests",
				FCUtils.isWebComponentCacheableForClientType(expireTimes, FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** No prefix is the common case: one TTL, both client types, no warning. */
	@Test
	public void noPrefixCachesForBoth() {
		Map<String, Long> expireTimes = expireTimes("30d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/**
	 * An unrecognized prefix still caches for both - changing that would take pages out of cache on
	 * upgrade - but it no longer does so silently. This is the shape of mistake that hid the defect,
	 * and after the browser -> guest rename it is what an un-updated page will produce.
	 */
	@Test
	public void anUnknownPrefixWarnsAndNamesTheValidOnes() {
		webResponseLogger = (Logger) LoggerFactory.getLogger(WebResponse.class);
		appender = new ListAppender<ILoggingEvent>();
		appender.start();
		webResponseLogger.addAppender(appender);

		Map<String, Long> expireTimes = expireTimes("browser:30d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));

		List<ILoggingEvent> warnings = appender.list.stream()
				.filter(event -> Level.WARN == event.getLevel())
				.toList();

		assertEquals("one warning expected, got " + warnings, 1, warnings.size());
		String message = warnings.get(0).getFormattedMessage();
		assertTrue(message, message.contains("browser"));
		assertTrue(message, message.contains(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}


	// ---------------------------------------------------------------------------------------------
	// The list form: maxage="bot:30d, guest:1d"
	// ---------------------------------------------------------------------------------------------

	/**
	 * The assertion the feature exists for. Two live TTLs at once, which nothing could express
	 * before: naming a prefix forced the other client type to NO_CACHE.
	 */
	@Test
	public void aListGivesEachClientTypeItsOwnLiveTtl() {
		Map<String, Long> expireTimes = expireTimes("bot:30d, guest:1d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));

		assertTrue("the bot TTL must be the longer of the two",
				expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT)
						> expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/**
	 * The general rule, stated on its own so a refactor cannot make the two directions diverge again
	 * - which is exactly how the guest: branch came to be unreachable. A one-entry list is not a
	 * special case of anything: it is the same parse as bot:30d, and must land on the same map.
	 */
	@Test
	public void aClientTypeAbsentFromAListIsNotCached() {
		Map<String, Long> guestOnly = expireTimes("guest:1d");

		assertCacheable("guest", guestOnly.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
		assertEquals("a value naming only guest must leave bots uncached",
				Long.valueOf(CacheProcessor.NO_CACHE), guestOnly.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));

		Map<String, Long> botOnly = expireTimes("bot:1d");

		assertCacheable("bot", botOnly.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("a value naming only bot must leave guests uncached",
				Long.valueOf(CacheProcessor.NO_CACHE), botOnly.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** An explicit zero is the spelling of "and not for the other one" - same map as bot:30d. */
	@Test
	public void anExplicitZeroIsTheSameAsOmittingTheClientType() {
		Map<String, Long> explicit = expireTimes("bot:30d, guest:0");

		assertCacheable("bot", explicit.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("guest:0 must be NO_CACHE",
				Long.valueOf(CacheProcessor.NO_CACHE), explicit.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** forever is a TTL like any other, and must be usable on one side of a split. */
	@Test
	public void foreverIsUsableInsideAList() {
		Map<String, Long> expireTimes = expireTimes("bot:forever, guest:1h");

		assertEquals("bot must be CACHE_FOREVER",
				Long.valueOf(CacheProcessor.CACHE_FOREVER), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** Whitespace around the commas and the colons is the author's, not the parser's. */
	@Test
	public void whitespaceAroundSeparatorsIsIgnored() {
		assertSameSplit(expireTimes("bot:30d,guest:1d"));
		assertSameSplit(expireTimes("bot : 30d , guest : 1d"));
		assertSameSplit(expireTimes("  bot:30d,   guest:1d  "));
	}

	private static void assertSameSplit(Map<String, Long> expireTimes) {
		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertCacheable("guest", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
		assertTrue("bot must still be the longer TTL",
				expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT)
						> expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** First match wins, as in guard-rules.conf and bots.conf - so the intuition transfers. */
	@Test
	public void aDuplicateClientTypeWarnsAndTheFirstWins() {
		captureWarnings();

		Map<String, Long> expireTimes = expireTimes("bot:30d, bot:1h");

		long oneHourish = System.currentTimeMillis() + 2 * 3600 * 1000L;
		assertTrue("the FIRST bot entry (30d) must win, was " + expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT),
				expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT) > oneHourish);

		String message = theOneWarning();
		assertTrue(message, message.contains("Duplicate"));
		assertTrue(message, message.contains(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
	}

	/**
	 * The one case where a list behaves differently from the single value it looks like: an unknown
	 * client type inside a list leaves the type it meant at NO_CACHE rather than caching for both.
	 * In a list the author has unambiguously asked for a split, so "cache for everyone" is the one
	 * outcome they certainly did not want - whereas as a WHOLE value (browser:30d, pinned by
	 * anUnknownPrefixWarnsAndNamesTheValidOnes) it must keep caching for both, or an un-updated
	 * pre-2.9.0 page drops out of cache on upgrade.
	 */
	@Test
	public void anUnknownClientTypeInAListIsIgnoredRatherThanCachedForBoth() {
		captureWarnings();

		Map<String, Long> expireTimes = expireTimes("bot:30d, browser:1d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("an unknown client type in a list must NOT fall through to caching for both",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));

		String message = theOneWarning();
		assertTrue(message, message.contains("browser"));
		assertTrue(message, message.contains(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** An unparseable TTL is NO_CACHE for its own client type only - the rest of the list stands. */
	@Test
	public void anUnparseableTtlOnlyCostsItsOwnClientType() {
		Map<String, Long> expireTimes = expireTimes("bot:30d, guest:banana");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("an unparseable TTL must be NO_CACHE for that client type",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	/** A list entry with no client type at all is a typo, not a bare TTL - warn and ignore it. */
	@Test
	public void aListEntryWithoutAClientTypeIsIgnored() {
		captureWarnings();

		Map<String, Long> expireTimes = expireTimes("bot:30d, 1d");

		assertCacheable("bot", expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_BOT));
		assertEquals("a bare TTL inside a list must not cache anything extra",
				Long.valueOf(CacheProcessor.NO_CACHE), expireTimes.get(FCHeaders.REQUEST_CLIENT_TYPE_GUEST));

		assertTrue(theOneWarning(), theOneWarning().contains("Malformed"));
	}

	private void captureWarnings() {
		webResponseLogger = (Logger) LoggerFactory.getLogger(WebResponse.class);
		appender = new ListAppender<ILoggingEvent>();
		appender.start();
		webResponseLogger.addAppender(appender);
	}

	private String theOneWarning() {
		List<ILoggingEvent> warnings = appender.list.stream()
				.filter(event -> Level.WARN == event.getLevel())
				.toList();

		assertEquals("one warning expected, got " + warnings, 1, warnings.size());
		return warnings.get(0).getFormattedMessage();
	}
}
