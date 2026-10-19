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
package org.frontcache.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

/**
 * Holds the Resilience4j registries, keyed by command key.
 *
 * Separate from {@link FcCommand} so the metrics stream can read circuit state without constructing a
 * command, and separate from {@link FcCommandMetrics} because the two have different lifetimes: the
 * registries are configuration, the metrics are a rolling window.
 *
 * Registry entries are named by command key alone, which is only correct because one node serves one
 * site. Before multi-domain support was retired, Hystrix's per-commandKey circuit breakers were shared
 * across every domain on an edge - one tenant's failing origin short-circuited every other tenant's
 * healthy one. That fault is now structurally impossible rather than fixed. Should multi-tenancy ever
 * return, entries would need a composite (domain, command key) name.
 */
public class FcResilienceRegistry {

	private static final CircuitBreakerRegistry CIRCUIT_BREAKERS = CircuitBreakerRegistry.ofDefaults();
	private static final BulkheadRegistry BULKHEADS = BulkheadRegistry.ofDefaults();
	private static final TimeLimiterRegistry TIME_LIMITERS = TimeLimiterRegistry.ofDefaults();

	private FcResilienceRegistry() { }

	public static CircuitBreaker circuitBreaker(FcCommandConfig config)
	{
		return CIRCUIT_BREAKERS.circuitBreaker(config.getCommandKey(), config.circuitBreakerConfig());
	}

	public static Bulkhead bulkhead(FcCommandConfig config)
	{
		return BULKHEADS.bulkhead(config.getCommandKey(), config.bulkheadConfig());
	}

	public static TimeLimiter timeLimiter(FcCommandConfig config)
	{
		return TIME_LIMITERS.timeLimiter(config.getCommandKey(), config.timeLimiterConfig());
	}

	/**
	 * The registries themselves, for the Micrometer binders in {@code org.frontcache.metrics}. Exposed
	 * rather than re-wrapped: resilience4j-micrometer binds to a registry and picks up entries added
	 * later, which is what makes a command that starts serving after startup show up on its own.
	 */
	public static CircuitBreakerRegistry circuitBreakerRegistry()
	{
		return CIRCUIT_BREAKERS;
	}

	public static BulkheadRegistry bulkheadRegistry()
	{
		return BULKHEADS;
	}

	public static TimeLimiterRegistry timeLimiterRegistry()
	{
		return TIME_LIMITERS;
	}

	/**
	 * The breaker for a command key if one was ever created, else null. The metrics stream uses this
	 * for {@code isCircuitBreakerOpen} - reading live state rather than tracking a copy.
	 */
	public static CircuitBreaker existingCircuitBreaker(String commandKey)
	{
		return CIRCUIT_BREAKERS.find(commandKey).orElse(null);
	}

	/**
	 * Forgets every breaker, bulkhead and time limiter, so the next command builds its own from the
	 * config that is current then.
	 *
	 * <b>This drops circuit state.</b> An open breaker snaps closed and every rolling window restarts
	 * at zero, which is visible on the realtime page as a step change. That is the cost of applying a
	 * changed resilience.properties without a restart, and {@link FcResilienceReload} reports it rather
	 * than leaving the operator to infer it - a breaker whose configuration just changed has no
	 * meaningful accumulated history anyway.
	 */
	public static void invalidateAll()
	{
		reset();
	}

	/** Test seam - drops accumulated state so each test starts from a closed circuit. */
	static void reset()
	{
		for (CircuitBreaker breaker : CIRCUIT_BREAKERS.getAllCircuitBreakers())
			CIRCUIT_BREAKERS.remove(breaker.getName());
		for (Bulkhead bulkhead : BULKHEADS.getAllBulkheads())
			BULKHEADS.remove(bulkhead.getName());
		for (TimeLimiter timeLimiter : TIME_LIMITERS.getAllTimeLimiters())
			TIME_LIMITERS.remove(timeLimiter.getName());
	}
}
