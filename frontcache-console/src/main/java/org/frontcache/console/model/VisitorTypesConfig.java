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
package org.frontcache.console.model;

import java.util.List;
import java.util.Set;

import org.frontcache.bots.BotRuleEntry;

/**
 * One edge's visitor-type classification: the ordered rules, and the bare User-Agent keywords.
 *
 * The verdict is {@code bot} or {@code guest} - a behaviour profile, not a judgement about the
 * client - which is what the console screen is named after. The raw conf/bots.conf behind it is a
 * separate model, {@link BotConfig}, on the Configs / Bot Configs screen.
 *
 * The keywords were a flat set as of 2.7 (the management API used to return them keyed by domain,
 * which always held a single entry once one node served one site), and the rules were added in 2.9.0.
 *
 * <b>Rules can be null</b>, and the JSP has to cope: the console reads a mixed fleet and is upgraded
 * independently of the nodes, so an edge older than 2.9.0 answers with keywords only.
 */
public class VisitorTypesConfig {

	private String name;
	private Set<String> config;
	private List<BotRuleEntry> rules;

	public VisitorTypesConfig(String name, Set<String> config, List<BotRuleEntry> rules) {
		super();
		this.name = name;
		this.config = config;
		this.rules = rules;
	}

	public String getName() {
		return name;
	}

	public Set<String> getConfig() {
		return config;
	}

	/** null from an edge older than 2.9.0 - render {@link #getConfig()} instead */
	public List<BotRuleEntry> getRules() {
		return rules;
	}

}
