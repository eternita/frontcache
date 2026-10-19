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
package org.frontcache.resilience;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rolling metrics for one command, shaped to the Hystrix dashboard JSON rather than to Resilience4j's
 * data model.
 *
 * Resilience4j's CircuitBreaker.Metrics carries failure and slow-call rates and buffered/failed/
 * successful/not-permitted counts - and no per-event-type rolling counters and no latency
 * percentiles. The dashboard asserts 36 keys and throws on the first missing one, so the counter set
 * has to be shaped like the JSON. That is what this class is: a ring of 1-second buckets of LongAdder
 * per {@link FcEventType} plus an HdrHistogram ring, read as a merged snapshot. See proposal
 * section 7.3.
 *
 * Keyed by command key alone. One Frontcache node serves one site, so there is exactly one group at
 * runtime and nothing to disambiguate - the composite (domain, commandKey) key an earlier draft of
 * the proposal needed became unnecessary when multi-domain support was retired (section 6.6).
 *
 * This class also owns the in-flight registry, which does double duty: it produces
 * currentConcurrentExecutionCount for the dashboard, and it is what the semaphore-path watchdog sweeps
 * (section 6.2). Keeping one registry rather than two means the watchdog costs no per-request
 * allocation beyond what the concurrency gauge already needs.
 */
public class FcCommandMetrics {

	private static final Logger logger = LoggerFactory.getLogger(FcCommandMetrics.class);

	/** How often buckets rotate. The window length is rollingStatsMs, so 10 buckets by default. */
	static final long BUCKET_MS = 1000;

	/** Watchdog cadence. Fine-grained enough to be useful on a 1.5s budget, coarse enough to be free. */
	static final long SWEEP_MS = 250;

	private static final long MAX_TRACKED_LATENCY_MS = 300000;

	private static final double[] PERCENTILES = { 0, 25, 50, 75, 90, 95, 99, 99.5, 100 };

	private static final ConcurrentMap<String, FcCommandMetrics> INSTANCES = new ConcurrentHashMap<String, FcCommandMetrics>();

	private static volatile ScheduledExecutorService scheduler;

	private final String commandKey;
	private final String group;
	private final int bucketCount;

	private final LongAdder[][] buckets;              // [bucket][event ordinal]
	/**
	 * Monotonic lifetime totals per event, alongside the rolling ring.
	 *
	 * The ring exists because the dashboard wants a 10-second window; Prometheus wants the opposite - a
	 * counter that only ever goes up, so rate() can be computed over any window the query asks for.
	 * Exporting the rolling value as a counter would be a lie (it decreases), and exporting it as a gauge
	 * would make every useful PromQL query wrong. Two cheap counters beat one that fits neither consumer.
	 */
	private final LongAdder[] totals;
	private final ConcurrentHistogram[] latencyBuckets;
	private volatile int currentBucket = 0;

	private final AtomicInteger concurrentExecutions = new AtomicInteger(0);
	/** Per-bucket high-water mark of concurrentExecutions, for rollingMaxConcurrentExecutionCount. */
	private final int[] maxConcurrentPerBucket;
	/**
	 * Executions currently being watched by the semaphore-path watchdog.
	 *
	 * An identity Set of the records themselves, not a {@code Map<Long, InFlight>} keyed by a
	 * monotonic id. The id was past the {@link Long} cache within the first few hundred requests, so
	 * every watched execution boxed a Long to register and boxed another to deregister, plus the map
	 * node for each. The record is its own handle; {@link #markStart} hands it back and
	 * {@link #markEnd} removes it.
	 */
	private final Set<InFlight> inFlight = ConcurrentHashMap.newKeySet();

	private FcCommandMetrics(String commandKey, String group, int rollingStatsMs) {
		this.commandKey = commandKey;
		this.group = group;
		this.bucketCount = Math.max(1, rollingStatsMs / (int) BUCKET_MS);

		this.buckets = new LongAdder[bucketCount][];
		this.latencyBuckets = new ConcurrentHistogram[bucketCount];
		this.totals = new LongAdder[FcEventType.values().length];
		for (int e = 0; e < this.totals.length; e++)
			this.totals[e] = new LongAdder();
		for (int i = 0; i < bucketCount; i++)
		{
			this.buckets[i] = new LongAdder[FcEventType.values().length];
			for (int e = 0; e < this.buckets[i].length; e++)
				this.buckets[i][e] = new LongAdder();
			this.latencyBuckets[i] = new ConcurrentHistogram(MAX_TRACKED_LATENCY_MS, 3);
		}
		this.maxConcurrentPerBucket = new int[bucketCount];
	}

	public static FcCommandMetrics getInstance(String commandKey, String group, int rollingStatsMs)
	{
		FcCommandMetrics metrics = INSTANCES.get(commandKey);
		if (null != metrics)
			return metrics;

		synchronized (FcCommandMetrics.class) {
			metrics = INSTANCES.get(commandKey);
			if (null == metrics)
			{
				metrics = new FcCommandMetrics(commandKey, group, rollingStatsMs);
				INSTANCES.put(commandKey, metrics);
				startScheduler();
			}
		}
		return metrics;
	}

	public static Collection<FcCommandMetrics> all()
	{
		return Collections.unmodifiableCollection(INSTANCES.values());
	}

	public static FcCommandMetrics existing(String commandKey)
	{
		return INSTANCES.get(commandKey);
	}

	/** Test seam. */
	static void reset()
	{
		synchronized (FcCommandMetrics.class) {
			INSTANCES.clear();
			if (null != scheduler)
			{
				scheduler.shutdownNow();
				scheduler = null;
			}
		}
	}

	public String getCommandKey() { return commandKey; }

	public String getGroup() { return group; }

	// ---- recording ----------------------------------------------------------

	public void markEvent(FcEventType event)
	{
		buckets[currentBucket][event.ordinal()].increment();
		totals[event.ordinal()].increment();
	}

	public void recordLatency(long millis)
	{
		latencyBuckets[currentBucket].recordValue(Math.min(Math.max(millis, 0), MAX_TRACKED_LATENCY_MS));
	}

	/**
	 * Registers an execution and returns its token, which must be handed back to {@link #markEnd}.
	 *
	 * @param budgetMs the configured timeout for the command, or 0 for "not watched". Only the
	 *        semaphore path passes a budget: the thread path is bounded by a real TimeLimiter, so
	 *        watching it too would double-report the same overrun.
	 * @return the token to pass to {@link #markEnd}, or {@code null} when the execution is not watched
	 *         - markEnd accepts null and still maintains the concurrency gauge
	 */
	public InFlight markStart(long budgetMs, String description)
	{
		int concurrent = concurrentExecutions.incrementAndGet();

		// Racy by construction - two threads can read the same bucket and both write. The value is a
		// dashboard high-water mark, not an accounting figure, and a lock on the request path to make it
		// exact would cost more than the number is worth.
		int bucket = currentBucket;
		if (concurrent > maxConcurrentPerBucket[bucket])
			maxConcurrentPerBucket[bucket] = concurrent;

		if (0 >= budgetMs)
			return null; // not watched - nothing to register, and nothing to allocate

		InFlight execution = new InFlight(System.nanoTime(), budgetMs, description);
		inFlight.add(execution);
		return execution;
	}

	/** @param execution the token from {@link #markStart}; null for an execution that was not watched */
	public void markEnd(InFlight execution)
	{
		concurrentExecutions.decrementAndGet();
		if (null != execution)
			inFlight.remove(execution);
	}

	public int getCurrentConcurrentExecutionCount()
	{
		return concurrentExecutions.get();
	}

	public int getRollingMaxConcurrentExecutionCount()
	{
		int max = 0;
		for (int i = 0; i < bucketCount; i++)
			if (maxConcurrentPerBucket[i] > max)
				max = maxConcurrentPerBucket[i];
		return max;
	}

	// ---- reading ------------------------------------------------------------

	/** Monotonic count since JVM start - what the Prometheus exporter reads. */
	public long getTotalCount(FcEventType event)
	{
		return totals[event.ordinal()].sum();
	}

	public long getRollingCount(FcEventType event)
	{
		long sum = 0;
		for (int i = 0; i < bucketCount; i++)
			sum += buckets[i][event.ordinal()].sum();
		return sum;
	}

	/**
	 * Hystrix's HealthCounts total: successes plus the outcomes that mean the dependency call did not
	 * complete. Short-circuits are deliberately excluded, matching Hystrix - counting them would let an
	 * open circuit keep its own error rate pinned at 100% and never recover.
	 */
	public long getRollingTotalRequests()
	{
		return getRollingCount(FcEventType.SUCCESS) + getRollingErrorCount();
	}

	public long getRollingErrorCount()
	{
		return getRollingCount(FcEventType.FAILURE)
				+ getRollingCount(FcEventType.TIMEOUT)
				+ getRollingCount(FcEventType.THREAD_POOL_REJECTED)
				+ getRollingCount(FcEventType.SEMAPHORE_REJECTED);
	}

	public int getErrorPercentage()
	{
		long total = getRollingTotalRequests();
		if (0 == total)
			return 0;
		return (int) ((getRollingErrorCount() * 100) / total);
	}

	/**
	 * The mean and the nine percentiles, read from ONE merge of the bucket ring.
	 *
	 * The merge is the expensive part - it allocates a {@link Histogram} sized for
	 * {@link #MAX_TRACKED_LATENCY_MS} at three significant digits (on the order of ten thousand
	 * counters) and folds every bucket into it. Both numbers come from the same merge here because
	 * every consumer wants both, and taking them through {@link #getLatencyMean()} and
	 * {@link #getLatencyPercentiles()} separately paid for the merge twice.
	 *
	 * Immutable, so a caller can hold one for the duration of a frame or a scrape.
	 */
	public static final class LatencySnapshot {

		private final long mean;
		private final Map<String, Long> percentiles;

		private LatencySnapshot(long mean, Map<String, Long> percentiles) {
			this.mean = mean;
			this.percentiles = percentiles;
		}

		public long getMean() {
			return mean;
		}

		/** The percentile map the dashboard expects, with its exact key set and order. */
		public Map<String, Long> getPercentiles() {
			return percentiles;
		}
	}

	/**
	 * One merge of the rolling ring.
	 *
	 * This is deliberately NOT cached here. A cache would need a TTL, and a TTL inside the accessor
	 * would make "record a latency, then read it back" quietly wrong for anything that does the two in
	 * quick succession - the tests among them. Callers that read repeatedly within one logical
	 * operation (a dashboard frame, a Prometheus scrape) hold the returned snapshot instead.
	 */
	public LatencySnapshot latencySnapshot()
	{
		Histogram merged = new Histogram(MAX_TRACKED_LATENCY_MS, 3);
		for (int i = 0; i < bucketCount; i++)
			merged.add(latencyBuckets[i]);

		boolean empty = (0 == merged.getTotalCount());

		Map<String, Long> percentiles = new LinkedHashMap<String, Long>();
		for (double p : PERCENTILES)
		{
			String key = (p == Math.floor(p)) ? String.valueOf((int) p) : String.valueOf(p);
			percentiles.put(key, Long.valueOf(empty ? 0 : merged.getValueAtPercentile(p)));
		}

		return new LatencySnapshot(empty ? 0 : (long) merged.getMean(), percentiles);
	}

	/** Convenience for a caller that wants only the mean. Costs a full merge - see {@link #latencySnapshot()}. */
	public long getLatencyMean()
	{
		return latencySnapshot().getMean();
	}

	/**
	 * The percentile map the dashboard expects, with its exact key set - it divides and plots these
	 * directly, so the shape matters as much as the numbers.
	 *
	 * Costs a full merge - see {@link #latencySnapshot()}.
	 */
	public Map<String, Long> getLatencyPercentiles()
	{
		return latencySnapshot().getPercentiles();
	}

	// ---- rotation and the watchdog -----------------------------------------

	/** Advances every instance's window by one bucket, clearing the bucket being reused. */
	static void rotateAll()
	{
		for (FcCommandMetrics metrics : INSTANCES.values())
			metrics.rotate();
	}

	void rotate()
	{
		int next = (currentBucket + 1) % bucketCount;
		for (LongAdder adder : buckets[next])
			adder.reset();
		latencyBuckets[next].reset();
		maxConcurrentPerBucket[next] = 0;
		currentBucket = next;
	}

	/**
	 * The semaphore-path watchdog: reports executions that outran their configured budget and does
	 * nothing else. No interrupt, no abort, no fallback, and explicitly no circuit-breaker
	 * notification - see {@link FcEventType#BUDGET_OVERRUN_OBSERVED} for why that last one matters.
	 *
	 * Reports once per execution, not once per sweep, so a request that hangs for an hour produces one
	 * log line rather than 14,400.
	 */
	static void sweepAll()
	{
		for (FcCommandMetrics metrics : INSTANCES.values())
			metrics.sweepOverruns();
	}

	void sweepOverruns()
	{
		long now = System.nanoTime();
		for (InFlight execution : inFlight)
		{
			if (execution.reported || !execution.isOverrun(now))
				continue;

			execution.reported = true;
			markEvent(FcEventType.BUDGET_OVERRUN_OBSERVED);
			logger.warn("Command " + commandKey + " exceeded its configured budget of " + execution.budgetMs
					+ "ms (" + execution.elapsedMs(now) + "ms elapsed, still running) - "
					+ execution.description
					+ ". Not aborted: semaphore-isolated commands run on the request thread and this budget"
					+ " is reported, not enforced.");
		}
	}

	private static void startScheduler()
	{
		if (null != scheduler)
			return;

		ScheduledExecutorService local = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r)
			{
				Thread t = new Thread(r, "fc-metrics");
				t.setDaemon(true);
				return t;
			}
		});

		local.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run()
			{
				try {
					rotateAll();
				} catch (Throwable t) {
					// a throw here would kill the scheduled task for the life of the JVM, silently
					// freezing every rolling counter - so swallow and log instead
					logger.error("Metrics rotation failed", t);
				}
			}
		}, BUCKET_MS, BUCKET_MS, TimeUnit.MILLISECONDS);

		local.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run()
			{
				try {
					sweepAll();
				} catch (Throwable t) {
					logger.error("Watchdog sweep failed", t);
				}
			}
		}, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);

		scheduler = local;
	}

	/**
	 * One watched execution. Package-visible because it IS the token {@link #markStart} returns - see
	 * the {@link #inFlight} field.
	 */
	static class InFlight {

		private final long startNanos;
		private final long budgetMs;
		private final String description;
		private volatile boolean reported = false;

		InFlight(long startNanos, long budgetMs, String description) {
			this.startNanos = startNanos;
			this.budgetMs = budgetMs;
			this.description = (null == description) ? "" : description;
		}

		boolean isOverrun(long nowNanos)
		{
			return elapsedMs(nowNanos) > budgetMs;
		}

		long elapsedMs(long nowNanos)
		{
			return TimeUnit.NANOSECONDS.toMillis(nowNanos - startNanos);
		}
	}
}
