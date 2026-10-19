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
package org.frontcache.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.frontcache.core.RequestContext;
import org.junit.Test;

/**
 * Parsing and validation of single guard-rules.conf lines, plus the predicates they compile to.
 *
 * No FCConfig / FRONTCACHE_HOME involved: the parser takes its allowed-redirect-hosts set directly,
 * so these tests stay independent of whichever fixture home another test class installed first.
 */
public class GuardRuleParserTest {

	private static final Set<String> ALLOWED_HOSTS = new HashSet<String>(java.util.Arrays.asList("www.example-site.com"));

	private final GuardRuleParser parser = new GuardRuleParser(ALLOWED_HOSTS);

	private static RequestContext context(String url) {
		return GuardRuleEngine.probeContext(url);
	}

	@Test
	public void redirectRuleIsParsed() {
		GuardRule rule = parser.parse("ip-access | host:ip | redirect:301 https://www.example-site.com/en/welcome.htm");

		assertEquals("ip-access", rule.getName());
		assertEquals(GuardAction.Type.REDIRECT, rule.getAction().getType());
		assertEquals(301, rule.getAction().getStatus());
		assertEquals("https://www.example-site.com/en/welcome.htm", rule.getAction().getTarget());
		assertFalse(rule.isDryRun());
		assertEquals(GuardAction.DISPOSITION_REDIRECTED, rule.getAction().getDisposition());
	}

	@Test
	public void rejectRuleKeepsCustomBodyAndDryRunFlag() {
		GuardRule rule = parser.parse("noisy | uri~^/search\\.htm | reject:429 429 Slow Down | dry-run");

		assertEquals(GuardAction.Type.REJECT, rule.getAction().getType());
		assertEquals(429, rule.getAction().getStatus());
		assertEquals("429 Slow Down", rule.getAction().getBody());
		assertTrue(rule.isDryRun());
	}

	@Test
	public void allowRuleNeedsNoStatus() {
		GuardRule rule = parser.parse("ping | uri~^/fc-ping\\.jsp$ | allow");

		assertEquals(GuardAction.Type.ALLOW, rule.getAction().getType());
		// allow is an exemption, not an event - nothing to log
		assertEquals(null, rule.getAction().getDisposition());
	}

	@Test
	public void predicatesAreAndedAndNegationIsHonoured() {
		GuardRule rule = parser.parse("anon | uri~^/en/ccc/va/ ; !cookie:hruc | redirect:302 /login.htm");

		assertEquals(2, rule.getPredicates().size());
		// no servlet request behind a probe context -> no cookies -> !cookie:hruc holds
		assertTrue(rule.matches(context("https://www.example-site.com/en/ccc/va/visited-coins-analytics.htm")));
		// first predicate fails -> whole condition fails
		assertFalse(rule.matches(context("https://www.example-site.com/en/welcome.htm")));
	}

	@Test
	public void hostIpMatchesIpLiteralsOnly() {
		GuardRule rule = parser.parse("ip-access | host:ip | redirect:301 /en/welcome.htm");

		assertTrue(rule.matches(context("http://160.202.254.65/")));
		assertTrue("IPv6 literal", rule.matches(context("http://0:0:0:0:0:0:0:1/")));
		assertFalse(rule.matches(context("https://www.example-site.com/")));
		// a name that merely starts with digits is still a name
		assertFalse(rule.matches(context("https://1and1.example-site.com/")));
	}

	@Test
	public void queryAndUrlPredicatesSeeTheQueryString() {
		GuardRule query = parser.parse("q | query~itemType | reject:400 no");
		GuardRule url = parser.parse("u | url~^https://www\\.example-site\\.com/en/ | reject:400 no");

		RequestContext ctx = context("https://www.example-site.com/en/ccc/va/visited.htm?itemTypeFQ=itemType:coin");
		assertTrue(query.matches(ctx));
		assertTrue(url.matches(ctx));
		assertFalse(query.matches(context("https://www.example-site.com/en/welcome.htm")));
	}

	@Test
	public void relativeRedirectTargetIsAlwaysAllowed() {
		GuardRule rule = parser.parse("r | uri~^/x | redirect:302 /login.htm");
		assertEquals("/login.htm", rule.getAction().getTarget());
	}

	@Test
	public void redirectToUnlistedHostIsRefused() {
		// open-redirect protection: a stray config line must not be able to point traffic elsewhere
		assertRejected("evil | uri~^/x | redirect:302 https://evil.example.com/");
	}

	@Test
	public void malformedLinesAreRefused() {
		assertRejected("no-action | uri~^/x");                            // missing action
		assertRejected(" | uri~^/x | allow");                             // no name
		assertRejected("empty-cond |  | allow");                          // no condition
		assertRejected("bad-regex | uri~^/[unclosed | allow");            // invalid regex
		assertRejected("unknown-pred | cookies:hruc | allow");            // unknown predicate
		assertRejected("bad-status | uri~^/x | redirect:200 /y");         // 200 is not a redirect
		assertRejected("bad-status2 | uri~^/x | reject:302 nope");        // 302 is not a rejection
		assertRejected("no-target | uri~^/x | redirect:302");             // redirect without target
		assertRejected("unknown-flag | uri~^/x | allow | dry-runn");      // typo in the flag
	}

	@Test
	public void placeholdersExpandAgainstTheRequest() {
		GuardRule rule = parser.parse("anon | uri~^/en/ | redirect:302 /login.htm?return=${url:enc}&from=${host}${uri}${query}");

		String target = rule.getAction().resolveTarget(context("https://www.example-site.com/en/ccc/va/v.htm?a=1"));

		assertEquals("/login.htm?return=https%3A%2F%2Fwww.example-site.com%2Fen%2Fccc%2Fva%2Fv.htm%3Fa%3D1"
				+ "&from=www.example-site.com/en/ccc/va/v.htm?a=1", target);
	}

	/**
	 * "Same path, other host" - what a second layer sends a client that reached it directly. The host
	 * is followed by a placeholder rather than a '/', and was read as 'www.example-site.com${uri}${query}'
	 * and refused (docs/direct-access-restriction-proposal.md).
	 */
	@Test
	public void aPlaceholderRightAfterTheHostEndsTheHost() {
		GuardRule rule = parser.parse("direct | uri~^/en/ ; method:GET | redirect:301 https://www.example-site.com${uri}${query}");

		assertEquals("https://www.example-site.com/en/search.htm?a=1",
				rule.getAction().resolveTarget(context("https://origin.example-site.com/en/search.htm?a=1")));
	}

	@Test
	public void aPlaceholderAfterTheHostDoesNotExcuseADisallowedHost() {
		assertRejected("direct | uri~^/en/ | redirect:301 https://evil.example.com${uri}");
		assertRejected("direct | uri~^/en/ | redirect:301 https://www.example-site.com.evil.example.com${uri}");
		// a target whose whole host is a placeholder is still refused here - there is no host to check
		assertRejected("direct | uri~^/en/ | redirect:301 https://${host}${uri}");
	}

	@Test
	public void unparseableLineNeverEscapesAsSomethingElse() {
		// every rejection is a GuardConfigException so the engine can skip exactly one line
		try {
			new GuardRuleParser(Collections.<String>emptySet()).parse("x | uri~^/y | redirect:302 https://www.example-site.com/");
			fail("expected GuardConfigException - no allowed hosts configured");
		} catch (GuardConfigException expected) {
			assertTrue(expected.getMessage().contains("not allowed"));
		}
	}

	private void assertRejected(String line) {
		try {
			parser.parse(line);
			fail("expected GuardConfigException for: " + line);
		} catch (GuardConfigException expected) {
			// as designed: one bad line is skipped, the file keeps working
		}
	}

}
