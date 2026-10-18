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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.frontcache.FCConfig;
import org.frontcache.guard.GuardConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the {@link WindowLimiter} of every rate-limit bucket, keyed by bucket name.
 *
 * <b>Process-scoped on purpose.</b> {@code FrontCacheEngine.reload()} constructs a new
 * GuardRuleEngine, and {@code reload-guard-rules} rebuilds the rule list; if the slot array lived in
 * the predicate, either would wipe every client's count - so an operator editing an unrelated rule
 * during an attack would hand every attacker a fresh window. This registry outlives both. Arrays
 * whose bucket is no longer referenced after a load are dropped by {@link #retain(Set)}, and an
 * array whose limit or window changed is rebuilt (its counts cannot be reinterpreted under a
 * different window).
 *
 * Static state is otherwise hostile to tests, so {@link #reset()} exists for them.
 */
public class GuardRateLimiterRegistry {

	public static final String SLOTS_PROPERTY = "front-cache.guard-rules.rate-limit.slots";
	public static final String MAX_TOTAL_SLOTS_PROPERTY = "front-cache.guard-rules.rate-limit.max-total-slots";
	public static final String ENABLED_PROPERTY = "front-cache.guard-rules.rate-limit.enabled";

	static final int DEFAULT_SLOTS = 262144;          // 2 MB
	static final int DEFAULT_MAX_TOTAL_SLOTS = 4194304; // 32 MB
	static final int MIN_SLOTS = 1024;

	private static Logger logger = LoggerFactory.getLogger(GuardRateLimiterRegistry.class);

	private static final Map<String, WindowLimiter> limiters = new ConcurrentHashMap<String, WindowLimiter>();

	private GuardRateLimiterRegistry() {
	}

	/**
	 * @return true unless the master switch is off. Read at load time - it is a property, so it
	 *         applies at the next restart, like every other property-level switch in the pipeline.
	 */
	public static boolean isEnabled() {
		return Boolean.parseBoolean(FCConfig.getProperty(ENABLED_PROPERTY, "true"));
	}

	/**
	 * The limiter for one bucket, created on first use and reused across reloads when its limit and
	 * window are unchanged - which is what makes counts survive a reload.
	 *
	 * @throws GuardConfigException when allocating it would exceed max-total-slots, so the offending
	 *                              rule is skipped with an error instead of the node running out of heap
	 */
	public static synchronized WindowLimiter limiter(RateLimitSpec spec)
	{
		String bucket = spec.getBucket();

		WindowLimiter existing = limiters.get(bucket);
		if (null != existing
				&& existing.getLimit() == spec.getLimit()
				&& existing.getWindowMillis() == spec.getWindowMillis())
			return existing;

		int slotCount = slotCount();

		// A bucket being rebuilt gives its slots back, so re-reading an unchanged file never drifts
		// towards the ceiling.
		long inUse = totalSlots() - ((null != existing) ? existing.getSlotCount() : 0);
		long maxTotal = maxTotalSlots();

		if (inUse + slotCount > maxTotal)
			throw new GuardConfigException("rate-limit bucket '" + bucket + "' would need " + slotCount
					+ " slots on top of " + inUse + " already allocated, over the " + maxTotal + " allowed by "
					+ MAX_TOTAL_SLOTS_PROPERTY);

		WindowLimiter limiter = new WindowLimiter(bucket, spec.getLimit(), spec.getWindowMillis(), slotCount);
		limiters.put(bucket, limiter);

		if (null != existing)
			logger.info("Rate-limit bucket '" + bucket + "' rebuilt for " + spec.getLimit() + " per "
					+ spec.getWindowMillis() + "ms - its counts were reset because the window changed");
		else
			logger.info("Rate-limit bucket '" + bucket + "' allocated: " + spec.getLimit() + " per "
					+ spec.getWindowMillis() + "ms, " + slotCount + " slots");

		return limiter;
	}

	/**
	 * Drops the limiters of buckets no longer named by any loaded rule. Called at the end of a load,
	 * so a deleted rule gives its memory back without a restart.
	 */
	public static synchronized void retain(Set<String> bucketsInUse)
	{
		if (null == bucketsInUse)
			return;

		for (Iterator<Map.Entry<String, WindowLimiter>> it = limiters.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<String, WindowLimiter> entry = it.next();
			if (!bucketsInUse.contains(entry.getKey()))
			{
				it.remove();
				logger.info("Rate-limit bucket '" + entry.getKey() + "' released - no rule refers to it any more");
			}
		}
	}

	public static Collection<WindowLimiter> getLimiters() {
		return new ArrayList<WindowLimiter>(limiters.values());
	}

	/** for tests */
	public static synchronized void reset() {
		limiters.clear();
	}

	private static long totalSlots()
	{
		long total = 0;
		for (WindowLimiter limiter : limiters.values())
			total += limiter.getSlotCount();

		return total;
	}

	/**
	 * @return configured slots per bucket, rounded up to a power of two (the limiter indexes with a
	 *         mask, so anything else would leave slots unreachable)
	 */
	static int slotCount()
	{
		int configured = intProperty(SLOTS_PROPERTY, DEFAULT_SLOTS);
		if (MIN_SLOTS > configured)
			configured = MIN_SLOTS;

		return Integer.highestOneBit(configured - 1) << 1;
	}

	static long maxTotalSlots() {
		return intProperty(MAX_TOTAL_SLOTS_PROPERTY, DEFAULT_MAX_TOTAL_SLOTS);
	}

	private static int intProperty(String key, int defaultValue)
	{
		String value = FCConfig.getProperty(key);
		if (null == value || 0 == value.trim().length())
			return defaultValue;

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			logger.error("Ignoring non-numeric " + key + "='" + value + "', using " + defaultValue);
			return defaultValue;
		}
	}

}
