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
import java.util.concurrent.atomic.LongAdder;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;

/**
 * A guard rule: name | condition | action.
 *
 * The name is the rule's identity everywhere else - it is written to the failed-requests log as
 * the reason and becomes the metric in the Kibana "Frontcache Rejected Requests" dashboard.
 * Renaming a rule renames the metric.
 */
public class GuardRule {

	private final String name;
	private final List<GuardPredicate> predicates;
	private final GuardAction action;
	private final boolean dryRun;
	private final boolean onErrorAllow;
	private final boolean trap;

	// hit count since start; in-memory only, reset on restart (by design)
	private final LongAdder hits = new LongAdder();

	// a challenge rule whose provider was found absent has said so - once per rule object, i.e. once per reload
	private final java.util.concurrent.atomic.AtomicBoolean unavailableReported = new java.util.concurrent.atomic.AtomicBoolean();

	public GuardRule(String name, List<GuardPredicate> predicates, GuardAction action, boolean dryRun) {
		this(name, predicates, action, dryRun, false);
	}

	/**
	 * @param onErrorAllow the rule says {@code on-error=allow}: an {@code allow} rule naming a plugin's predicate,
	 *                     whose author accepted that the exemption does not apply when the predicate has no answer
	 */
	public GuardRule(String name, List<GuardPredicate> predicates, GuardAction action, boolean dryRun, boolean onErrorAllow) {
		this(name, predicates, action, dryRun, onErrorAllow, false);
	}

	/**
	 * @param trap the rule says {@code trap}: a honeypot, whose hit identifies the client - it matches a client's
	 *             request only (never an {@code <fc:include>} re-entering the engine, which carries the visitor's
	 *             address but is the origin's page asking, not the visitor), and its log line records the address
	 *             this node resolved (see {@link #logReason})
	 */
	public GuardRule(String name, List<GuardPredicate> predicates, GuardAction action, boolean dryRun, boolean onErrorAllow,
			boolean trap) {
		this.name = name;
		this.predicates = Collections.unmodifiableList(predicates);
		this.action = action;
		this.dryRun = dryRun;
		this.onErrorAllow = onErrorAllow;
		this.trap = trap;
	}

	/**
	 * @return true when EVERY predicate holds (predicates are AND-ed, evaluation short-circuits) - and, for a trap, the
	 *         request is a client's
	 */
	public boolean matches(RequestContext context)
	{
		if (trap && !isClientRequest(context))
			return false;

		try {
			for (GuardPredicate predicate : predicates)
				if (!predicate.test(context))
					return false;
		} catch (PredicateUndecided e) {
			return false; // a plugin's predicate had no answer: the rule does not match, negated or not
		}

		return true;
	}

	public String getName() {
		return name;
	}

	public List<GuardPredicate> getPredicates() {
		return predicates;
	}

	public GuardAction getAction() {
		return action;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	/** {@code | on-error=allow} - see the constructor. */
	public boolean isOnErrorAllow() {
		return onErrorAllow;
	}

	/** {@code | trap} - see the constructor. */
	public boolean isTrap() {
		return trap;
	}

	/**
	 * A client's request: top-level. A request with no type is a load-time probe (the exemption warnings, the redirect
	 * loop check), which must see a trap rule as it would see any other.
	 */
	private static boolean isClientRequest(RequestContext context)
	{
		String requestType = context.getRequestType();
		return null == requestType || FCHeaders.COMPONENT_TOPLEVEL.equals(requestType);
	}

	/**
	 * The reason column of this rule's guard log line: the rule name ({@code <rule>:dry-run} for a dry-run match), and
	 * the client address this node resolved as {@code <rule>#<address>} - for a rate-limit rule that refused the
	 * request, and for a trap, acted on or dry-run.
	 *
	 * The address is the one the limiter counts and a {@code client-ip:} rule matches: a forwarding header is read
	 * only from a configured trusted proxy. The log's own client-IP column is best effort - the first forwarding
	 * header, whoever wrote it - so the two CAN disagree, and for the two kinds of rule whose whole point is WHICH
	 * client, the log records the one the node would act on. "Which address was counted?" is the first question
	 * anyone debugging a 429 asks; "who fell into the trap?" is the only question a trap answers, and an address a
	 * scanner can write itself would let it frame anyone. A rate rule's dry-run line keeps the plain reason it always
	 * had.
	 */
	public String logReason(RequestContext context, boolean dryRunMatch)
	{
		String reason = dryRunMatch ? name + ":dry-run" : name;
		boolean withAddress = trap || (!dryRunMatch && 0 < context.getGuardRetryAfterSeconds());
		if (!withAddress)
			return reason;

		String address = context.getRateLimitKey();
		return (null != address) ? reason + "#" + address : reason;
	}

	public long getHits() {
		return hits.sum();
	}

	public void countHit() {
		hits.increment();
	}

	/** @return true the first time it is called for this rule object, false after */
	boolean firstUnavailableReport() {
		return unavailableReported.compareAndSet(false, true);
	}

	public String getCondition()
	{
		StringBuilder sb = new StringBuilder();
		for (GuardPredicate predicate : predicates)
		{
			if (0 < sb.length())
				sb.append(" ; ");
			sb.append(predicate.describe());
		}
		return sb.toString();
	}

	/**
	 * @return flat, JSON-friendly snapshot of this rule for the management API and the console
	 */
	public GuardRuleEntry toEntry() {
		return new GuardRuleEntry(name, getCondition(), action.toString(), dryRun, getHits());
	}

	@Override
	public String toString() {
		return name + " | " + getCondition() + " | " + action + (dryRun ? " | dry-run" : "")
				+ (onErrorAllow ? " | " + GuardRuleParser.ON_ERROR_ALLOW : "") + (trap ? " | " + GuardRuleParser.TRAP : "");
	}

}
