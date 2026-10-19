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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;

import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardPredicate;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Parsing a rate: predicate, and the two structural rules the rest of the pipeline depends on:
 * a counting predicate is evaluated LAST, and there is at most one of it per rule.
 */
public class RateLimitRuleParsingTest {

	private final GuardRuleParser parser = new GuardRuleParser(Collections.<String>emptySet());

	// The registry is process-scoped on purpose - it has to survive a reload - so it is shared with
	// every other test in the JVM. Clear it on both sides rather than assuming test order.
	@Before
	public void setUp() {
		GuardRateLimiterRegistry.reset();
	}

	@After
	public void tearDown() {
		GuardRateLimiterRegistry.reset();
	}

	private GuardRule parse(String line) {
		return parser.parse(line);
	}

	private static RateLimitSpec specOf(GuardRule rule) {
		for (GuardPredicate predicate : rule.getPredicates())
			if (predicate instanceof RateLimitPredicate)
				return ((RateLimitPredicate) predicate).getSpec();

		throw new IllegalStateException("no rate predicate in " + rule);
	}

	private void expectRefused(String line, String expectedInMessage) {
		try {
			parse(line);
			fail("expected this to be refused: " + line);
		} catch (GuardConfigException e) {
			assertTrue("message was: " + e.getMessage(),
					e.getMessage().toLowerCase().contains(expectedInMessage.toLowerCase()));
		}
	}

	// ---- the shape the feature was designed around ------------------------------------------

	@Test
	public void limitPerWindowIsParsed() {
		RateLimitSpec spec = specOf(parse("ip-flood | rate:100/10s | reject:429 Too Many Requests"));

		assertEquals(100, spec.getLimit());
		assertEquals(10000L, spec.getWindowMillis());
	}

	@Test
	public void aBareUnitMeansOne() {
		assertEquals(1000L, specOf(parse("r | rate:10/s | reject:429")).getWindowMillis());
		assertEquals(60000L, specOf(parse("r | rate:10/m | reject:429")).getWindowMillis());
		assertEquals(3600000L, specOf(parse("r | rate:10/h | reject:429")).getWindowMillis());
	}

	@Test
	public void minutesAndHoursAreParsed() {
		assertEquals(120000L, specOf(parse("r | rate:10/2m | reject:429")).getWindowMillis());
		assertEquals(3600000L, specOf(parse("r | rate:10/60m | reject:429")).getWindowMillis());
	}

	@Test
	public void defaultsAreBucketPerRuleAndToplevelOnly() {
		RateLimitSpec spec = specOf(parse("search-flood | uri~^/search ; rate:20/10s | reject:429"));

		assertEquals("the bucket is the rule name, so a dry-run copy measures its own limit",
				"search-flood", spec.getBucket());
		assertTrue(spec.isToplevelOnly());
	}

	@Test
	public void bucketAndScopeAreHonouredInEitherOrder() {
		RateLimitSpec spec = specOf(parse("r | rate:10/10s scope=all bucket=shared | reject:429"));

		assertEquals("shared", spec.getBucket());
		assertTrue(!spec.isToplevelOnly());
	}

	@Test
	public void rulesSharingABucketShareOneCounter() {
		parse("a | uri~^/x ; rate:5/10s bucket=shared | reject:429");
		parse("b | uri~^/y ; rate:5/10s bucket=shared | reject:429");

		assertEquals(1, GuardRateLimiterRegistry.getLimiters().size());
	}

	@Test
	public void rulesWithoutABucketGetSeparateCounters() {
		parse("live    | rate:100/10s | reject:429");
		parse("shadow  | rate:50/10s  | reject:429 | dry-run");

		assertEquals(2, GuardRateLimiterRegistry.getLimiters().size());
	}

	// ---- the counting predicate runs last ------------------------------------------------------

	@Test
	public void aRatePredicateIsMovedToTheEndOfTheCondition() {
		// Written first, it would meter the whole site and then report hits against a URI it never
		// matched - the condition is an AND, so moving it is semantics-preserving for the match and
		// decisive for what gets counted.
		GuardRule rule = parse("search-flood | rate:20/10s ; uri~^/search ; method:GET | reject:429");

		List<GuardPredicate> predicates = rule.getPredicates();
		assertEquals(3, predicates.size());
		assertTrue("rate must be evaluated last",
				predicates.get(predicates.size() - 1) instanceof RateLimitPredicate);
	}

	@Test
	public void theConditionStillReadsAsWrittenApartFromTheMove() {
		GuardRule rule = parse("search-flood | rate:20/10s ; uri~^/search | reject:429");

		assertEquals("uri~^/search ; rate:20/10s", rule.getCondition());
	}

	@Test
	public void twoRatePredicatesInOneRuleAreRefused() {
		expectRefused("r | rate:10/10s ; rate:100/1m | reject:429", "more than one rate predicate");
	}

	@Test
	public void aNegatedRatePredicateIsRefused() {
		// "true while under the limit" would count on the ALLOWED path and keep evaluating
		expectRefused("r | !rate:10/10s | reject:429", "cannot be negated");
	}

	// ---- bad input is refused, one line at a time -----------------------------------------------

	@Test
	public void malformedRatePredicatesAreRefused() {
		expectRefused("r | rate:100 | reject:429", "<limit>/<window>");
		expectRefused("r | rate:abc/10s | reject:429", "not a request count");
		expectRefused("r | rate:0/10s | reject:429", "at least 1");
		expectRefused("r | rate:-5/10s | reject:429", "at least 1");
		expectRefused("r | rate:10/10x | reject:429", "unit must be s, m or h");
		expectRefused("r | rate:10/0s | reject:429", "at least 1");
		expectRefused("r | rate:10/2h | reject:429", "at most 1h");
		expectRefused("r | rate:10/abcs | reject:429", "not a window length");
		expectRefused("r | rate:10/10s burst=20 | reject:429", "unknown rate attribute");
		expectRefused("r | rate:10/10s scope=sideways | reject:429", "unknown scope");
		expectRefused("r | rate:10/10s bucket= | reject:429", "bucket needs a name");
		expectRefused("r | rate:10/10s toplevel | reject:429", "name=value");
	}

	@Test
	public void aLimitThePackedCounterCouldNotDistinguishIsRefused() {
		expectRefused("r | rate:99999999/10s | reject:429", "at most " + WindowLimiter.MAX_LIMIT);
	}

}
