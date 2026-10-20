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

import java.time.Duration;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

/**
 * The resolved settings for one command key, read from conf/resilience.properties by
 * {@link FcResilienceConfig} and translated to Resilience4j configs here.
 *
 * The Hystrix property names and their semantics are preserved verbatim - the property names outlived
 * the library, and
 * its keys, so an operator's existing tuning carries over untouched.
 */
public class FcCommandConfig {

	public enum Isolation { THREAD, SEMAPHORE }

	private final String commandKey;
	private final Isolation isolation;
	private final int timeoutMs;
	private final boolean interruptOnTimeout;
	private final int semaphoreMaxConcurrentRequests;
	private final int fallbackSemaphoreMaxConcurrentRequests;
	private final int errorThresholdPercentage;
	private final int requestVolumeThreshold;
	private final int sleepWindowMs;
	private final int rollingStatsMs;
	private final String threadPoolKey;

	FcCommandConfig(String commandKey, Isolation isolation, int timeoutMs, boolean interruptOnTimeout,
			int semaphoreMaxConcurrentRequests, int fallbackSemaphoreMaxConcurrentRequests,
			int errorThresholdPercentage, int requestVolumeThreshold, int sleepWindowMs, int rollingStatsMs,
			String threadPoolKey) {
		this.commandKey = commandKey;
		this.isolation = isolation;
		this.timeoutMs = timeoutMs;
		this.interruptOnTimeout = interruptOnTimeout;
		this.semaphoreMaxConcurrentRequests = semaphoreMaxConcurrentRequests;
		this.fallbackSemaphoreMaxConcurrentRequests = fallbackSemaphoreMaxConcurrentRequests;
		this.errorThresholdPercentage = errorThresholdPercentage;
		this.requestVolumeThreshold = requestVolumeThreshold;
		this.sleepWindowMs = sleepWindowMs;
		this.rollingStatsMs = rollingStatsMs;
		this.threadPoolKey = threadPoolKey;
	}

	public String getCommandKey() { return commandKey; }
	public Isolation getIsolation() { return isolation; }
	public int getTimeoutMs() { return timeoutMs; }
	public boolean isInterruptOnTimeout() { return interruptOnTimeout; }
	public int getSemaphoreMaxConcurrentRequests() { return semaphoreMaxConcurrentRequests; }
	public int getErrorThresholdPercentage() { return errorThresholdPercentage; }
	public int getRequestVolumeThreshold() { return requestVolumeThreshold; }
	public int getSleepWindowMs() { return sleepWindowMs; }
	public int getRollingStatsMs() { return rollingStatsMs; }
	public String getThreadPoolKey() { return threadPoolKey; }

	/**
	 * Parsed but NOT enforced - reported on the dashboard only, so an existing resilience.properties
	 * round-trips and the {@code propertyValue_fallbackIsolationSemaphoreMaxConcurrentRequests} field
	 * the dashboard asserts keeps carrying the operator's value.
	 */
	public int getFallbackSemaphoreMaxConcurrentRequests() { return fallbackSemaphoreMaxConcurrentRequests; }

	/**
	 * Two Resilience4j defaults have to be overridden or production behavior changes silently:
	 * permittedNumberOfCallsInHalfOpenState (R4j 10, Hystrix probes with exactly 1) and the exception
	 * exclusions below.
	 *
	 * The exclusions are the non-obvious half. Resilience4j's decorators record every exception the
	 * decorated call throws, including the rejections thrown by the bulkhead and the thread pool that
	 * sit inside the breaker. Hystrix counts neither SEMAPHORE_REJECTED nor THREAD_POOL_REJECTED
	 * against the error percentage. Without these exclusions a saturated node would open its own
	 * circuits purely because it was busy - a load-shedding spiral that is entirely an artifact of the
	 * library swap.
	 */
	public CircuitBreakerConfig circuitBreakerConfig()
	{
		return CircuitBreakerConfig.custom()
				.failureRateThreshold(errorThresholdPercentage)
				.minimumNumberOfCalls(requestVolumeThreshold)
				.slidingWindowType(SlidingWindowType.TIME_BASED)
				.slidingWindowSize(Math.max(1, rollingStatsMs / 1000))
				.waitDurationInOpenState(Duration.ofMillis(sleepWindowMs))
				.permittedNumberOfCallsInHalfOpenState(1)
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				.ignoreExceptions(FcBadRequestException.class,
						io.github.resilience4j.bulkhead.BulkheadFullException.class,
						java.util.concurrent.RejectedExecutionException.class)
				.build();
	}

	public TimeLimiterConfig timeLimiterConfig()
	{
		return TimeLimiterConfig.custom()
				.timeoutDuration(Duration.ofMillis(timeoutMs))
				.cancelRunningFuture(interruptOnTimeout)
				.build();
	}

	/**
	 * maxWaitDuration is ZERO on purpose: Hystrix's semaphore is a non-blocking tryAcquire, so a
	 * non-zero wait here would silently turn rejections into queued waits. It defaults to zero in
	 * Resilience4j 2.x, and is set explicitly rather than assumed.
	 */
	public BulkheadConfig bulkheadConfig()
	{
		return BulkheadConfig.custom()
				.maxConcurrentCalls(semaphoreMaxConcurrentRequests)
				.maxWaitDuration(Duration.ZERO)
				.build();
	}
}
