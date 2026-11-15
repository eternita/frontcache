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
package org.frontcache.bots;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardConfigException;
import org.junit.After;
import org.junit.Test;

/**
 * Declared visitor types: the {@code @type} directive and its refusals, the is-a and leaf-then-base rules every
 * consumer resolves through, the {@code client=} selector, and bots.conf rules classifying into a declared type.
 */
public class VisitorTypesTest {

	@After
	public void unload() {
		BotClassifier.install(null);
	}

	// ---- the directive --------------------------------------------------------------------

	@Test
	public void aDeclarationNamesABase() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest", "@type bot-whitelisted   like   bot");

		assertEquals("guest", types.rootOf("customer"));
		assertEquals("bot", types.rootOf("bot-whitelisted"));
		assertEquals("a root is its own root", "guest", types.rootOf("guest"));
		assertNull("an undeclared name has none", types.rootOf("captcha-passed"));
		assertTrue(types.isKnown("bot") && types.isKnown("customer"));
		assertFalse(types.isKnown("captcha-passed"));
	}

	@Test
	public void aDeclarationMayEndWithAComment() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest      # a tracking/session cookie is present");

		assertEquals("guest", types.rootOf("customer"));
	}

	@Test
	public void noDeclarationsIsTheTwoRoots() {
		assertTrue(VisitorTypes.ROOTS.declared().isEmpty());
		assertTrue(VisitorTypes.ROOTS.isKnown("guest"));
		assertFalse(VisitorTypes.ROOTS.isKnown("customer"));
		assertEquals("nothing loaded is the roots, and active() never loads", VisitorTypes.ROOTS, VisitorTypes.active());
	}

	@Test
	public void theDirectiveIsRecognisedByPrefixOnly() {
		assertTrue(VisitorTypes.isDeclaration("@type customer like guest"));
		assertTrue(VisitorTypes.isDeclaration("@type\tcustomer like guest"));
		assertFalse("a keyword that merely starts with the letters", VisitorTypes.isDeclaration("@typewriter-bot"));
		assertFalse(VisitorTypes.isDeclaration("customer | cookie:hruc | customer"));
	}

	@Test
	public void eachBadDeclarationIsRefusedWithAReason() {
		assertRefusedDeclaration("@type customer", "expected");
		assertRefusedDeclaration("@type customer is guest", "expected");
		assertRefusedDeclaration("@type Customer like guest", "not a type name");
		assertRefusedDeclaration("@type 9lives like guest", "not a type name");
		assertRefusedDeclaration("@type a_b like guest", "not a type name");
		assertRefusedDeclaration("@type " + "x".repeat(33) + " like guest", "not a type name");
		assertRefusedDeclaration("@type guest like bot", "built-in");
		assertRefusedDeclaration("@type bot like guest", "built-in");
		assertRefusedDeclaration("@type browser like guest", "renamed");
		assertRefusedDeclaration("@type all like guest", "reserved");
		assertRefusedDeclaration("@type none like bot", "reserved");
		assertRefusedDeclaration("@type customer like human", "not a base");
		assertRefusedDeclaration("@type customer like guest store", "unknown flag");
	}

	/** One level deep: a declared type is never a base. */
	@Test
	public void aDeclaredTypeCannotBeABase() {
		VisitorTypes.Builder builder = VisitorTypes.builder();
		builder.add("@type customer like guest");
		try {
			builder.add("@type customer-verified like customer");
			fail("a declared type as a base must be refused");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("one level deep"));
		}
	}

	@Test
	public void noStoreFlagsTheTypeAndNothingElse() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest no-store   # session cookie",
				"@type bot-monitoring like bot no-store", "@type captcha-passed like guest");

		assertTrue(types.isNoStore("customer"));
		assertTrue("a bot-based type may be no-store too", types.isNoStore("bot-monitoring"));
		assertEquals("the flag changes nothing about the base", "guest", types.rootOf("customer"));
		assertFalse(types.isNoStore("captcha-passed"));
		assertFalse("a root is never no-store", types.isNoStore("guest"));
		assertFalse(types.isNoStore("bot"));
		assertFalse("an unknown name is not", types.isNoStore("partner"));
		assertFalse(types.isNoStore(null));
		assertFalse(VisitorTypes.ROOTS.isNoStore("guest"));
	}

	/** A misspelt no-store must not declare a type that stores: the whole line is refused. */
	@Test
	public void anUnknownFlagRefusesTheDeclaration() {
		assertRefusedDeclaration("@type customer like guest nostore", "unknown flag 'nostore'");
		assertRefusedDeclaration("@type customer like guest no-store store", "unknown flag 'store'");
	}

	/** First wins - the duplicate's flag included, either way round. */
	@Test
	public void aDuplicatesFlagIsIgnoredWithIt() {
		VisitorTypes.Builder builder = VisitorTypes.builder();
		builder.add("@type customer like guest");
		assertTrue(builder.add("@type customer like guest no-store").contains("first wins"));
		assertFalse(builder.build().isNoStore("customer"));

		builder = VisitorTypes.builder();
		builder.add("@type customer like guest no-store");
		builder.add("@type customer like guest");
		assertTrue(builder.build().isNoStore("customer"));
	}

	/** Decided when an entry would be written, from the running vocabulary - for the leaf only. */
	@Test
	public void aRequestIsNoStoreOnlyAsTheFlaggedLeaf() {
		BotClassifier.install(BotClassifier.of(new ArrayList<BotRule>(),
				VisitorTypes.of("@type customer like guest no-store", "@type captcha-passed like guest")));

		assertTrue(org.frontcache.core.FCUtils.isNoStoreVisitorType(context("customer", "guest")));
		assertFalse(org.frontcache.core.FCUtils.isNoStoreVisitorType(context("captcha-passed", "guest")));
		assertFalse("the base of a no-store type is not no-store",
				org.frontcache.core.FCUtils.isNoStoreVisitorType(context("guest", "guest")));

		// a reload that drops the flag lets the next write through - nothing about it was kept on the request
		RequestContext inFlight = context("customer", "guest");
		BotClassifier.install(BotClassifier.of(new ArrayList<BotRule>(), VisitorTypes.of("@type customer like guest")));
		assertFalse(org.frontcache.core.FCUtils.isNoStoreVisitorType(inFlight));
	}

	@Test
	public void aDuplicateIsAWarningAndTheFirstWins() {
		VisitorTypes.Builder builder = VisitorTypes.builder();
		assertNull(builder.add("@type customer like guest"));
		String warning = builder.add("@type customer like bot");

		assertTrue(warning, warning.contains("first wins"));
		assertEquals("guest", builder.build().rootOf("customer"));
	}

	@Test
	public void theSeventeenthDeclarationIsRefused() {
		VisitorTypes.Builder builder = VisitorTypes.builder();
		for (int i = 0; i < VisitorTypes.MAX_DECLARED; i++)
			builder.add("@type t" + i + " like guest");
		try {
			builder.add("@type one-too-many like guest");
			fail("the cap must hold");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("more than " + VisitorTypes.MAX_DECLARED));
		}
		assertEquals(VisitorTypes.MAX_DECLARED, builder.build().declared().size());
	}

	// ---- is-a and leaf-then-base ----------------------------------------------------------

	@Test
	public void aTypeIsItselfAndItsBase() {
		assertTrue(VisitorTypes.isA("customer", "guest", "customer"));
		assertTrue(VisitorTypes.isA("customer", "guest", "guest"));
		assertFalse(VisitorTypes.isA("customer", "guest", "bot"));
		assertFalse("a root is not its declared types", VisitorTypes.isA("guest", "guest", "customer"));
	}

	/** Proposal 7.1.2's table, row by row: bot, guest, customer (like guest), bot-monitoring (like bot). */
	@Test
	public void ttlReadsTheLeafThenTheBase() {
		long d30 = 30L, h1 = 1L, m5 = 5L, forever = CacheProcessor.CACHE_FOREVER, origin = CacheProcessor.NO_CACHE;

		assertRow(map(d30, h1), d30, h1, h1, d30);                                      // bot:30d, guest:1h
		assertRow(withLeaf(map(d30, h1), "customer", origin), d30, h1, origin, d30);    // ..., customer:0
		assertRow(withLeaf(map(origin, h1), "customer", m5), origin, h1, m5, origin);   // guest:1h, customer:5m
		assertRow(withLeaf(map(origin, origin), "customer", origin), origin, origin, origin, origin);
		assertRow(withLeaf(map(origin, origin), "bot-monitoring", forever), origin, origin, origin, forever);
		assertRow(withLeaf(map(d30, origin), "bot-monitoring", origin), d30, origin, origin, origin);
	}

	@Test
	public void anEntryWrittenBeforeTheTypeExistedAnswersItThroughItsBase() {
		// the "no flush" property: an entry holding only bot / guest answers every declared type
		assertEquals(Long.valueOf(7), VisitorTypes.ttl(map(3, 7), "customer", "guest"));
		assertNull("only a map without the root has no answer", VisitorTypes.ttl(new HashMap<String, Long>(), "customer", "guest"));
	}

	// ---- client="..." ---------------------------------------------------------------------

	@Test
	public void aRootSelectsItsDeclaredTypes() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest");

		ClientSelector guest = ClientSelector.parse("guest", types);
		assertTrue("a customer was a guest yesterday and still gets the fragment", guest.selects("customer", "guest"));
		assertTrue(guest.selects("guest", "guest"));
		assertFalse(guest.selects("bot", "bot"));

		ClientSelector customer = ClientSelector.parse("customer", types);
		assertTrue(customer.selects("customer", "guest"));
		assertFalse("client=customer is customers only", customer.selects("guest", "guest"));
	}

	@Test
	public void aListSelectsAnyOfItsNames() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest", "@type captcha-passed like guest");
		ClientSelector selector = ClientSelector.parse("customer, captcha-passed", types);

		assertTrue(selector.selects("customer", "guest"));
		assertTrue(selector.selects("captcha-passed", "guest"));
		assertFalse(selector.selects("guest", "guest"));
	}

	@Test
	public void aNegatedNameExcludes() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest");

		ClientSelector everyoneButCustomers = ClientSelector.parse("!customer", types);
		assertTrue(everyoneButCustomers.selects("guest", "guest"));
		assertTrue("bots included", everyoneButCustomers.selects("bot", "bot"));
		assertFalse(everyoneButCustomers.selects("customer", "guest"));

		ClientSelector guestsButCustomers = ClientSelector.parse("guest, !customer", types);
		assertTrue(guestsButCustomers.selects("guest", "guest"));
		assertFalse(guestsButCustomers.selects("bot", "bot"));
		assertFalse(guestsButCustomers.selects("customer", "guest"));

		ClientSelector notBots = ClientSelector.parse("!bot", types);
		assertTrue("!bot means what guest means", notBots.selects("customer", "guest"));
		assertFalse(notBots.selects("bot", "bot"));
	}

	/** A typo fails the way it always failed: no known name left is "no attribute" - everyone. */
	@Test
	public void unknownNamesAreDroppedAndNoneLeftIsEveryone() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest");

		assertNull(ClientSelector.parse("custmer", types));
		assertNull(ClientSelector.parse("!custmer", types));
		assertNull(ClientSelector.parse("", types));
		assertNull(ClientSelector.parse(null, types));
		assertNull("'all' is the documented default", ClientSelector.parse("all", types));
		assertFalse("the known name in a list still counts", ClientSelector.parse("custmer, customer", types).selects("guest", "guest"));
	}

	@Test
	public void theRootsKeepTheirCaseInsensitiveSpelling() {
		assertTrue(ClientSelector.parse("BOT", VisitorTypes.ROOTS) == ClientSelector.BOT);
		assertTrue(ClientSelector.parse(" Guest ", VisitorTypes.ROOTS) == ClientSelector.GUEST);
	}

	// ---- classifying into a declared type -------------------------------------------------

	@Test
	public void aRuleClassifiesIntoADeclaredTypeAndTheClientTypeIsItsRoot() {
		VisitorTypes types = VisitorTypes.of("@type bot-monitoring like bot");
		BotRule rule = BotRuleParser.parse("monitoring | ua~(?i)pingdom | bot-monitoring", types);

		assertEquals("bot-monitoring", rule.getVisitorType());
		assertEquals("bot", rule.getClientType());
		assertEquals("the console's verdict column shows the type as written", "bot-monitoring", rule.toEntry().getClientType());
	}

	@Test
	public void theOneArgumentParseFailsClosedOnADeclaredType() {
		try {
			BotRuleParser.parse("monitoring | ua~(?i)pingdom | bot-monitoring");
			fail("a lone line parsed without its file's declarations must refuse a declared type");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("not a visitor type"));
		}
	}

	@Test
	public void aRootVerdictKeepsBothValuesEqual() {
		BotRule rule = BotRuleParser.parse("seo | ua~(?i)ahrefs | bot", VisitorTypes.of("@type customer like guest"));

		assertEquals("bot", rule.getVisitorType());
		assertEquals("bot", rule.getClientType());
		assertEquals("a bare keyword is a bot", "bot", BotRuleParser.keywordRule("Googlebot").getVisitorType());
		assertEquals("the built-in default is guest", "guest", BotClassifier.defaultRule().getVisitorType());
	}

	@Test
	public void validateReadsDeclarationsBeforeRulesAndReportsInFileOrder() {
		List<String> errors = BotClassifier.validate(String.join("\n",
				"customer | cookie:hruc ; request-type:toplevel | customer",  // 1: names a type declared below
				"@type customer like guest",                                   // 2
				"suspect  | ua~(?i)curl | suspect",                            // 3: undeclared
				"@type browser like guest",                                    // 4: reserved
				"Googlebot"));                                                 // 5

		assertEquals(errors.toString(), 2, errors.size());
		assertTrue(errors.get(0), errors.get(0).startsWith("line 3:"));
		assertTrue(errors.get(1), errors.get(1).startsWith("line 4:"));
	}

	/** get-bots' visitorTypes: the roots first, then the declared types, each with the hits of the rules deciding it. */
	@Test
	public void theTypeEntriesListEveryTypeWithItsDecidingHits() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest no-store", "@type bot-whitelisted like bot");
		BotRule customer = BotRuleParser.parse("customer | cookie:hruc | customer", types);
		BotRule shadow = BotRuleParser.parse("customer-dry | cookie:x | customer | dry-run", types);
		BotRule seo = BotRuleParser.parse("seo | ua~(?i)ahrefs | bot", types);
		List<BotRule> rules = new ArrayList<BotRule>(List.of(customer, shadow, seo, BotClassifier.defaultRule()));
		customer.countHit();
		customer.countHit();
		shadow.countHit();
		seo.countHit();
		rules.get(3).countHit(); // the built-in default

		List<VisitorTypeEntry> entries = BotClassifier.of(rules, types).getVisitorTypeEntries();

		assertEquals("[bot like bot (1 hits), guest like guest (1 hits),"
				+ " customer like guest no-store (2 hits), bot-whitelisted like bot (0 hits)]", entries.toString());
		assertTrue(entries.get(0).isBuiltIn());
		assertFalse(entries.get(2).isBuiltIn());
	}

	@Test
	public void theInstalledClassifiersVocabularyIsActive() {
		VisitorTypes types = VisitorTypes.of("@type customer like guest");
		List<BotRule> rules = new ArrayList<BotRule>();
		rules.add(BotClassifier.defaultRule());
		BotClassifier.install(BotClassifier.of(rules, types));

		assertTrue(VisitorTypes.active().isKnown("customer"));
	}

	@Test
	public void aRequestCarriesTheLeafAndTheRoot() {
		RequestContext context = new RequestContext();
		context.setVisitorType("customer", "guest");
		assertEquals("customer", context.getVisitorType());
		assertEquals("getClientType() stays the root", "guest", context.getClientType());

		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_BOT);
		assertEquals("setting a root alone resets the leaf", "bot", context.getVisitorType());
	}

	// ---- helpers --------------------------------------------------------------------------

	private static RequestContext context(String visitorType, String clientType) {
		RequestContext context = new RequestContext();
		context.setVisitorType(visitorType, clientType);
		return context;
	}

	private static void assertRefusedDeclaration(String line, String reason) {
		try {
			VisitorTypes.builder().add(line);
			fail("'" + line + "' must be refused");
		} catch (GuardConfigException e) {
			assertTrue("'" + line + "': " + e.getMessage(), e.getMessage().contains(reason));
		}
	}

	private static Map<String, Long> map(long bot, long guest) {
		Map<String, Long> map = new HashMap<String, Long>();
		map.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, bot);
		map.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, guest);
		return map;
	}

	private static Map<String, Long> withLeaf(Map<String, Long> map, String type, long value) {
		map.put(type, value);
		return map;
	}

	private static void assertRow(Map<String, Long> map, long bot, long guest, long customer, long monitoring) {
		assertEquals(map.toString() + " bot", Long.valueOf(bot), VisitorTypes.ttl(map, "bot", "bot"));
		assertEquals(map.toString() + " guest", Long.valueOf(guest), VisitorTypes.ttl(map, "guest", "guest"));
		assertEquals(map.toString() + " customer", Long.valueOf(customer), VisitorTypes.ttl(map, "customer", "guest"));
		assertEquals(map.toString() + " bot-monitoring", Long.valueOf(monitoring), VisitorTypes.ttl(map, "bot-monitoring", "bot"));
	}
}
