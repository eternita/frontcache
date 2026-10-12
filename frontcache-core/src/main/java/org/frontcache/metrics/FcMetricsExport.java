/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.metrics;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.cache.FcCacheMetrics;
import org.frontcache.guard.GuardAction;
import org.frontcache.include.combine.FcCombineMetrics;
import org.frontcache.license.FcLicense;
import org.frontcache.license.License;
import org.frontcache.license.LicenseState;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEngine;
import org.frontcache.guard.ratelimit.GuardRateLimiterRegistry;
import org.frontcache.guard.ratelimit.WindowLimiter;
import org.frontcache.resilience.FcCommandMetrics;
import org.frontcache.resilience.FcEventType;
import org.frontcache.resilience.FcThreadPools;
import org.frontcache.warmer.CacheWarmer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.registry.otlp.OtlpConfig;

/**
 * Prometheus / OTLP export, additive alongside the SSE stream.
 *
 * The SSE stream stays the source of truth for the Hystrix dashboard: Micrometer's model does not carry
 * Hystrix's event taxonomy, so choosing it INSTEAD would mean hand-writing the same JSON from a poorer
 * data model. So {@link FcCommandMetrics} keeps producing the dashboard's numbers and this class exports
 * the same counters through Micrometer. See proposal section 7.5.
 *
 * <h2>Off by default</h2>
 *
 * {@code front-cache.metrics.export=none} is the default. A node that starts a listener or an OTLP push
 * loop merely because it was upgraded is not acceptable behaviour, and an OTLP registry with no endpoint
 * configured would log a failure every step.
 *
 * <h2>Cardinality</h2>
 *
 * Series are labelled by command key and event name, and by nothing else. {@code RequestLogger} has
 * URL-level data and labelling with it would be tempting; on a page cache the URL space is unbounded by
 * construction, so that is a cardinality explosion rather than an observability feature.
 */
public class FcMetricsExport {

	static final String EXPORT_KEY = "front-cache.metrics.export";
	static final String OTLP_URL_KEY = "front-cache.metrics.otlp.url";
	static final String OTLP_STEP_KEY = "front-cache.metrics.otlp.step";
	static final String OTLP_HEADERS_KEY = "front-cache.metrics.otlp.headers";

	static final String EXPORT_NONE = "none";
	static final String EXPORT_PROMETHEUS = "prometheus";
	static final String EXPORT_OTLP = "otlp";
	static final String EXPORT_BOTH = "both";

	/** Every series carries this prefix, including the meters Resilience4j's own binders register. */
	private static final String PREFIX = "frontcache.";

	private static final Logger logger = LoggerFactory.getLogger(FcMetricsExport.class);

	private static volatile FcMetricsExport instance;

	private final CompositeMeterRegistry registry = new CompositeMeterRegistry();
	private final PrometheusMeterRegistry prometheus;
	private final OtlpMeterRegistry otlp;

	private FcMetricsExport(boolean withPrometheus, boolean withOtlp) {
		// Prefix everything, rather than only our own meters. resilience4j-micrometer registers
		// resilience4j.* names and ExecutorServiceMetrics registers executor.*; a single series prefix is
		// what makes the whole set findable and greppable in one query.
		registry.config().meterFilter(new MeterFilter() {
			@Override
			public io.micrometer.core.instrument.Meter.Id map(io.micrometer.core.instrument.Meter.Id id)
			{
				return id.getName().startsWith(PREFIX) ? id : id.withName(PREFIX + id.getName());
			}
		});

		this.prometheus = withPrometheus ? new PrometheusMeterRegistry(PrometheusConfig.DEFAULT) : null;
		this.otlp = withOtlp ? new OtlpMeterRegistry(otlpConfig(), io.micrometer.core.instrument.Clock.SYSTEM) : null;

		if (null != prometheus)
			registry.add(prometheus);
		if (null != otlp)
			registry.add(otlp);
	}

	/**
	 * Starts the exporter if configured. Idempotent, and cheap when export is off - which is why the
	 * engine can call it unconditionally at startup.
	 */
	public static synchronized void start()
	{
		if (null != instance)
			return;

		String mode = FCConfig.getProperty(EXPORT_KEY, EXPORT_NONE).trim().toLowerCase();

		boolean withPrometheus = EXPORT_PROMETHEUS.equals(mode) || EXPORT_BOTH.equals(mode);
		boolean withOtlp = EXPORT_OTLP.equals(mode) || EXPORT_BOTH.equals(mode);

		if (!withPrometheus && !withOtlp)
		{
			if (!EXPORT_NONE.equals(mode))
				logger.warn(EXPORT_KEY + "=" + mode + " is not one of none|prometheus|otlp|both"
						+ " - metrics export stays OFF");
			return;
		}

		if (withOtlp && isEmpty(FCConfig.getProperty(OTLP_URL_KEY)))
		{
			logger.error(EXPORT_KEY + " asks for OTLP but " + OTLP_URL_KEY + " is not set"
					+ " - OTLP export is disabled (a push registry with no endpoint just logs failures)");
			withOtlp = false;
			if (!withPrometheus)
				return;
		}

		FcMetricsExport export = new FcMetricsExport(withPrometheus, withOtlp);
		export.bindMeters();
		instance = export;

		logger.info("Metrics export started: prometheus=" + withPrometheus + " otlp=" + withOtlp
				+ " (all series prefixed " + PREFIX + ")");
	}

	/** Test seam - closes the registries and forgets the instance. */
	public static synchronized void stop()
	{
		if (null == instance)
			return;

		instance.registry.close();
		instance = null;
	}

	public static boolean isEnabled()
	{
		return null != instance;
	}

	/**
	 * @return the Prometheus text exposition, or null when Prometheus export is not enabled
	 */
	public static String scrape()
	{
		FcMetricsExport local = instance;
		if (null == local || null == local.prometheus)
			return null;

		return local.prometheus.scrape();
	}

	/** Package-private for tests. */
	static MeterRegistry registry()
	{
		FcMetricsExport local = instance;
		return (null == local) ? null : local.registry;
	}

	// ---- what gets exported -------------------------------------------------

	private void bindMeters()
	{
		// Resilience4j's own binders: circuit state, failure rate, bulkhead occupancy, timeout counts.
		io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
				.ofCircuitBreakerRegistry(org.frontcache.resilience.FcResilienceRegistry.circuitBreakerRegistry())
				.bindTo(registry);
		io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics
				.ofBulkheadRegistry(org.frontcache.resilience.FcResilienceRegistry.bulkheadRegistry())
				.bindTo(registry);
		io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics
				.ofTimeLimiterRegistry(org.frontcache.resilience.FcResilienceRegistry.timeLimiterRegistry())
				.bindTo(registry);

		// Those binders alone would make Prometheus strictly poorer than the SSE stream: no per-event
		// taxonomy and no latency percentiles. We already maintain both for the dashboard, so bridge them.
		bindCommandMetrics();
		bindThreadPools();
		bindCacheMetrics();
		bindCombineMetrics();
		bindGuardMetrics();
		bindWarmerMetrics();
		bindLicenseMetrics();
		bindJvmMetrics();
	}

	private boolean licenseBound = false;

	/**
	 * The license state - docs/ip-protection-proposal.md section 5.2: one series per state, 1 for the
	 * current one, so {@code frontcache_license_state{state="valid"} == 0} is an alert, and
	 * {@code updates.until} so a renewal can be alerted on before it is due.
	 *
	 * Both read {@link FcLicense#current()} at scrape time - a volatile read of a value the license monitor
	 * re-checks every minute, so a renewed license dropped into conf/ shows up without a restart.
	 * {@code updates.until} is NaN with no license: "no date" is not the epoch.
	 *
	 * {@code state} is a fixed set of six ({@link LicenseState}) - no license id or licensee label, which
	 * would put customer names into every Prometheus server this node is scraped by.
	 */
	private synchronized void bindLicenseMetrics()
	{
		if (licenseBound)
			return;
		licenseBound = true;

		for (final LicenseState state : LicenseState.values())
			Gauge.builder("license.state", () -> (FcLicense.current().getState() == state) ? 1 : 0)
					.description("1 for this node's current license state, 0 for the others")
					.tags(Tags.of("state", state.id()))
					.register(registry);

		Gauge.builder("license.updates.until.timestamp", () -> {
					License license = FcLicense.current().getLicense();
					return (null == license) ? Double.NaN
							: license.getUpdatesUntil().atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
				})
				.description("The license's updates-until date as a Unix timestamp; NaN with no license")
				.baseUnit("seconds")
				.register(registry);
	}

	private boolean warmerBound = false;

	/**
	 * The cache warmer: entries by what happened to them, and whether a run is going.
	 *
	 * Bound unconditionally, like the combine counters - the counts are static on {@code CacheWarmer}
	 * and need no warmer instance, and a scrape must not create one (it resolves FRONTCACHE_HOME/warmer).
	 * No list-name or URL label: {@code result} is the fixed set {@link CacheWarmer#RESULTS}.
	 */
	private synchronized void bindWarmerMetrics()
	{
		if (warmerBound)
			return;
		warmerBound = true;

		for (final String result : CacheWarmer.RESULTS)
			FunctionCounter.builder("warmer.entries", result, r -> CacheWarmer.entriesTotal(r))
					.description("URI-list entries the cache warmer finished, by result, since JVM start")
					.tags(Tags.of("result", result))
					.register(registry);

		Gauge.builder("warmer.running", () -> {
					CacheWarmer warmer = CacheWarmer.existingInstance();
					return (null != warmer && warmer.isRunning()) ? 1 : 0;
				})
				.description("1 while a cache warm run is in progress (running, paused or stopping)")
				.register(registry);
	}

	/**
	 * Heap, process CPU and uptime - the JVM numbers {@code get-node-stats} already reports to the
	 * console, exported here so the same three are available to Grafana and to alerting.
	 *
	 * <h3>Why bind them here rather than bridge FcNodeStats</h3>
	 *
	 * {@code FcNodeStats} reads the same beans and deliberately owes nothing to Micrometer, because the
	 * management API has to answer whether or not export is enabled. Bridging it into gauges would put
	 * a second implementation of "what is heap" in the tree; Micrometer's binders are the standard one,
	 * are already on the classpath, and produce the per-pool breakdown a flat heap-used gauge cannot.
	 *
	 * <h3>Bound once, not on refresh</h3>
	 *
	 * Unlike commands, pools and the cache, these exist from JVM start - there is no later moment at
	 * which a heap appears. So this is called from {@link #bindMeters()} only, and {@link #refresh()}
	 * leaves it alone.
	 *
	 * <h3>What is deliberately NOT bound</h3>
	 *
	 * {@code JvmGcMetrics} is the obvious next one and is left out on purpose: it is {@code Closeable}
	 * and registers a GC notification listener, so binding it adds a lifecycle obligation to
	 * {@link #stop()} that nothing else here has, and a leak if that is ever missed. Same for
	 * {@code JvmHeapPressureMetrics}. Threads, class loading and file descriptors are simply out of
	 * this change's scope - each is a line to add once someone wants the series.
	 */
	private void bindJvmMetrics()
	{
		// heap and non-heap, per memory pool. Tags: area (heap|nonheap) and id (pool name) - both
		// bounded by the JVM's own fixed pool set, which is what the cardinality guard cares about.
		new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(registry);

		// process.cpu.usage and system.cpu.usage, plus system.cpu.count and load average. Untagged.
		new io.micrometer.core.instrument.binder.system.ProcessorMetrics().bindTo(registry);

		// process.uptime and process.start.time - what explains a cache size or a heap that looks wrong
		// because the node restarted four minutes ago. Untagged.
		new io.micrometer.core.instrument.binder.system.UptimeMetrics().bindTo(registry);
	}

	private boolean cacheBound = false;

	/**
	 * L1/L2 hit ratio, eviction rate and index size - the numbers an operator actually alerts on, and
	 * the ones the resilience meters say nothing about. A page cache that has quietly stopped hitting
	 * looks perfectly healthy through circuit-breaker metrics: every origin call succeeds.
	 *
	 * Two different sources, deliberately:
	 * <ul>
	 * <li>the READ COUNTERS come from {@link FcCacheMetrics}, which counts them on the request path, so
	 *     they are exact and monotonic;</li>
	 * <li>the SIZES come from {@link CacheStats}, sampled at scrape time, because a size is a level and
	 *     counting every insert and eviction to track one would cost more than reading it.</li>
	 * </ul>
	 *
	 * Sizes are bound once the cache processor exists, and only for the tiers it actually tracks: a
	 * processor with no disk tier gets no index series rather than a gauge reading a flat zero, which
	 * is a line somebody would eventually alert on.
	 */
	private synchronized void bindCacheMetrics()
	{
		// The read counters do not depend on a cache processor existing, so they are always registered -
		// a zero here means "no reads yet", which is true.
		if (!cacheReadsBound)
		{
			cacheReadsBound = true;
			for (final FcCacheMetrics.Outcome outcome : FcCacheMetrics.Outcome.values())
				FunctionCounter.builder("cache.reads", outcome, o -> FcCacheMetrics.count(o))
						.description("Cache reads by outcome, since JVM start")
						.tags(Tags.of("result", outcome.getResult(), "tier", outcome.getTier()))
						.register(registry);
		}

		if (cacheBound)
			return;

		// existingInstance(), not getInstance(): a scrape must not be what starts the cache. See
		// CacheManager.existingInstance().
		CacheProcessor processor = CacheManager.existingInstance();
		if (null == processor)
			return;

		CacheStats stats = processor.getCacheStats();
		cacheBound = true;

		if (CacheStats.UNKNOWN != stats.getL1Entries())
			cacheGauge("cache.entries", "Entries currently cached", Tags.of("tier", "L1"),
					s -> s.getL1Entries());

		if (CacheStats.UNKNOWN != stats.getL1Evictions())
			FunctionCounter.builder("cache.evictions", processor, p -> p.getCacheStats().getL1Evictions())
					.description("Entries evicted from the in-memory tier, since JVM start")
					.tags(Tags.of("tier", "L1"))
					.register(registry);

		// Content bytes, NOT the tier's retained heap - the meter name says "content" for that reason.
		if (CacheStats.UNKNOWN != stats.getL1ContentBytes())
			cacheGauge("cache.content.bytes", "Bytes of response content held in the in-memory tier",
					Tags.of("tier", "L1"), s -> s.getL1ContentBytes());

		if (CacheStats.UNKNOWN != stats.getL2Entries())
			cacheGauge("cache.entries", "Entries currently cached", Tags.of("tier", "L2"),
					s -> s.getL2Entries());

		if (CacheStats.UNKNOWN != stats.getL2DiskBytes())
			cacheGauge("cache.disk.bytes", "Bytes the on-disk tier occupies", Tags.of("tier", "L2"),
					s -> s.getL2DiskBytes());
	}

	private boolean cacheReadsBound = false;

	/**
	 * Each gauge re-reads through {@link CacheManager#existingInstance()} rather than closing over the
	 * processor, so a cache reload that swaps the instance does not leave the series reporting the old
	 * one forever.
	 */
	private void cacheGauge(String name, String description, Tags tags,
			final java.util.function.ToLongFunction<CacheStats> read)
	{
		Gauge.builder(name, () -> {
					CacheProcessor current = CacheManager.existingInstance();
					return (null == current) ? Double.valueOf(Double.NaN)
							: Double.valueOf(read.applyAsLong(current.getCacheStats()));
				})
				.description(description)
				.tags(tags)
				.register(registry);
	}

	/**
	 * Meters are registered lazily, per command, because a command's metrics object does not exist until
	 * it first executes. Re-run on each scrape so a command that starts serving later still appears.
	 */
	private void bindCommandMetrics()
	{
		for (FcCommandMetrics metrics : FcCommandMetrics.all())
			bindCommand(metrics);
	}

	private final Map<String, Boolean> boundCommands = new HashMap<String, Boolean>();
	private final Map<String, Boolean> boundPools = new HashMap<String, Boolean>();

	private synchronized void bindCommand(final FcCommandMetrics metrics)
	{
		if (Boolean.TRUE.equals(boundCommands.get(metrics.getCommandKey())))
			return;
		boundCommands.put(metrics.getCommandKey(), Boolean.TRUE);

		Tags commandTag = Tags.of("command", metrics.getCommandKey());

		// FunctionCounter, not Gauge, even though both would read the same monotonic number. Prometheus
		// treats a counter's drop to zero as a process restart and a gauge's as a real decrease - so as a
		// gauge, every restart would show up as a large negative rate() instead of a reset. This is also
		// what gets it the conventional _total suffix and the counter type in the exposition.
		for (final FcEventType event : FcEventType.values())
			FunctionCounter.builder("command.events", metrics, m -> m.getTotalCount(event))
					.description("Command executions by outcome, since JVM start")
					.tags(commandTag.and("event", event.name().toLowerCase().replace('_', '-')))
					.register(registry);

		Gauge.builder("command.concurrent.executions", metrics, m -> m.getCurrentConcurrentExecutionCount())
				.description("Commands executing right now")
				.tags(commandTag)
				.strongReference(true)
				.register(registry);

		// Seven latency gauges, ONE merge of the rolling histogram ring between them. Micrometer calls
		// each gauge's lambda separately, so reading them straight off the metrics object meant seven
		// full merges per command per scrape - each allocating a ~10k-counter Histogram, and six of them
		// also building a nine-entry map only to pull one value out of it.
		final LatencySnapshotCache latency = new LatencySnapshotCache(metrics);

		Gauge.builder("command.latency.seconds", latency, c -> c.get().getMean() / 1000.0)
				.description("Mean command latency over the rolling window")
				.tags(commandTag.and("quantile", "mean"))
				.strongReference(true)
				.register(registry);

		for (final String percentile : new String[] { "50", "90", "95", "99", "99.5", "100" })
			Gauge.builder("command.latency.seconds", latency,
							c -> value(c.get().getPercentiles().get(percentile)) / 1000.0)
					.description("Command latency percentile over the rolling window")
					.tags(commandTag.and("quantile", percentile))
					.strongReference(true)
					.register(registry);
	}

	/**
	 * Holds one command's latency snapshot for long enough that the seven gauges of a single scrape
	 * share it.
	 *
	 * The TTL exists only to span a scrape, not to smooth the data: it is far shorter than any sane
	 * scrape interval, so consecutive scrapes always see a fresh merge. The lambdas Micrometer holds
	 * reference this object, which is why the gauges are registered against it - with
	 * strongReference(true), as before, so it is not collected out from under the registry.
	 *
	 * Racy by construction: two threads scraping at once may both merge, and the second overwrites the
	 * first. The cost of that is one redundant merge, and the numbers are equally valid either way.
	 */
	private static final class LatencySnapshotCache {

		/** Long enough to cover one scrape's seven reads, far shorter than any scrape interval. */
		private static final long TTL_NANOS = 1_000_000_000L; // 1s

		private final FcCommandMetrics metrics;
		private volatile FcCommandMetrics.LatencySnapshot snapshot;
		private volatile long takenNanos;

		LatencySnapshotCache(FcCommandMetrics metrics) {
			this.metrics = metrics;
		}

		FcCommandMetrics.LatencySnapshot get() {
			FcCommandMetrics.LatencySnapshot current = snapshot;
			long now = System.nanoTime();
			if (null == current || TTL_NANOS < now - takenNanos)
			{
				current = metrics.latencySnapshot();
				snapshot = current;
				takenNanos = now;
			}
			return current;
		}
	}

	// ---- guard rules ---------------------------------------------------------

	private final Map<String, Boolean> boundGuardRules = new HashMap<String, Boolean>();
	private final Map<String, Boolean> boundRateLimitBuckets = new HashMap<String, Boolean>();

	private boolean combineBound = false;

	/**
	 * Combine-and-reduce: batches, members and envelope errors.
	 * See docs/archive/combine-reduce-proposal.md section 12.
	 *
	 * <h3>The number these exist for</h3>
	 *
	 * <pre>
	 * rate(frontcache_include_combine_members_total{result="combined"}[5m])
	 *   / rate(frontcache_include_combine_batches_total{result="ok"}[5m])
	 * </pre>
	 *
	 * Average fragments per origin request - the feature's entire claim, and a number nothing else in
	 * the process can produce. The command metrics count origin calls but not how many fragments each
	 * carried; the cache metrics count hits, and a batch IS the miss path.
	 *
	 * <h3>What to alert on</h3>
	 *
	 * {@code frontcache_include_combine_errors_total}. Every value of its {@code kind} label is an
	 * origin that claims to implement the contract and does not, and there is no other signal for it:
	 * the page still renders, out of fallbacks, and every other meter reads healthy.
	 * {@code batches_total{result="unsupported"}} is the softer version - an origin with the markup but
	 * not the handler - which costs one wasted request per group per memo window and otherwise hides.
	 *
	 * <h3>Bound unconditionally</h3>
	 *
	 * Like the cache read counters and unlike the cache SIZE gauges, these need no cache processor and
	 * no engine: a zero means "no combining yet", which is true and is itself worth being able to see
	 * on a node whose markup was supposed to have been switched over.
	 */
	private synchronized void bindCombineMetrics()
	{
		if (combineBound)
			return;
		combineBound = true;

		for (final FcCombineMetrics.Batch outcome : FcCombineMetrics.Batch.values())
			FunctionCounter.builder("include.combine.batches", outcome, o -> FcCombineMetrics.count(o))
					.description("Combined origin calls by outcome, since JVM start")
					.tags(Tags.of("result", outcome.getResult()))
					.register(registry);

		for (final FcCombineMetrics.Member outcome : FcCombineMetrics.Member.values())
			FunctionCounter.builder("include.combine.members", outcome, o -> FcCombineMetrics.count(o))
					.description("Combinable includes by how they were resolved, since JVM start")
					.tags(Tags.of("result", outcome.getResult()))
					.register(registry);

		for (final FcCombineMetrics.Error kind : FcCombineMetrics.Error.values())
			FunctionCounter.builder("include.combine.errors", kind, k -> FcCombineMetrics.count(k))
					.description("Combine envelopes rejected as malformed, since JVM start - an origin bug")
					.tags(Tags.of("kind", kind.getKind()))
					.register(registry);
	}

	/**
	 * What the guard pipeline rejected, redirected, exempted or would have caught.
	 *
	 * Until now these counts existed only behind {@code get-guard-rules}, i.e. reachable by the console
	 * and by nothing else. They answer the questions an operator asks of a guard rule - is it firing at
	 * all, is a new dry-run rule about to refuse real visitors, did the rate limit start biting after a
	 * traffic change - and none of those should require opening a web UI to see.
	 *
	 * <h3>One counter per rule, with the outcome as a fixed label</h3>
	 *
	 * A rule has one action and one dry-run flag, both decided at load time, so its outcome never varies
	 * per request: the single hit counter plus a constant {@code result} label is the whole truth. The
	 * label vocabulary is the log's, not a new one - {@code rejected} / {@code redirected} / {@code
	 * dry-run} are exactly the dispositions written to frontcache-failed-requests.log, so a Grafana panel
	 * and a Kibana panel name the same thing the same way. {@code allowed} is the one addition, for
	 * exemption rules, which are never logged but are worth counting: an {@code allow} rule with no hits
	 * is dead config sitting in front of the rules that matter.
	 *
	 * <h3>Read by name, not by reference</h3>
	 *
	 * {@code reload-guard-rules} builds new GuardRule objects and resets their counters, so a meter
	 * closing over a rule instance would silently freeze at the pre-reload value. Each counter therefore
	 * looks its rule up by name on every read - the same reason {@link #cacheGauge} re-reads through
	 * {@code CacheManager.existingInstance()}. The reset that a reload causes is real and is reported as
	 * one: these are counters, and Prometheus already understands a counter going backwards as a reset.
	 */
	private synchronized void bindGuardMetrics()
	{
		// existingInstance(), not getFrontCache(): a scrape must not be what starts the node. Early in
		// startup this is legitimately null - the engine calls start() from its own constructor - and the
		// rules are picked up by the next refresh(), like commands and pools.
		FrontCacheEngine engine = FrontCacheEngine.existingInstance();
		if (null == engine)
			return;

		GuardRuleEngine guards = engine.getGuardRuleEngine();
		if (null != guards)
			for (GuardRule rule : guards.getRules())
			{
				final String name = rule.getName();
				String key = name + "|" + resultOf(rule);

				if (Boolean.TRUE.equals(boundGuardRules.get(key)))
					continue;
				boundGuardRules.put(key, Boolean.TRUE);

				FunctionCounter.builder("guard.actions", name, n -> guardHits(n))
						.description("Requests matched by a guard rule, since the rules were last loaded")
						.tags(Tags.of("name", name, "result", resultOf(rule)))
						.register(registry);
			}

		for (WindowLimiter limiter : GuardRateLimiterRegistry.getLimiters())
		{
			final String bucket = limiter.getBucket();

			if (Boolean.TRUE.equals(boundRateLimitBuckets.get(bucket)))
				continue;
			boundRateLimitBuckets.put(bucket, Boolean.TRUE);

			// The undersizing signal. A rate-limit bucket is a fixed slot array with no eviction, so two
			// addresses hashing to one slot make the arriving one take it over and start a fresh count.
			// That is deliberate and it fails safe - it can only under-count, never refuse a visitor
			// wrongly - but a takeover rate climbing with traffic means front-cache.guard-rules.rate-limit.slots
			// is too small for the address space actually being seen.
			FunctionCounter.builder("guard.ratelimit.takeovers", bucket, b -> rateLimitTakeovers(b))
					.description("Rate-limit slot takeovers - a rising rate means the slot array is undersized")
					.tags(Tags.of("name", bucket))
					.register(registry);
		}
	}

	/**
	 * Hits of every currently loaded rule with this name.
	 *
	 * Summed rather than first-match because nothing stops two rules sharing a name, and two meters with
	 * identical tags would be one meter in the registry - so the sum is the only reading that is not
	 * silently wrong. With unique names, which is the norm, it is the rule's own count.
	 *
	 * @return 0 when no rule of that name is loaded any more, which is what a deleted rule should read
	 */
	private static double guardHits(String name)
	{
		FrontCacheEngine engine = FrontCacheEngine.existingInstance();
		if (null == engine)
			return 0;

		GuardRuleEngine guards = engine.getGuardRuleEngine();
		if (null == guards)
			return 0;

		long hits = 0;
		for (GuardRule rule : guards.getRules())
			if (name.equals(rule.getName()))
				hits += rule.getHits();

		return hits;
	}

	private static double rateLimitTakeovers(String bucket)
	{
		for (WindowLimiter limiter : GuardRateLimiterRegistry.getLimiters())
			if (bucket.equals(limiter.getBucket()))
				return limiter.getTakeovers();

		return 0;
	}

	/**
	 * The rule's outcome as a label value, in the log's own vocabulary so a metric and a log line about
	 * the same rule agree on what happened.
	 */
	static String resultOf(GuardRule rule)
	{
		if (rule.isDryRun())
			return GuardAction.DISPOSITION_DRY_RUN;

		String disposition = rule.getAction().getDisposition();
		return (null != disposition) ? disposition : "allowed"; // an 'allow' rule writes no log line
	}

	private synchronized void bindThreadPools()
	{
		for (String poolKey : FcThreadPools.all())
		{
			if (Boolean.TRUE.equals(boundPools.get(poolKey)))
				continue;

			ThreadPoolExecutor pool = FcThreadPools.existing(poolKey);
			if (null == pool)
				continue;

			boundPools.put(poolKey, Boolean.TRUE);

			// Standard executor meters - queue size, active count, completed tasks, task duration.
			new ExecutorServiceMetrics(pool, poolKey, Tags.empty()).bindTo(registry);

			// Not covered by ExecutorServiceMetrics, and the number an operator actually alerts on:
			// rejections mean the node is shedding origin calls.
			FunctionCounter.builder("threadpool.rejections", poolKey, key -> FcThreadPools.rejectionCount(key))
					.description("Origin calls rejected because the pool was saturated, since JVM start")
					.tags(Tags.of("pool", poolKey))
					.register(registry);
		}
	}

	/**
	 * Picks up commands and pools that started serving after the exporter did. Called from the scrape
	 * endpoint and before an OTLP push, so a series never goes missing just because of startup order.
	 */
	public static void refresh()
	{
		FcMetricsExport local = instance;
		if (null == local)
			return;

		local.bindCommandMetrics();
		local.bindThreadPools();
		local.bindCacheMetrics();
		local.bindGuardMetrics();
	}

	// ---- config -------------------------------------------------------------

	private static OtlpConfig otlpConfig()
	{
		final String url = FCConfig.getProperty(OTLP_URL_KEY, "");
		final Duration step = parseStep(FCConfig.getProperty(OTLP_STEP_KEY, "60s"));
		final Map<String, String> headers = parseHeaders(FCConfig.getProperty(OTLP_HEADERS_KEY, ""));

		return new OtlpConfig() {
			@Override
			public String get(String key)
			{
				return null; // everything comes from the overrides below
			}

			@Override
			public String url()
			{
				return url;
			}

			@Override
			public Duration step()
			{
				return step;
			}

			@Override
			public Map<String, String> headers()
			{
				return headers;
			}
		};
	}

	static Duration parseStep(String value)
	{
		String trimmed = (null == value) ? "" : value.trim();
		try {
			if (trimmed.endsWith("ms"))
				return Duration.ofMillis(Long.parseLong(trimmed.substring(0, trimmed.length() - 2).trim()));
			if (trimmed.endsWith("s"))
				return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
			if (trimmed.endsWith("m"))
				return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1).trim()));
			return Duration.ofSeconds(Long.parseLong(trimmed));
		} catch (RuntimeException e) {
			logger.warn("Can't read " + OTLP_STEP_KEY + "=" + value + " - using 60s");
			return Duration.ofSeconds(60);
		}
	}

	/**
	 * {@code header1=value1,header2=value2}. Values are usually collector auth tokens, so they are never
	 * logged - only the header names, and only at debug.
	 */
	static Map<String, String> parseHeaders(String value)
	{
		Map<String, String> headers = new HashMap<String, String>();
		if (isEmpty(value))
			return headers;

		for (String pair : value.split(","))
		{
			int eq = pair.indexOf('=');
			if (eq < 1)
				continue;

			headers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
		}

		if (!headers.isEmpty())
			logger.debug("OTLP headers configured: " + headers.keySet());

		return headers;
	}

	private static long value(Long boxed)
	{
		return (null == boxed) ? 0 : boxed.longValue();
	}

	private static boolean isEmpty(String value)
	{
		return null == value || 0 == value.trim().length();
	}
}
