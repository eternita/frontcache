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
package org.frontcache.io;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.bots.BotRuleEntry;
import org.frontcache.bots.VisitorTypeEntry;

/**
 * Bot classification config from ./conf/bots.conf: the ordered rules, and the bare User-Agent
 * keywords.
 *
 * A flat set as of 2.7. Before that it was a {@code Map<domain, Set<String>>} - a shape that outlived
 * its reason: one node serves one site, so the map always held exactly one entry, and the console's
 * JSPs had to switch on the literal {@code "default-domain"} to render it.
 *
 * Both shapes go out on the wire, and both are accepted coming in. See
 * {@link LegacyDomainKeyedField} for why.
 *
 * {@code botRules} is additive, as of 2.9.0. {@code botKeywords} keeps meaning exactly what it did -
 * the BARE keyword lines, nothing else - so a console older than the rules still renders the list it
 * knows; a newer console reading an older node finds botRules absent and falls back to the same
 * list. The console is deployed separately from the fleet and upgrades in either order.
 *
 * {@code visitorTypes} is additive likewise, as of 2.13.0: the roots and the declared types, each with its base, its
 * {@code no-store} flag and the hits of the rules deciding it. An older node leaves it absent; an older console
 * ignores it and renders a declared type as the string in a rule's verdict column.
 */
public class GetBotsActionResponse extends ActionResponse {

	private Set<String> botKeywords;

	private List<BotRuleEntry> botRules;

	private List<VisitorTypeEntry> visitorTypes;

	public GetBotsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Set<String> getBotKeywords() {
		return botKeywords;
	}

	public void setBotKeywords(Set<String> botKeywords) {
		this.botKeywords = botKeywords;
	}

	/**
	 * The rules in evaluation order (built-ins included), each with its hit count since load. Null
	 * from a node older than 2.9.0.
	 */
	public List<BotRuleEntry> getBotRules() {
		return botRules;
	}

	public void setBotRules(List<BotRuleEntry> botRules) {
		this.botRules = botRules;
	}

	/** The roots, then the declared types. Null from a node older than 2.13.0 - it knows bot and guest only. */
	public List<VisitorTypeEntry> getVisitorTypes() {
		return visitorTypes;
	}

	public void setVisitorTypes(List<VisitorTypeEntry> visitorTypes) {
		this.visitorTypes = visitorTypes;
	}

	/**
	 * @deprecated pre-2.7 shape of {@link #getBotKeywords()} - a single-entry map keyed by domain.
	 */
	@Deprecated
	public Map<String, Set<String>> getBots() {
		return LegacyDomainKeyedField.wrap(botKeywords);
	}

	/** @deprecated pre-2.7 shape of {@link #setBotKeywords(Set)} */
	@Deprecated
	public void setBots(Map<String, Set<String>> bots) {
		if (null == botKeywords)
			botKeywords = LegacyDomainKeyedField.flatten(bots);
	}

}
