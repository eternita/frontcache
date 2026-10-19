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
package org.frontcache.guard.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The rate predicate against whole requests: what it counts, and - mostly - what it must NOT count.
 *
 * This is the one guard predicate with a side effect, and every one of the cases below is silent
 * when it is wrong: a probe or an include quietly spending a real visitor's budget looks exactly
 * like normal operation until someone is refused for no reason.
 */
public class RateLimitPredicateTest {

	private final GuardRuleParser parser = new GuardRuleParser(Collections.<String>emptySet());

	@Before
	public void setUp() {
		GuardRateLimiterRegistry.reset();
		// no trusted proxies: the socket peer is the key, which is what the stand-in request supplies
		ClientIpResolver.install(new ClientIpResolver("", "X-Forwarded-For"));
	}

	@After
	public void tearDown() {
		GuardRateLimiterRegistry.reset();
		ClientIpResolver.reload();
	}

	// ---- what it counts -------------------------------------------------------------------------

	@Test
	public void aClientOverTheLimitMatchesAndCarriesRetryAfter() {
		GuardRule rule = parser.parse("ip-flood | rate:3/1m | reject:429 Too Many Requests");

		for (int i = 0; i < 3; i++)
			assertFalse("request " + (i + 1) + " is within the limit", rule.matches(toplevel("203.0.113.7")));

		RequestContext overLimit = toplevel("203.0.113.7");
		assertTrue(rule.matches(overLimit));
		assertTrue("Retry-After must be set for the 429", 0 < overLimit.getGuardRetryAfterSeconds());
		assertTrue("and must not exceed the window", 60 >= overLimit.getGuardRetryAfterSeconds());
	}

	@Test
	public void eachClientHasItsOwnBudget() {
		GuardRule rule = parser.parse("ip-flood | rate:2/1m | reject:429");

		rule.matches(toplevel("203.0.113.7"));
		rule.matches(toplevel("203.0.113.7"));
		assertTrue(rule.matches(toplevel("203.0.113.7")));

		assertFalse("a different client is untouched", rule.matches(toplevel("198.51.100.9")));
	}

	@Test
	public void aDryRunRuleStillCounts() {
		// dry-run is how a limit is sized before it enforces anything; one that did not count would
		// measure nothing at all
		GuardRule rule = parser.parse("shadow | rate:2/1m | reject:429 | dry-run");

		assertTrue(rule.isDryRun());
		rule.matches(toplevel("203.0.113.7"));
		rule.matches(toplevel("203.0.113.7"));

		assertTrue("the third request is over the limit even in dry-run", rule.matches(toplevel("203.0.113.7")));
	}

	// ---- what it must not count ------------------------------------------------------------------

	@Test
	public void aLoadTimeProbeCountsNothing() {
		// GuardRuleEngine calls rule.matches(probe) for redirect-loop detection and the exemption
		// self-check; those synthetic requests must not spend a real client's budget
		GuardRule rule = parser.parse("ip-flood | rate:1/1m | reject:429");

		RequestContext probe = toplevel("203.0.113.7");
		probe.setGuardProbe();

		for (int i = 0; i < 10; i++)
			assertFalse(rule.matches(probe));

		assertFalse("a real request afterwards still has its full budget", rule.matches(toplevel("203.0.113.7")));
	}

	@Test
	public void includesCountNothingUnderTheDefaultScope() {
		// an include re-enters the engine carrying the ORIGINAL visitor's address, so metering them
		// would spend a visitor's budget once per fragment of the page they asked for
		GuardRule rule = parser.parse("ip-flood | rate:1/1m | reject:429");

		for (int i = 0; i < 10; i++)
		{
			assertFalse(rule.matches(context("203.0.113.7", FCHeaders.COMPONENT_INCLUDE)));
			assertFalse(rule.matches(context("203.0.113.7", FCHeaders.COMPONENT_ASYNC_INCLUDE)));
		}

		assertFalse("the visitor's own budget is intact", rule.matches(toplevel("203.0.113.7")));
	}

	@Test
	public void scopeAllCountsIncludesToo() {
		GuardRule rule = parser.parse("all-traffic | rate:1/1m scope=all | reject:429");

		assertFalse(rule.matches(context("203.0.113.7", FCHeaders.COMPONENT_INCLUDE)));
		assertTrue(rule.matches(context("203.0.113.7", FCHeaders.COMPONENT_INCLUDE)));
	}

	@Test
	public void anEarlierPredicateThatDoesNotMatchCountsNothing() {
		// short-circuit evaluation is what scopes the counter: only requests the rest of the
		// condition selected are ever metered
		GuardRule rule = parser.parse("search-flood | uri~^/search ; rate:1/1m | reject:429");

		for (int i = 0; i < 10; i++)
		{
			RequestContext elsewhere = toplevel("203.0.113.7");
			elsewhere.setRequestURI("/en/welcome.htm");
			assertFalse(rule.matches(elsewhere));
		}

		RequestContext onSearch = toplevel("203.0.113.7");
		onSearch.setRequestURI("/search");
		assertFalse("first search request is within the limit", rule.matches(onSearch));
	}

	@Test
	public void anUnresolvableAddressIsNotMetered() {
		GuardRule rule = parser.parse("ip-flood | rate:1/1m | reject:429");

		RequestContext noRequest = new RequestContext();
		noRequest.setRequestType(FCHeaders.COMPONENT_TOPLEVEL);
		noRequest.setRequestURI("/");
		noRequest.setRequestQueryString("");

		for (int i = 0; i < 10; i++)
			assertFalse("no request behind the context - fail open", rule.matches(noRequest));
	}

	// ---- counters outlive a reload ---------------------------------------------------------------

	@Test
	public void countsSurviveARuleReload() {
		// otherwise an operator editing an unrelated rule during an attack hands every attacker a
		// fresh window
		GuardRule before = parser.parse("ip-flood | rate:2/1m | reject:429");
		before.matches(toplevel("203.0.113.7"));
		before.matches(toplevel("203.0.113.7"));

		GuardRule after = parser.parse("ip-flood | rate:2/1m | reject:429"); // same file, re-read

		assertTrue("the client is still over its limit after the reload", after.matches(toplevel("203.0.113.7")));
	}

	@Test
	public void aChangedLimitRebuildsTheCounter() {
		GuardRule before = parser.parse("ip-flood | rate:2/1m | reject:429");
		before.matches(toplevel("203.0.113.7"));
		before.matches(toplevel("203.0.113.7"));

		GuardRule widened = parser.parse("ip-flood | rate:100/1m | reject:429");

		assertFalse("counts cannot be reinterpreted under a new limit, so they reset",
				widened.matches(toplevel("203.0.113.7")));
		assertEquals(1, GuardRateLimiterRegistry.getLimiters().size());
	}

	// ---- stand-in request ---------------------------------------------------------------------------

	private static RequestContext toplevel(String remoteAddr) {
		return context(remoteAddr, FCHeaders.COMPONENT_TOPLEVEL);
	}

	private static RequestContext context(final String remoteAddr, String requestType) {
		RequestContext context = new RequestContext();
		context.setRequestType(requestType);
		context.setRequestURI("/search");
		context.setRequestQueryString("");
		context.setFrontCacheHost("www.example.com");

		context.setRequest((HttpServletRequest) Proxy.newProxyInstance(
				RateLimitPredicateTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getRemoteAddr":
							return remoteAddr;
						case "getHeaders":
							return Collections.enumeration(Collections.<String>emptyList());
						case "getMethod":
							return "GET";
						default:
							return null;
						}
					}
				}));

		return context;
	}

}
