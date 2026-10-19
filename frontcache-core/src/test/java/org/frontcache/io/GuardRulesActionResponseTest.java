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
package org.frontcache.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.frontcache.guard.GuardAction;
import org.frontcache.guard.GuardPredicateFactory;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEntry;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The get-guard-rules / reload-guard-rules management actions: constants, the rule -> DTO mapping
 * the console renders, and a JSON round-trip (FrontCacheClient deserializes the same shape the
 * servlet writes, so a mismatch here breaks the console silently).
 */
public class GuardRulesActionResponseTest {

	private final ObjectMapper jsonMapper = new ObjectMapper();

	@Test
	public void guardActionConstantsExist() {
		assertEquals("get-guard-rules", FrontcacheAction.GET_GUARD_RULES);
		assertEquals("reload-guard-rules", FrontcacheAction.RELOAD_GUARD_RULES);
	}

	@Test
	public void guardActionsAreDocumentedInHelp() {
		Map<String, String> desc = FrontcacheAction.actionsDescriptionMap;

		assertTrue(desc.containsKey(FrontcacheAction.GET_GUARD_RULES));
		assertTrue(desc.containsKey(FrontcacheAction.RELOAD_GUARD_RULES));
		assertNotNull(desc.get(FrontcacheAction.GET_GUARD_RULES));
		assertNotNull(desc.get(FrontcacheAction.RELOAD_GUARD_RULES));
	}

	@Test
	public void ruleMapsToEntryWithHits() {
		GuardRule rule = new GuardRule("ip-access",
				Arrays.asList(GuardPredicateFactory.parse("host:ip")),
				GuardAction.redirect(301, "https://www.example.com/en/welcome.htm"),
				false);
		rule.countHit();
		rule.countHit();

		GuardRuleEntry entry = rule.toEntry();

		assertEquals("ip-access", entry.getName());
		assertEquals("host:ip", entry.getCondition());
		assertEquals("redirect:301 https://www.example.com/en/welcome.htm", entry.getAction());
		assertFalse(entry.isDryRun());
		assertEquals(2, entry.getHits());
	}

	@Test
	public void dryRunFlagSurvivesTheMapping() {
		GuardRule rule = new GuardRule("noisy-bots",
				Arrays.asList(GuardPredicateFactory.parse("uri~^/search\\.htm"),
						GuardPredicateFactory.parse("!cookie:hruc")),
				GuardAction.reject(429, "429 Slow Down"),
				true);

		GuardRuleEntry entry = rule.toEntry();

		assertTrue(entry.isDryRun());
		assertEquals("uri~^/search\\.htm ; !cookie:hruc", entry.getCondition());
		assertEquals(0, entry.getHits());
	}

	@Test
	public void responseSurvivesAJsonRoundTrip() throws Exception {
		GetGuardRulesActionResponse response = new GetGuardRulesActionResponse();
		response.setGuardRules(Arrays.asList(
				new GuardRuleEntry("uri-too-long", "uri-length>4096", "reject:414", false, 12),
				new GuardRuleEntry("ip-access", "host:ip", "redirect:301 https://www.example.com/", false, 3)));

		String json = jsonMapper.writeValueAsString(response);
		GetGuardRulesActionResponse parsed = jsonMapper.readValue(json, GetGuardRulesActionResponse.class);

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, parsed.getResponseStatus());

		List<GuardRuleEntry> rules = parsed.getGuardRules();
		assertEquals(2, rules.size());
		// evaluation order is the payload's meaning - first match wins, so it must survive
		assertEquals("uri-too-long", rules.get(0).getName());
		assertEquals("ip-access", rules.get(1).getName());
		assertEquals(12, rules.get(0).getHits());
		assertEquals("redirect:301 https://www.example.com/", rules.get(1).getAction());
	}

	@Test
	public void reloadResponseIsOkAndSaysWhatHappened() {
		ReloadGuardRulesActionResponse response = new ReloadGuardRulesActionResponse();

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		// counters live in the rebuilt rules, so a reload resets them - callers should know
		assertTrue(response.getAction().contains("reloaded"));
		assertTrue(response.getAction().contains("counters"));
	}

}
