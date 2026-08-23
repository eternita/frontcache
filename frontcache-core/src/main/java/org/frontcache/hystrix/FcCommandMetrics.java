/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

	private static final AtomicLong EXECUTION_IDS = new AtomicLong(0);

	private static volatile ScheduledExecutorService scheduler;

	private final String commandKey;
	private final String group;
	private final int bucketCount;

	private final LongAdder[][] buckets;              // [bucket][event ordinal]
	private final ConcurrentHistogram[] latencyBuckets;
	private volatile int currentBucket = 0;

	private final AtomicInteger concurrentExecutions = new AtomicInteger(0);
	private final ConcurrentMap<Long, InFlight> inFlight = new ConcurrentHashMap<Long, InFlight>();

	private FcCommandMetrics(String commandKey, String group, int rollingStatsMs) {
		this.commandKey = commandKey;
		this.group = group;
		this.bucketCount = Math.max(1, rollingStatsMs / (int) BUCKET_MS);

		this.buckets = new LongAdder[bucketCount][];
		this.latencyBuckets = new ConcurrentHistogram[bucketCount];
		for (int i = 0; i < bucketCount; i++)
		{
			this.buckets[i] = new LongAdder[FcEventType.values().length];
			for (int e = 0; e < this.buckets[i].length; e++)
				this.buckets[i][e] = new LongAdder();
			this.latencyBuckets[i] = new ConcurrentHistogram(MAX_TRACKED_LATENCY_MS, 3);
		}
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
	}

	public void recordLatency(long millis)
	{
		latencyBuckets[currentBucket].recordValue(Math.min(Math.max(millis, 0), MAX_TRACKED_LATENCY_MS));
	}

	/**
	 * Registers an execution and returns its token.
	 *
	 * @param budgetMs the configured timeout for the command, or 0 for "not watched". Only the
	 *        semaphore path passes a budget: the thread path is bounded by a real TimeLimiter, so
	 *        watching it too would double-report the same overrun.
	 */
	public long markStart(long budgetMs, String description)
	{
		long id = EXECUTION_IDS.incrementAndGet();
		concurrentExecutions.incrementAndGet();
		if (budgetMs > 0)
			inFlight.put(Long.valueOf(id), new InFlight(System.nanoTime(), budgetMs, description));
		return id;
	}

	public void markEnd(long id)
	{
		concurrentExecutions.decrementAndGet();
		inFlight.remove(Long.valueOf(id));
	}

	public int getCurrentConcurrentExecutionCount()
	{
		return concurrentExecutions.get();
	}

	// ---- reading ------------------------------------------------------------

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

	private Histogram latencySnapshot()
	{
		Histogram merged = new Histogram(MAX_TRACKED_LATENCY_MS, 3);
		for (int i = 0; i < bucketCount; i++)
			merged.add(latencyBuckets[i]);
		return merged;
	}

	public long getLatencyMean()
	{
		Histogram snapshot = latencySnapshot();
		return (0 == snapshot.getTotalCount()) ? 0 : (long) snapshot.getMean();
	}

	/**
	 * The percentile map the dashboard expects, with its exact key set - it divides and plots these
	 * directly, so the shape matters as much as the numbers.
	 */
	public Map<String, Long> getLatencyPercentiles()
	{
		Histogram snapshot = latencySnapshot();
		Map<String, Long> percentiles = new LinkedHashMap<String, Long>();
		for (double p : PERCENTILES)
		{
			String key = (p == Math.floor(p)) ? String.valueOf((int) p) : String.valueOf(p);
			percentiles.put(key, Long.valueOf(0 == snapshot.getTotalCount() ? 0 : snapshot.getValueAtPercentile(p)));
		}
		return percentiles;
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
		for (Map.Entry<Long, InFlight> entry : inFlight.entrySet())
		{
			InFlight execution = entry.getValue();
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

	private static class InFlight {

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
