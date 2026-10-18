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
package org.frontcache.guard.ratelimit;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardPredicate;

/**
 * The {@code rate:} predicate - true when this request's client IP has already used up its budget
 * for the current window.
 *
 * <b>This is the one guard predicate with a side effect</b>, and the pipeline assumes predicates
 * have none. Four consequences, each silent when it is wrong:
 *
 * <ol>
 * <li>It must be the LAST predicate of its rule, so the counter is fed only by requests the rest of
 *     the condition already matched. {@code GuardRuleParser} moves it there (the condition is an
 *     AND, so that is semantics-preserving) and refuses a rule holding two.</li>
 * <li>{@code !rate:} is refused at parse time - a negated counting predicate counts on the allowed
 *     path and then keeps evaluating.</li>
 * <li>A load-time probe context counts nothing. {@code GuardRuleEngine} calls
 *     {@code rule.matches(probe)} for redirect-loop detection and the exemption self-check, and
 *     those synthetic requests must not spend a real client's budget.</li>
 * <li>A dry-run rule DOES count. Dry-run is how a limit is sized before it enforces anything, so a
 *     dry-run rate rule that did not count would measure nothing.</li>
 * </ol>
 *
 * Counting also stops at {@code scope=toplevel} (the default): an include re-enters the engine
 * carrying the original visitor's address, so metering includes would spend a visitor's budget once
 * per fragment of the page they asked for.
 */
public class RateLimitPredicate implements GuardPredicate {

	private final RateLimitSpec spec;
	private final boolean enabled;

	private volatile WindowLimiter limiter; // resolved when the rule parser binds the bucket name

	public RateLimitPredicate(RateLimitSpec spec) {
		this.spec = spec;
		this.enabled = GuardRateLimiterRegistry.isEnabled();
	}

	/**
	 * Resolves the bucket (defaulting to the rule name) and its limiter. Called by
	 * {@code GuardRuleParser} once the rule name is known - the predicate factory does not know it.
	 *
	 * @return a predicate bound to its limiter
	 * @throws org.frontcache.guard.GuardConfigException when the bucket cannot be allocated
	 */
	public RateLimitPredicate bind(String ruleName)
	{
		RateLimitPredicate bound = new RateLimitPredicate(spec.withDefaultBucket(ruleName));
		bound.limiter = GuardRateLimiterRegistry.limiter(bound.spec);
		return bound;
	}

	public String getBucket() {
		return spec.getBucket();
	}

	public RateLimitSpec getSpec() {
		return spec;
	}

	@Override
	public boolean test(RequestContext context)
	{
		if (!enabled)
			return false;

		WindowLimiter current = limiter;
		if (null == current)
			return false; // unbound (a predicate built outside the rule parser) - meter nothing

		// A synthetic probe is not traffic. This has to be checked here rather than by the engine's
		// countHits flag, which matches() never sees.
		if (context.isGuardProbe())
			return false;

		if (spec.isToplevelOnly() && !FCHeaders.COMPONENT_TOPLEVEL.equals(context.getRequestType()))
			return false;

		String key = context.getRateLimitKey();
		if (null == key)
			return false; // address undecidable (recycled request, unparseable header) - fail open

		long retryMillis = current.checkAndCount(WindowLimiter.hash(spec.getBucket(), key), WindowLimiter.nowMillis());
		if (0 == retryMillis)
			return false;

		// carried to applyGuardAction, which turns it into Retry-After on the 429
		context.setGuardRetryAfterSeconds((int) Math.max(1, (retryMillis + 999) / 1000));
		return true;
	}

	@Override
	public String describe() {
		return spec.getSource();
	}

}
