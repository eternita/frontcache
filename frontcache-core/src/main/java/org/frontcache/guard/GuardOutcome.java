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
package org.frontcache.guard;

import java.util.Collections;
import java.util.List;

/**
 * Result of running the guard pipeline for one request: either "carry on to FC_Total" or a
 * terminal action (reject / redirect) together with the rule that produced it.
 *
 * Dry-run rules that matched are carried alongside: they do not stop the request, but each one is
 * written to the failed-requests log so a new rule can be validated against real traffic before it
 * enforces anything.
 */
public class GuardOutcome {

	/** no rule matched - the request continues as usual */
	public static final GuardOutcome CONTINUE = new GuardOutcome(null, null, null, null, Collections.<GuardRule>emptyList());

	private final GuardRule rule;
	private final GuardAction action;    // what is done - the rule's own, or what a challenge rule fell back to
	private final String resolvedTarget; // redirect / challenge only: target with placeholders expanded
	private final String challengePlugin; // challenge only: the plugin whose page the visitor is sent to
	private final List<GuardRule> dryRunMatches;

	private GuardOutcome(GuardRule rule, GuardAction action, String resolvedTarget, String challengePlugin,
			List<GuardRule> dryRunMatches) {
		this.rule = rule;
		this.action = action;
		this.resolvedTarget = resolvedTarget;
		this.challengePlugin = challengePlugin;
		this.dryRunMatches = (null != dryRunMatches) ? dryRunMatches : Collections.<GuardRule>emptyList();
	}

	public static GuardOutcome terminal(GuardRule rule, String resolvedTarget, List<GuardRule> dryRunMatches) {
		return new GuardOutcome(rule, rule.getAction(), resolvedTarget, null, dryRunMatches);
	}

	/**
	 * @param action what is done instead of the rule's own action - a challenge rule's {@code else}, or the 429 a
	 *               challenge answers a request it cannot redirect
	 */
	public static GuardOutcome terminal(GuardRule rule, GuardAction action, String resolvedTarget,
			List<GuardRule> dryRunMatches) {
		return new GuardOutcome(rule, action, resolvedTarget, null, dryRunMatches);
	}

	/** A challenge: a redirect to {@code challengePlugin}'s page, at {@code target}. */
	public static GuardOutcome challenge(GuardRule rule, String target, String challengePlugin,
			List<GuardRule> dryRunMatches) {
		return new GuardOutcome(rule, rule.getAction(), target, challengePlugin, dryRunMatches);
	}

	public static GuardOutcome cont(List<GuardRule> dryRunMatches) {
		if (null == dryRunMatches || dryRunMatches.isEmpty())
			return CONTINUE;

		return new GuardOutcome(null, null, null, null, dryRunMatches);
	}

	public boolean isTerminal() {
		return null != rule;
	}

	public GuardRule getRule() {
		return rule;
	}

	/**
	 * @return what is done - the rule's action, except where a challenge rule fell back to its {@code else} action or
	 *         answered a non-GET with a 429; null for CONTINUE
	 */
	public GuardAction getAction() {
		return action;
	}

	/** @return the plugin a challenge sends the visitor to; null for every other outcome */
	public String getChallengePlugin() {
		return challengePlugin;
	}

	/**
	 * @return redirect target with placeholders expanded, or null for non-redirect outcomes
	 */
	public String getResolvedTarget() {
		return resolvedTarget;
	}

	public List<GuardRule> getDryRunMatches() {
		return dryRunMatches;
	}

}
