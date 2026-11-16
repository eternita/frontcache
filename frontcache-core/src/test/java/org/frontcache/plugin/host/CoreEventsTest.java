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
package org.frontcache.plugin.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleParser;
import org.frontcache.guard.ratelimit.GuardRateLimiterRegistry;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardSummary;
import org.frontcache.plugin.event.RateLimitExceeded;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The core's emit points that need the guard: the once-per-window rate-limit event and the guard summary. */
public class CoreEventsTest {

	private final GuardRuleParser parser = new GuardRuleParser(Collections.<String>emptySet());

	@Before
	public void setUp() {
		GuardRateLimiterRegistry.reset();
		ClientIpResolver.install(new ClientIpResolver("", "X-Forwarded-For"));
	}

	@After
	public void tearDown() {
		FcEventBus.stopAll();
		GuardRateLimiterRegistry.reset();
		ClientIpResolver.reload();
	}

	@Test
	public void aClientGoingOverTheLimitIsOneEventPerWindowNotOnePerRefusal() throws InterruptedException {
		List<FcEvent> received = Collections.synchronizedList(new ArrayList<>());
		PluginCounters counters = new PluginCounters();
		FcEventBus.subscribe("test", FcEventBusTest.listener(Set.of(RateLimitExceeded.class), received::addAll), 100,
				counters);

		GuardRule rule = parser.parse("search-limit | rate:2/1m | reject:429");
		for (int i = 0; i < 10; i++)
			rule.matches(request("203.0.113.7"));
		rule.matches(request("198.51.100.9"));
		rule.matches(request("198.51.100.9"));
		rule.matches(request("198.51.100.9"));

		FcEventBusTest.waitFor(() -> 2 == counters.delivered.sum());
		Thread.sleep(100);
		assertEquals("one per client", 2, received.size());
		RateLimitExceeded first = (RateLimitExceeded) received.get(0);
		assertEquals("search-limit", first.bucket());
		assertEquals("search-limit", first.rule());
		assertEquals(2, first.limit());
		assertEquals(60_000L, first.windowMillis());
		assertEquals("203.0.113.7", first.clientKey());
		assertEquals("www.example.com/search?q=x", first.uri());
		assertEquals(RateLimitExceeded.TYPE, first.type());
	}

	@Test
	public void theGuardSummaryCountsEachIntervalOnItsOwn() {
		GuardRule reject = parser.parse("bad-bots | uri~^/wp-admin | reject:403");
		GuardRule dryRun = parser.parse("shadow | uri~^/search | reject:429 | dry-run");
		GuardRule allow = parser.parse("health | uri~^/health | allow");
		GuardRule limited = parser.parse("search-limit | rate:1/1m | reject:429");
		List<GuardRule> rules = List.of(reject, dryRun, allow, limited);

		reject.countHit();
		GuardSummaryTicker ticker = new GuardSummaryTicker(() -> rules, 10_000L);
		ticker.summarize(); // the baseline - what happened before the ticker started is not in any summary

		reject.countHit();
		reject.countHit();
		dryRun.countHit();
		limited.matches(request("203.0.113.7"));
		limited.matches(request("203.0.113.7"));
		limited.matches(request("203.0.113.7"));

		GuardSummary summary = ticker.summarize();
		assertEquals(10_000L, summary.intervalMillis());
		assertEquals(4, summary.rules().size());
		assertEquals(new GuardSummary.RuleCount("bad-bots", "rejected", 2), summary.rules().get(0));
		assertEquals(new GuardSummary.RuleCount("shadow", "dry-run", 1), summary.rules().get(1));
		assertEquals(new GuardSummary.RuleCount("health", "allowed", 0), summary.rules().get(2));
		GuardSummary.BucketCount bucket = summary.buckets().stream().filter(b -> "search-limit".equals(b.bucket()))
				.findFirst().orElseThrow();
		assertEquals("one client went over, however many times it was refused", 1, bucket.overLimit());

		GuardSummary next = ticker.summarize();
		assertTrue(next.rules().stream().allMatch(r -> 0 == r.matched()));
		assertTrue(next.buckets().stream().allMatch(b -> 0 == b.overLimit()));
	}

	private static RequestContext request(String remoteAddr) {
		RequestContext context = new RequestContext();
		context.setRequestType(FCHeaders.COMPONENT_TOPLEVEL);
		context.setRequestURI("/search");
		context.setRequestQueryString("?q=x");
		context.setFrontCacheHost("www.example.com");
		context.setRequest((HttpServletRequest) Proxy.newProxyInstance(CoreEventsTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class }, (proxy, method, args) -> {
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
				}));
		return context;
	}
}
