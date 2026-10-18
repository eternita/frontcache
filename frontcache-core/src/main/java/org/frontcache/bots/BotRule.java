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
package org.frontcache.bots;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardPredicate;

/**
 * One classification rule from conf/bots.conf: name | condition | verdict [| dry-run].
 *
 * The verdict is a CLIENT TYPE - {@code bot} or {@code guest}, nothing else. It is not a judgement
 * about the client: it selects a behaviour profile (which branch of a WebResponse's expireTimeMap
 * applies, so a different TTL or a cached response vs. an origin call, and whether an
 * {@code <fc:include client="...">} fragment is resolved). Guard rules can read it back through
 * their {@code client-type:} predicate, but that is one consumer among several, not the purpose.
 *
 * Deliberately shaped like {@link org.frontcache.guard.GuardRule}, hit counter included: an
 * operator who has read the Guard Rules screen should need no second explanation for this one.
 */
public class BotRule {

	private final String name;
	private final List<GuardPredicate> predicates;
	private final String clientType;
	private final boolean dryRun;

	// hit count since load; in-memory only, reset on restart and on reload-bots (by design)
	private final LongAdder hits = new LongAdder();

	public BotRule(String name, List<GuardPredicate> predicates, String clientType, boolean dryRun) {
		this.name = name;
		this.predicates = Collections.unmodifiableList(predicates);
		this.clientType = clientType;
		this.dryRun = dryRun;
	}

	/**
	 * @return true when EVERY predicate holds (predicates are AND-ed, evaluation short-circuits)
	 */
	public boolean matches(RequestContext context)
	{
		for (GuardPredicate predicate : predicates)
			if (!predicate.test(context))
				return false;

		return true;
	}

	public String getName() {
		return name;
	}

	public List<GuardPredicate> getPredicates() {
		return predicates;
	}

	/** {@code bot} or {@code guest} */
	public String getClientType() {
		return clientType;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public long getHits() {
		return hits.sum();
	}

	public void countHit() {
		hits.increment();
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
	public BotRuleEntry toEntry() {
		return new BotRuleEntry(name, getCondition(), clientType, dryRun, getHits());
	}

	@Override
	public String toString() {
		return name + " | " + getCondition() + " | " + clientType + (dryRun ? " | dry-run" : "");
	}

}
