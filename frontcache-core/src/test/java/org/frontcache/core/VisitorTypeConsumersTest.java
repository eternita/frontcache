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

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.bots.BotClassifier;
import org.frontcache.bots.BotRule;
import org.frontcache.bots.VisitorTypes;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardPredicate;
import org.frontcache.guard.GuardPredicateFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What reads a request's visitor type: {@code maxage} and the expireTimeMap it becomes, the cacheability checks the
 * two save sites make, the guard's {@code client-type:}, and the propagation of the leaf to an include - each with the
 * rule that a declared type is its base wherever it is not named.
 */
public class VisitorTypeConsumersTest {

	private static final String PAGE = "http://example.com/p.html";

	@Before
	public void declareTypes() {
		List<BotRule> rules = new ArrayList<BotRule>();
		rules.add(BotClassifierAccess.defaultRule());
		BotClassifier.install(BotClassifier.of(rules, VisitorTypes.of(
				"@type customer like guest", "@type captcha-passed like guest", "@type bot-whitelisted like bot")));
	}

	@After
	public void unload() {
		BotClassifier.install(null);
		ClientIpResolver.install(new ClientIpResolver("", "X-Forwarded-For"));
	}

	// ---- maxage ---------------------------------------------------------------------------

	@Test
	public void aListMayNameADeclaredTypeAndOnlyNamedTypesAreStored() {
		Map<String, Long> map = expireTimes("bot:30d, guest:1h, customer:0");

		assertEquals(3, map.size());
		assertEquals(Long.valueOf(CacheProcessor.NO_CACHE), map.get("customer"));
		assertFalse("bot-whitelisted was not named, so it inherits from bot", map.containsKey("bot-whitelisted"));

		assertTrue(FCUtils.isWebComponentCacheableForClientType(map, "guest", "guest"));
		assertFalse("customer:0 - a customer always goes to the origin",
				FCUtils.isWebComponentCacheableForClientType(map, "customer", "guest"));
		assertTrue("an unnamed type reads its base",
				FCUtils.isWebComponentCacheableForClientType(map, "bot-whitelisted", "bot"));
	}

	@Test
	public void aDeclaredTypeAsTheWholeValueCachesForThatTypeOnly() {
		Map<String, Long> map = expireTimes("bot-whitelisted:30d");

		assertTrue(FCUtils.isWebComponentCacheableForClientType(map, "bot-whitelisted", "bot"));
		assertFalse("both roots stay NO_CACHE", FCUtils.isWebComponentCacheableForClientType(map, "bot", "bot"));
		assertFalse(FCUtils.isWebComponentCacheableForClientType(map, "guest", "guest"));
		assertTrue("an entry only a declared type may read is still subject to cache - else putToCache drops it",
				FCUtils.isWebComponentSubjectToCache(map));
	}

	@Test
	public void anUnknownTypeKeepsItsOldHandling() {
		Map<String, Long> single = expireTimes("partner:30d");
		assertTrue("an unknown prefix as the whole value caches for both roots, as before",
				FCUtils.isWebComponentCacheableForClientType(single, "guest", "guest")
				&& FCUtils.isWebComponentCacheableForClientType(single, "bot", "bot"));

		Map<String, Long> list = expireTimes("bot:30d, partner:1h");
		assertEquals("in a list it is ignored", 2, list.size());
		assertFalse(FCUtils.isWebComponentCacheableForClientType(list, "guest", "guest"));
	}

	@Test
	public void nothingCacheableIsNotSubjectToCache() {
		assertFalse(FCUtils.isWebComponentSubjectToCache(expireTimes("bot:0, customer:0")));
		assertFalse(FCUtils.isWebComponentSubjectToCache(expireTimes(null)));
	}

	/**
	 * The test standing for "no flush": an entry stored before the type existed - only bot / guest in its map - is
	 * served to a customer per guest, and no ERROR is logged (a missing key used to mean "never cached again").
	 */
	@Test
	public void anOldEntryIsServedToADeclaredTypePerItsBaseWithoutAnError() {
		Map<String, Long> oldEntry = new HashMap<String, Long>();
		oldEntry.put("bot", CacheProcessor.CACHE_FOREVER);
		oldEntry.put("guest", System.currentTimeMillis() + 3_600_000L);

		Logger fcUtilsLogger = (Logger) LoggerFactory.getLogger(FCUtils.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
		appender.start();
		fcUtilsLogger.addAppender(appender);
		try {
			assertTrue(FCUtils.isWebComponentCacheableForClientType(oldEntry, "customer", "guest"));
			assertFalse(FCUtils.isWebComponentExpired(oldEntry, "customer", "guest"));
			assertFalse("the two-argument form resolves a declared type through this node's vocabulary",
					FCUtils.isWebComponentExpired(oldEntry, "customer"));
		} finally {
			fcUtilsLogger.detachAppender(appender);
		}
		for (ILoggingEvent event : appender.list)
			assertFalse(event.getFormattedMessage(), Level.ERROR == event.getLevel());
	}

	@Test
	public void aDeclaredTypesOwnStampDecidesItsExpiry() {
		Map<String, Long> map = new HashMap<String, Long>();
		map.put("bot", CacheProcessor.CACHE_FOREVER);
		map.put("guest", System.currentTimeMillis() - 1L);         // expired for guests
		map.put("customer", System.currentTimeMillis() + 60_000L); // still live for customers

		assertTrue(FCUtils.isWebComponentExpired(map, "guest", "guest"));
		assertFalse(FCUtils.isWebComponentExpired(map, "customer", "guest"));
		assertTrue("an unnamed guest-based type expires with guest", FCUtils.isWebComponentExpired(map, "captcha-passed", "guest"));
	}

	// ---- guard client-type: ---------------------------------------------------------------

	@Test
	public void clientTypeSelectsByIsA() {
		GuardPredicate guest = GuardPredicateFactory.parse("client-type:guest");
		GuardPredicate customer = GuardPredicateFactory.parse("client-type:customer");

		assertTrue("every existing rule keeps its population", guest.test(request("customer", "guest")));
		assertTrue(guest.test(request("guest", "guest")));
		assertTrue(customer.test(request("customer", "guest")));
		assertFalse(customer.test(request("guest", "guest")));
		assertFalse(customer.test(request("bot", "bot")));
		assertTrue(GuardPredicateFactory.parse("client-type:bot").test(request("bot-whitelisted", "bot")));
	}

	@Test
	public void anUnknownClientTypeIsRefusedAtLoad() {
		try {
			GuardPredicateFactory.parse("client-type:custmer");
			fail("a name no type has is a rule that never matches - refused instead");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("names no visitor type"));
		}
		try {
			GuardPredicateFactory.parse("client-type:browser");
			fail("browser was renamed in 2.9.0");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("renamed"));
		}
	}

	// ---- propagation ----------------------------------------------------------------------

	@Test
	public void anIncludeFetchCarriesTheLeafBesideTheRoot() throws Exception {
		RequestContext context = new RequestContext();
		context.setVisitorType("customer", "guest");
		context.setClientIP("203.0.113.9");
		context.setIncludeLevel("0");
		context.setRequestId("req-1");

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		headers.put(FCHeaders.X_FRONTCACHE_VISITOR_TYPE, new ArrayList<String>(List.of("captcha-passed"))); // an earlier hop's
		Map<String, List<String>>[] sent = sentHeaders(context, headers);

		assertEquals(List.of("guest"), sent[0].get(FCHeaders.X_FRONTCACHE_CLIENT_TYPE));
		assertEquals("replaced, never appended", List.of("customer"), sent[0].get(FCHeaders.X_FRONTCACHE_VISITOR_TYPE));
	}

	/** The line most likely to be forgotten and the most expensive: a client choosing captcha-passed. */
	@Test
	public void aClientSentVisitorTypeIsNotForwarded() {
		Map<String, String> inbound = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		inbound.put(FCHeaders.X_FRONTCACHE_VISITOR_TYPE, "captcha-passed");
		inbound.put("User-Agent", "Chrome/140");

		Map<String, List<String>> headers = FCUtils.buildRequestHeaders(servletRequest(inbound, "203.0.113.9"));

		assertFalse(headers.keySet().toString(), headers.containsKey(FCHeaders.X_FRONTCACHE_VISITOR_TYPE));
		assertTrue(headers.containsKey("User-Agent"));
	}

	@Test
	public void aTrustedPeersLeafIsAdoptedWhenThisNodeBasesItTheSame() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		assertEquals("customer", propagated("guest", "customer"));
		assertEquals("an older page node sends the root alone", "guest", propagated("guest", null));
	}

	@Test
	public void anUnknownOrMismatchedLeafAdoptsTheRootAlone() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		assertEquals("a type this node does not declare", "guest", propagated("guest", "ai-agent"));
		assertEquals("a type this node bases on the other root", "bot", propagated("bot", "customer"));
	}

	@Test
	public void anUntrustedPeerGetsNeitherValue() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		Map<String, String> inbound = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		inbound.put(FCHeaders.X_FRONTCACHE_CLIENT_TYPE, "guest");
		inbound.put(FCHeaders.X_FRONTCACHE_VISITOR_TYPE, "captcha-passed");
		RequestContext context = new RequestContext();
		context.setRequest(servletRequest(inbound, "203.0.113.9"));
		context.setRequestFromFrontcache();

		assertEquals(null, FCUtils.propagatedClientType(context));
	}

	// ---- helpers --------------------------------------------------------------------------

	private static Map<String, Long> expireTimes(String maxAge) {
		return new WebResponse(PAGE, "body".getBytes(), maxAge, null).getExpireTimeMap();
	}

	private static RequestContext request(String visitorType, String clientType) {
		RequestContext context = new RequestContext();
		context.setVisitorType(visitorType, clientType);
		return context;
	}

	/** the root as propagatedClientType adopts it, then the leaf as propagatedVisitorType does - the engine's order */
	private static String propagated(String root, String leaf) {
		Map<String, String> inbound = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		inbound.put(FCHeaders.X_FRONTCACHE_CLIENT_TYPE, root);
		if (null != leaf)
			inbound.put(FCHeaders.X_FRONTCACHE_VISITOR_TYPE, leaf);

		RequestContext context = new RequestContext();
		context.setRequest(servletRequest(inbound, "10.1.2.3"));
		context.setRequestFromFrontcache();

		String clientType = FCUtils.propagatedClientType(context);
		assertEquals(root, clientType);
		return FCUtils.propagatedVisitorType(context, clientType);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>>[] sentHeaders(RequestContext context, Map<String, List<String>> headers) throws Exception {
		final Map<String, List<String>>[] sent = new Map[1];
		FcHttpClient client = new FcHttpClient() {
			@Override
			public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext ctx) {
				sent[0] = requestHeaders;
				return new WebResponse(urlStr);
			}

			@Override
			public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext ctx) {
				sent[0] = requestHeaders;
				return new WebResponse(urlStr);
			}

			@Override
			public WebResponse fetch(String urlStr) {
				return new WebResponse(urlStr);
			}
		};
		FCUtils.includeDynamicCallHttpClient("http://origin/inc.jsp", headers, client, context);
		return sent;
	}

	private static HttpServletRequest servletRequest(final Map<String, String> headers, final String remoteAddr) {
		return (HttpServletRequest) Proxy.newProxyInstance(VisitorTypeConsumersTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class }, (proxy, method, args) -> {
					switch (method.getName())
					{
					case "getHeader":
						return headers.get((String) args[0]);
					case "getHeaderNames":
						return Collections.enumeration(new ArrayList<String>(headers.keySet()));
					case "getHeaders":
					{
						String value = headers.get((String) args[0]);
						return Collections.enumeration((null == value) ? List.<String>of() : List.of(value));
					}
					case "getRemoteAddr":
						return remoteAddr;
					case "getMethod":
						return "GET";
					default:
						return null;
					}
				});
	}

	/** BotClassifier's built-ins are package-private; the default rule is all this vocabulary needs. */
	private static final class BotClassifierAccess {
		static BotRule defaultRule() {
			return new BotRule("default", List.of(new GuardPredicate() {
				@Override
				public boolean test(RequestContext context) {
					return true;
				}

				@Override
				public String describe() {
					return "(always)";
				}
			}), FCHeaders.REQUEST_CLIENT_TYPE_GUEST, false);
		}
	}
}
