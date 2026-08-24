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
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.cache.FcCacheMetrics;
import org.frontcache.resilience.FcCommandMetrics;
import org.frontcache.resilience.FcEventType;
import org.frontcache.resilience.FcThreadPools;
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

		Gauge.builder("command.latency.seconds", metrics, m -> m.getLatencyMean() / 1000.0)
				.description("Mean command latency over the rolling window")
				.tags(commandTag.and("quantile", "mean"))
				.strongReference(true)
				.register(registry);

		for (final String percentile : new String[] { "50", "90", "95", "99", "99.5", "100" })
			Gauge.builder("command.latency.seconds", metrics,
							m -> value(m.getLatencyPercentiles().get(percentile)) / 1000.0)
					.description("Command latency percentile over the rolling window")
					.tags(commandTag.and("quantile", percentile))
					.strongReference(true)
					.register(registry);
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
