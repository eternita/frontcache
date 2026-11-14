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
package org.frontcache.plugin.host;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.frontcache.guard.GuardAction;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.ratelimit.GuardRateLimiterRegistry;
import org.frontcache.guard.ratelimit.WindowLimiter;
import org.frontcache.plugin.event.GuardSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a {@link GuardSummary} every interval, from counters the guard keeps anyway: each rule's hit count
 * and each rate-limit bucket's over-limit count. Deltas are taken per object, so a rule a reload replaced starts
 * from zero, as its counter does.
 */
final class GuardSummaryTicker {

	private static final Logger logger = LoggerFactory.getLogger(GuardSummaryTicker.class);

	static final String DISPOSITION_ALLOWED = "allowed";

	private final Supplier<List<GuardRule>> rules;
	private final long intervalMillis;
	private final ScheduledExecutorService executor;

	private Map<GuardRule, Long> lastHits = new IdentityHashMap<>();
	private Map<WindowLimiter, Long> lastOverLimit = new IdentityHashMap<>();

	GuardSummaryTicker(Supplier<List<GuardRule>> rules, long intervalMillis) {
		this.rules = rules;
		this.intervalMillis = intervalMillis;
		this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "fc-guard-summary");
			t.setDaemon(true);
			return t;
		});
	}

	void start() {
		summarize(); // the baseline: the first summary covers the first interval, not the time since start
		executor.scheduleAtFixedRate(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	void stop() {
		executor.shutdownNow();
	}

	private void tick() {
		try {
			GuardSummary summary = summarize();
			if (null != summary)
				FcEventBus.publish(summary);
		} catch (Throwable t) {
			logger.warn("Could not build the guard summary: " + t);
		}
	}

	/** @return the counts since the previous call */
	synchronized GuardSummary summarize() {

		List<GuardRule> current = rules.get();
		List<GuardSummary.RuleCount> ruleCounts = new ArrayList<>();
		Map<GuardRule, Long> hits = new IdentityHashMap<>();
		if (null != current)
			for (GuardRule rule : current)
			{
				long now = rule.getHits();
				hits.put(rule, now);
				ruleCounts.add(new GuardSummary.RuleCount(rule.getName(), disposition(rule),
						now - lastHits.getOrDefault(rule, 0L)));
			}
		lastHits = hits;

		List<GuardSummary.BucketCount> bucketCounts = new ArrayList<>();
		Map<WindowLimiter, Long> overLimit = new IdentityHashMap<>();
		for (WindowLimiter limiter : GuardRateLimiterRegistry.getLimiters())
		{
			long now = limiter.getOverLimitCount();
			overLimit.put(limiter, now);
			bucketCounts.add(new GuardSummary.BucketCount(limiter.getBucket(), limiter.getLimit(),
					limiter.getWindowMillis(), now - lastOverLimit.getOrDefault(limiter, 0L)));
		}
		lastOverLimit = overLimit;

		return new GuardSummary(System.currentTimeMillis(), CoreEvents.node(), CoreEvents.site(), intervalMillis,
				ruleCounts, bucketCounts);
	}

	static String disposition(GuardRule rule) {
		if (rule.isDryRun())
			return GuardAction.DISPOSITION_DRY_RUN;
		GuardAction action = rule.getAction();
		// a challenge whose provider is absent acts as its else action, or as a dry run - say what it does now
		if (GuardAction.Type.CHALLENGE == action.getType() && null == PluginEndpoints.challenge(action.getProvider()))
		{
			if (null == action.getOtherwise())
				return GuardAction.DISPOSITION_DRY_RUN;
			action = action.getOtherwise();
		}
		String disposition = action.getDisposition();
		return (null == disposition) ? DISPOSITION_ALLOWED : disposition;
	}
}
