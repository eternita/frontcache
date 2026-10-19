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

import java.util.List;

import org.frontcache.guard.GuardRuleEntry;

/**
 * Response for the get-guard-rules action: the guard rules this node is running, in evaluation
 * order (built-ins first, then conf/guard-rules.conf), each with its hit count since start.
 *
 * A LIST, not a per-domain map like bots/dynamic-urls: guard rules are global by design (a rule
 * targets a domain with a host~ predicate), and their order is what decides which one wins.
 */
public class GetGuardRulesActionResponse extends ActionResponse {

	private List<GuardRuleEntry> guardRules;

	public GetGuardRulesActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public List<GuardRuleEntry> getGuardRules() {
		return guardRules;
	}

	public void setGuardRules(List<GuardRuleEntry> guardRules) {
		this.guardRules = guardRules;
	}

}
