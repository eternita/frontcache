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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Behavior of the seam, against the acceptance criteria of the Resilience4j migration.
 *
 * Every test uses its own command key: the Resilience4j registries and {@link FcCommandMetrics} are
 * keyed by that name and shared JVM-wide, exactly as Hystrix's are, so reusing a key would leak circuit
 * state between tests.
 */
public class FcCommandTest {

	private static final String GROUP = "test.example.com";

	@After
	public void tearDown()
	{
		FcResilienceRegistry.reset();
		FcCommandMetrics.reset();
		FcThreadPools.shutdownAll();
		FcResilienceConfig.reset();
	}

	private static void configure(String commandKey, String... keyValues)
	{
		Properties props = new Properties();
		for (int i = 0; i < keyValues.length; i += 2)
			props.setProperty("hystrix.command." + commandKey + "." + keyValues[i], keyValues[i + 1]);
		FcResilienceConfig.install(props);
	}

	// ---- circuit breaker ----------------------------------------------------

	@Test
	public void circuitOpensAtTheVolumeThresholdAndNotBefore()
	{
		String key = "Circuit-Opens";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"circuitBreaker.requestVolumeThreshold", "4",
				"circuitBreaker.errorThresholdPercentage", "50");

		for (int i = 0; i < 3; i++)
			assertEquals("fallback", new FailingCommand(key).execute());

		assertEquals("3 failures is below requestVolumeThreshold=4",
				CircuitBreaker.State.CLOSED, FcResilienceRegistry.existingCircuitBreaker(key).getState());

		assertEquals("fallback", new FailingCommand(key).execute());

		assertEquals("the 4th failure reaches the threshold",
				CircuitBreaker.State.OPEN, FcResilienceRegistry.existingCircuitBreaker(key).getState());

		FailingCommand shortCircuited = new FailingCommand(key);
		shortCircuited.execute();
		assertTrue("subsequent calls are short-circuited, not executed",
				shortCircuited.getExecutionEvents().contains(FcEventType.SHORT_CIRCUITED));
		assertEquals("run() is not entered while the circuit is open", 0, shortCircuited.runCount.get());
	}

	/**
	 * Resilience4j permits 10 half-open probes by default. Hystrix probes with exactly one, and ten
	 * concurrent calls into an origin that has just come back up is how it goes down again.
	 */
	@Test
	public void halfOpenLetsExactlyOneProbeThrough() throws Exception
	{
		String key = "Half-Open-Probe";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"circuitBreaker.requestVolumeThreshold", "2",
				"circuitBreaker.errorThresholdPercentage", "50",
				"circuitBreaker.sleepWindowInMilliseconds", "200");

		for (int i = 0; i < 2; i++)
			new FailingCommand(key).execute();
		assertEquals(CircuitBreaker.State.OPEN, FcResilienceRegistry.existingCircuitBreaker(key).getState());

		Thread.sleep(300);

		FailingCommand probe = new FailingCommand(key);
		probe.execute();
		assertEquals("the first call after the sleep window is the probe and does run", 1, probe.runCount.get());

		FailingCommand afterProbe = new FailingCommand(key);
		afterProbe.execute();
		assertEquals("the probe failed, so the circuit re-opened and the next call does not run",
				0, afterProbe.runCount.get());
		assertTrue(afterProbe.getExecutionEvents().contains(FcEventType.SHORT_CIRCUITED));
	}

	@Test
	public void badRequestIsRethrownAndDoesNotMoveTheFailureRate()
	{
		String key = "Bad-Request";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"circuitBreaker.requestVolumeThreshold", "2",
				"circuitBreaker.errorThresholdPercentage", "50");

		for (int i = 0; i < 5; i++)
		{
			BadRequestCommand command = new BadRequestCommand(key);
			try {
				command.execute();
				fail("FcBadRequestException must reach the caller");
			} catch (FcBadRequestException expected) {
				assertTrue(command.getExecutionEvents().contains(FcEventType.BAD_REQUEST));
				assertFalse("no fallback is resolved for a bad request",
						command.getExecutionEvents().contains(FcEventType.FALLBACK_SUCCESS));
			}
		}

		assertEquals("5 bad requests must leave the circuit closed",
				CircuitBreaker.State.CLOSED, FcResilienceRegistry.existingCircuitBreaker(key).getState());
	}

	// ---- isolation ----------------------------------------------------------

	@Test
	public void semaphoreRejectsImmediatelyRatherThanQueuing() throws Exception
	{
		final String key = "Semaphore-Reject";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"execution.isolation.semaphore.maxConcurrentRequests", "1");

		final CountDownLatch holderStarted = new CountDownLatch(1);
		final CountDownLatch releaseHolder = new CountDownLatch(1);

		Thread holder = new Thread(new Runnable() {
			@Override
			public void run()
			{
				new BlockingCommand(key, holderStarted, releaseHolder).execute();
			}
		});
		holder.start();
		assertTrue("holder did not start", holderStarted.await(5, TimeUnit.SECONDS));

		try {
			BlockingCommand rejected = new BlockingCommand(key, new CountDownLatch(1), new CountDownLatch(0));
			long startedAt = System.currentTimeMillis();
			rejected.execute();
			long elapsed = System.currentTimeMillis() - startedAt;

			assertTrue("the only permit is taken, so this is a semaphore rejection",
					rejected.getExecutionEvents().contains(FcEventType.SEMAPHORE_REJECTED));
			assertEquals("rejected before run() was entered", 0, rejected.runCount.get());
			assertTrue("rejection must be immediate, not a queued wait (" + elapsed + "ms)", elapsed < 1000);
		} finally {
			releaseHolder.countDown();
			holder.join(5000);
		}
	}

	@Test
	public void threadPoolWithoutAQueueRejectsWhenSaturated() throws Exception
	{
		final String key = "Pool-Reject";
		Properties props = new Properties();
		props.setProperty("hystrix.command." + key + ".execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.command." + key + ".execution.isolation.thread.timeoutInMilliseconds", "10000");
		props.setProperty("hystrix.threadpool.RejectPool.coreSize", "1");
		props.setProperty("hystrix.threadpool.RejectPool.maxQueueSize", "-1");
		FcResilienceConfig.install(props);

		final CountDownLatch holderStarted = new CountDownLatch(1);
		final CountDownLatch releaseHolder = new CountDownLatch(1);

		Thread holder = new Thread(new Runnable() {
			@Override
			public void run()
			{
				new BlockingCommand(key, "RejectPool", holderStarted, releaseHolder).execute();
			}
		});
		holder.start();
		assertTrue("holder did not start", holderStarted.await(5, TimeUnit.SECONDS));

		try {
			BlockingCommand rejected = new BlockingCommand(key, "RejectPool", new CountDownLatch(1), new CountDownLatch(0));
			long startedAt = System.currentTimeMillis();
			rejected.execute();
			long elapsed = System.currentTimeMillis() - startedAt;

			assertTrue("the single thread is busy and there is no queue",
					rejected.getExecutionEvents().contains(FcEventType.THREAD_POOL_REJECTED));
			assertTrue("rejection must be immediate, not queued behind the busy thread (" + elapsed + "ms)",
					elapsed < 1000);
		} finally {
			releaseHolder.countDown();
			holder.join(5000);
		}
	}

	@Test
	public void threadPathTimesOutAndResolvesTheFallback()
	{
		String key = "Thread-Timeout";
		Properties props = new Properties();
		props.setProperty("hystrix.command." + key + ".execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.command." + key + ".execution.isolation.thread.timeoutInMilliseconds", "200");
		props.setProperty("hystrix.threadpool.TimeoutPool.coreSize", "2");
		FcResilienceConfig.install(props);

		SlowCommand command = new SlowCommand(key, "TimeoutPool", 2000);

		long startedAt = System.currentTimeMillis();
		assertEquals("fallback", command.execute());
		long elapsed = System.currentTimeMillis() - startedAt;

		assertTrue(command.getExecutionEvents().contains(FcEventType.TIMEOUT));
		assertTrue("unlike the semaphore path, the thread path actually bounds the latency ("
				+ elapsed + "ms against a 200ms budget)", elapsed < 1500);
		assertNull("getFailedExecutionException() stays null for a timeout, as in Hystrix",
				command.getFailedExecutionException());
	}

	// ---- fallbacks ----------------------------------------------------------

	@Test
	public void failureExposesTheOriginalExceptionToTheFallback()
	{
		String key = "Failure-Exception";
		configure(key, "execution.isolation.strategy", "SEMAPHORE");

		FailingCommand command = new FailingCommand(key);
		command.execute();

		assertTrue(command.getExecutionEvents().contains(FcEventType.FAILURE));
		assertNotNull("run()'s exception is what getFailedExecutionException() returns",
				command.getFailedExecutionException());
		assertEquals("origin down", command.getFailedExecutionException().getMessage());
		assertEquals("failure event is recorded before the fallback runs, so failureType() sees it",
				FcEventType.FAILURE, command.eventsSeenByFallback.get(0));
	}

	@Test
	public void checkedExceptionFromRunIsNotWrappedInWhatTheFallbackSees()
	{
		String key = "Checked-Exception";
		configure(key, "execution.isolation.strategy", "SEMAPHORE");

		CheckedFailureCommand command = new CheckedFailureCommand(key);
		command.execute();

		assertTrue(command.getFailedExecutionException() instanceof java.io.IOException);
		assertEquals("cannot read origin", command.getFailedExecutionException().getMessage());
	}

	/**
	 * The direct regression test for the "in multi threaded environment hystrix does not resolve
	 * fallback ??" comment in ConcurrentIncludeProcessor. getFallback() is
	 * invoked from execute()'s own catch block, on the calling thread, with no subscription semantics
	 * in between - so it runs every time, including when many threads fail at once.
	 */
	@Test
	public void fallbackAlwaysResolvesUnderConcurrentFailures() throws Exception
	{
		final String key = "Concurrent-Fallback";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"execution.isolation.semaphore.maxConcurrentRequests", "50",
				// keep the circuit out of it: this is about the fallback, not about breaking
				"circuitBreaker.errorThresholdPercentage", "100");

		int threads = 30;
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);
		final AtomicInteger fallbacksResolved = new AtomicInteger(0);
		final AtomicInteger escaped = new AtomicInteger(0);

		for (int i = 0; i < threads; i++)
			new Thread(new Runnable() {
				@Override
				public void run()
				{
					try {
						start.await();
						if ("fallback".equals(new FailingCommand(key).execute()))
							fallbacksResolved.incrementAndGet();
					} catch (Throwable t) {
						escaped.incrementAndGet();
					} finally {
						done.countDown();
					}
				}
			}).start();

		start.countDown();
		assertTrue(done.await(15, TimeUnit.SECONDS));

		assertEquals("no failure escaped as an exception instead of yielding its fallback", 0, escaped.get());
		assertEquals("every one of " + threads + " concurrent failures resolved its fallback",
				threads, fallbacksResolved.get());
	}

	@Test
	public void withoutAFallbackTheFailureIsThrownAsFcCommandException()
	{
		String key = "No-Fallback";
		configure(key, "execution.isolation.strategy", "SEMAPHORE");

		try {
			new NoFallbackCommand(key).execute();
			fail("expected FcCommandException");
		} catch (FcCommandException e) {
			assertEquals(key, e.getCommandKey());
			assertEquals(FcEventType.FAILURE, e.getFailureType());
			assertEquals("origin down", e.getCause().getMessage());
		}
	}

	// ---- the watchdog -----------------------------------------------------

	/**
	 * The contrast with {@link HystrixSemaphoreTimeoutMeasurementTest}: Hystrix hands the caller a
	 * fallback at the budget while run() keeps going on the caller's thread and its completed work is
	 * discarded. The watchdog reports and gets out of the way - the real value is returned, nothing is
	 * counted as a timeout, and the circuit does not move.
	 */
	@Test
	public void watchdogReportsOverrunsWithoutAbortingOrFailing() throws Exception
	{
		String key = "Watchdog";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"execution.isolation.thread.timeoutInMilliseconds", "100",
				"circuitBreaker.requestVolumeThreshold", "1");

		SlowCommand command = new SlowCommand(key, null, 800);

		long startedAt = System.currentTimeMillis();
		String result = command.execute();
		long elapsed = System.currentTimeMillis() - startedAt;

		assertEquals("the real value is returned, not a fallback", "slow-but-fine", result);
		assertTrue("nothing was aborted (" + elapsed + "ms)", elapsed >= 800);
		assertTrue(command.getExecutionEvents().contains(FcEventType.SUCCESS));
		assertFalse("an overrun is not a timeout", command.getExecutionEvents().contains(FcEventType.TIMEOUT));
		assertEquals("and not a failure", CircuitBreaker.State.CLOSED,
				FcResilienceRegistry.existingCircuitBreaker(key).getState());

		FcCommandMetrics metrics = FcCommandMetrics.existing(key);
		assertEquals("the overrun is reported on its own counter, once",
				1, metrics.getRollingCount(FcEventType.BUDGET_OVERRUN_OBSERVED));
		assertEquals("and never as a timeout, which would move errorPercentage and could open a circuit",
				0, metrics.getRollingCount(FcEventType.TIMEOUT));
		assertEquals(0, metrics.getErrorPercentage());
	}

	@Test
	public void watchdogStaysQuietWithinBudget() throws Exception
	{
		String key = "Watchdog-Quiet";
		configure(key,
				"execution.isolation.strategy", "SEMAPHORE",
				"execution.isolation.thread.timeoutInMilliseconds", "2000");

		assertEquals("slow-but-fine", new SlowCommand(key, null, 400).execute());

		assertEquals(0, FcCommandMetrics.existing(key).getRollingCount(FcEventType.BUDGET_OVERRUN_OBSERVED));
	}

	// ---- fixtures -----------------------------------------------------------

	private static class FailingCommand extends FcCommand<String> {

		private final AtomicInteger runCount = new AtomicInteger(0);
		private final java.util.List<FcEventType> eventsSeenByFallback = new java.util.ArrayList<FcEventType>();

		FailingCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			runCount.incrementAndGet();
			throw new RuntimeException("origin down");
		}

		@Override
		protected String getFallback()
		{
			eventsSeenByFallback.addAll(getExecutionEvents());
			return "fallback";
		}
	}

	private static class CheckedFailureCommand extends FcCommand<String> {

		CheckedFailureCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run() throws Exception
		{
			throw new java.io.IOException("cannot read origin");
		}

		@Override
		protected String getFallback()
		{
			return "fallback";
		}
	}

	private static class NoFallbackCommand extends FcCommand<String> {

		NoFallbackCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			throw new RuntimeException("origin down");
		}
	}

	private static class BadRequestCommand extends FcCommand<String> {

		BadRequestCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			throw new FcBadRequestException("malformed url");
		}

		@Override
		protected String getFallback()
		{
			return "fallback";
		}
	}

	private static class SlowCommand extends FcCommand<String> {

		private final long durationMs;

		SlowCommand(String commandKey, String poolKey, long durationMs) {
			super(null == poolKey
					? Setter.withGroupKey(GROUP).andCommandKey(commandKey)
					: Setter.withGroupKey(GROUP).andCommandKey(commandKey).andThreadPoolKey(poolKey));
			this.durationMs = durationMs;
		}

		@Override
		protected String run() throws Exception
		{
			Thread.sleep(durationMs);
			return "slow-but-fine";
		}

		@Override
		protected String getFallback()
		{
			return "fallback";
		}
	}

	private static class BlockingCommand extends FcCommand<String> {

		private final AtomicInteger runCount = new AtomicInteger(0);
		private final CountDownLatch started;
		private final CountDownLatch release;

		BlockingCommand(String commandKey, CountDownLatch started, CountDownLatch release) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
			this.started = started;
			this.release = release;
		}

		BlockingCommand(String commandKey, String poolKey, CountDownLatch started, CountDownLatch release) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey).andThreadPoolKey(poolKey));
			this.started = started;
			this.release = release;
		}

		@Override
		protected String run() throws Exception
		{
			runCount.incrementAndGet();
			started.countDown();
			release.await(10, TimeUnit.SECONDS);
			return "held";
		}

		@Override
		protected String getFallback()
		{
			return "fallback";
		}
	}
}
