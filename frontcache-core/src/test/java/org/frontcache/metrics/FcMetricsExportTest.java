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
package org.frontcache.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import org.frontcache.FCConfig;
import org.frontcache.guard.GuardAction;
import org.frontcache.guard.GuardPredicate;
import org.frontcache.guard.GuardRule;
import org.frontcache.include.combine.FcCombineMetrics;
import org.frontcache.resilience.FcCommand;
import org.frontcache.resilience.FcCommandMetrics;
import org.frontcache.resilience.FcTestSupport;
import org.junit.After;
import org.junit.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;

/**
 * The Prometheus / OTLP exporter (proposal section 7.5).
 *
 * Three of these matter more than the rest: export is OFF unless asked for, every series is prefixed,
 * and nothing is ever labelled by URL. The first is an upgrade-safety property, the third is the one
 * that turns an observability feature into an outage if it is got wrong.
 */
public class FcMetricsExportTest {

	@After
	public void tearDown()
	{
		FcMetricsExport.stop();
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FcTestSupport.resetAll();
		FcCombineMetrics.reset();
	}

	private static void enable(String mode)
	{
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, mode);
		FcMetricsExport.start();
	}

	private static void executeSomeTraffic()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command.Cache-Miss.execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.threadpool.OriginPool.coreSize", "2");
		FcTestSupport.installConfig(props);

		new Ok("Cache-Miss").execute();
		new Boom("Cache-Miss").execute();
	}

	/** The upgrade-safety property: nothing starts unless someone asked for it. */
	@Test
	public void exportIsOffByDefault()
	{
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FcMetricsExport.start();

		assertFalse("a node must not start a listener or a push loop just because it was upgraded",
				FcMetricsExport.isEnabled());
		assertNull(FcMetricsExport.scrape());
		assertNull(FcMetricsExport.registry());
	}

	@Test
	public void unrecognizedModeStaysOff()
	{
		enable("promethius");

		assertFalse("a typo must not be interpreted as 'on'", FcMetricsExport.isEnabled());
	}

	/** OTLP with no endpoint would log a delivery failure every step; it stays off instead. */
	@Test
	public void otlpWithoutAUrlStaysOff()
	{
		FCConfig.setProperty(FcMetricsExport.OTLP_URL_KEY, "");
		enable("otlp");

		assertFalse(FcMetricsExport.isEnabled());
	}

	@Test
	public void prometheusScrapeCarriesTheCommandTaxonomy()
	{
		enable("prometheus");
		executeSomeTraffic();
		FcMetricsExport.refresh();

		String scrape = FcMetricsExport.scrape();
		assertNotNull(scrape);

		// The event taxonomy is the thing Resilience4j's own metrics do not carry, and the reason
		// FcCommandMetrics is bridged rather than replaced.
		assertTrue("no command event series in:\n" + scrape, scrape.contains("frontcache_command_events"));
		assertTrue("success not exported", scrape.contains("event=\"success\""));
		assertTrue("failure not exported", scrape.contains("event=\"failure\""));
		assertTrue("fallback-success not exported", scrape.contains("event=\"fallback-success\""));
		assertTrue("command key must be the label", scrape.contains("command=\"Cache-Miss\""));

		assertTrue("latency percentiles not exported", scrape.contains("frontcache_command_latency_seconds"));
		assertTrue("p99 not exported", scrape.contains("quantile=\"99\""));
		assertTrue("concurrency gauge not exported",
				scrape.contains("frontcache_command_concurrent_executions"));

		// Resilience4j's own binders, renamed under our prefix.
		assertTrue("circuit breaker state not exported: the R4j binder is not bound",
				scrape.contains("frontcache_resilience4j_circuitbreaker"));

		// Thread pools - including the rejection count, which ExecutorServiceMetrics does not provide and
		// which is what an operator alerts on.
		assertTrue("thread pool rejections not exported", scrape.contains("frontcache_threadpool_rejections"));
		assertTrue("pool label missing", scrape.contains("pool=\"OriginPool\""));
	}

	/**
	 * The JVM numbers the console shows per edge - heap, process CPU, uptime - reaching Prometheus, so
	 * the panel and the dashboards agree about a node rather than each holding half the picture.
	 *
	 * These are bound at startup, not on refresh, so this deliberately does NOT refresh first: if they
	 * only appeared after one, a scrape taken before any traffic would be missing them.
	 */
	@Test
	public void jvmHeapCpuAndUptimeAreExported()
	{
		enable("prometheus");

		String scrape = FcMetricsExport.scrape();

		assertTrue("heap not exported:\n" + scrape, scrape.contains("frontcache_jvm_memory_used_bytes"));
		assertTrue("the heap/non-heap split is what makes the number readable",
				scrape.contains("area=\"heap\""));
		assertTrue("process cpu not exported", scrape.contains("frontcache_process_cpu_usage"));
		assertTrue("uptime not exported", scrape.contains("frontcache_process_uptime_seconds"));
	}

	/**
	 * The JVM binders are the one part of the export not driven by traffic, so they are also the part
	 * that could quietly bind twice - once at startup and again on every scrape.
	 */
	@Test
	public void repeatedRefreshesDoNotDuplicateJvmSeries()
	{
		enable("prometheus");

		int before = countMeters("frontcache.jvm.memory.used");
		FcMetricsExport.refresh();
		FcMetricsExport.refresh();

		assertTrue("there must be some to begin with", before > 0);
		assertEquals("a refresh must not add a second copy of a JVM gauge",
				before, countMeters("frontcache.jvm.memory.used"));
	}

	private static int countMeters(String name)
	{
		int found = 0;
		for (Meter meter : FcMetricsExport.registry().getMeters())
			if (name.equals(meter.getId().getName()))
				found++;

		return found;
	}

	/** Section 7.5: one prefix for the whole set, so it is findable in a single query. */
	@Test
	public void everySeriesIsPrefixed()
	{
		enable("prometheus");
		executeSomeTraffic();
		FcMetricsExport.refresh();

		for (Meter meter : FcMetricsExport.registry().getMeters())
			assertTrue("unprefixed series: " + meter.getId().getName(),
					meter.getId().getName().startsWith("frontcache."));
	}

	/**
	 * No module, no module series. The license and cache-warmer series are the Pro module's (bound through
	 * {@code FrontcacheModule.bindMeters}, docs/ip-protection-apache-core-and-prop-pro-proposal.md section
	 * 4.3) and frontcache-pro's {@code ProMetersTest} asserts them; this module's test class path has no
	 * Pro jar, which is a Community node.
	 */
	@Test
	public void aNodeWithNoModuleExportsNoModuleSeries()
	{
		enable("prometheus");

		String scrape = FcMetricsExport.scrape();
		assertTrue(scrape, scrape.contains("frontcache_"));
		assertFalse(scrape, scrape.contains("frontcache_license_"));
		assertFalse(scrape, scrape.contains("frontcache_warmer_"));
	}

	/**
	 * The cardinality guard. {@code RequestLogger} has URL-level data and labelling with it would be
	 * tempting; on a page cache the URL space is unbounded by construction, so a per-URL label is not an
	 * observability feature but a way to take down a Prometheus server. Asserted as an allowlist of tag
	 * KEYS so a new label has to be added here deliberately.
	 */
	@Test
	public void labelsAreBoundedAndNeverDerivedFromUrls()
	{
		enable("prometheus");
		executeSomeTraffic();
		FcMetricsExport.refresh();

		// Reviewed: every one of these has a small fixed value set fixed at compile time.
		//   result -> hit|miss          tier -> L1|L2|none        (FcCacheMetrics.Outcome, 3 combinations)
		//   command -> the five FC_* keys   event -> FcEventType   quantile -> a fixed list
		//   pool -> the named thread pools  name/kind/state -> resilience4j's own, one per breaker
		//   name -> ALSO the guard rule / rate-limit bucket names, which come from a config file and are
		//           therefore bounded by it; result -> ALSO the guard dispositions (a fixed set of four)
		//   area -> heap|nonheap        id -> JVM memory pool names   (JvmMemoryMetrics)
		// area and id come from the JVM, not from anything request-shaped: the memory pool set is fixed
		// by the collector at startup and is under a dozen entries, so it cannot grow with traffic -
		// which is the property this list exists to protect.
		TreeSet<String> allowed = new TreeSet<String>(java.util.Arrays.asList(
				"command", "event", "quantile", "pool", "name", "kind", "state", "result", "tier",
				"area", "id"));

		TreeSet<String> seen = new TreeSet<String>();
		for (Meter meter : FcMetricsExport.registry().getMeters())
			for (Tag tag : meter.getId().getTags())
			{
				seen.add(tag.getKey());

				assertFalse("tag value looks like a URL - " + tag.getKey() + "=" + tag.getValue(),
						tag.getValue().contains("://") || tag.getValue().startsWith("/"));
			}

		TreeSet<String> unexpected = new TreeSet<String>(seen);
		unexpected.removeAll(allowed);
		assertEquals("new label keys must be reviewed for cardinality before being allowed: " + unexpected,
				"[]", unexpected.toString());
	}

	/**
	 * Event and rejection series are counters, not gauges. Prometheus treats a counter dropping to zero as
	 * a process restart and a gauge dropping as a real decrease - as gauges, every restart would render as
	 * a large negative rate().
	 */
	@Test
	public void monotonicSeriesAreCountersNotGauges()
	{
		enable("prometheus");
		executeSomeTraffic();
		FcMetricsExport.refresh();

		for (Meter meter : FcMetricsExport.registry().getMeters())
		{
			String name = meter.getId().getName();
			if (name.equals("frontcache.command.events") || name.equals("frontcache.threadpool.rejections"))
				assertEquals(name + " must be a counter so restarts read as resets",
						Meter.Type.COUNTER, meter.getId().getType());
		}

		assertTrue("counters carry the conventional _total suffix in the exposition",
				FcMetricsExport.scrape().contains("frontcache_command_events_total"));
	}

	/** Prometheus wants a series that only goes up; the dashboard wants a 10-second window. Both exist. */
	@Test
	public void exportedCountersAreMonotonicNotRolling() throws Exception
	{
		enable("prometheus");
		executeSomeTraffic();
		FcMetricsExport.refresh();

		FcCommandMetrics metrics = FcCommandMetrics.existing("Cache-Miss");
		assertEquals(1, metrics.getTotalCount(org.frontcache.resilience.FcEventType.SUCCESS));

		// Rotate the whole rolling window out: the dashboard's number goes to zero, the exported one
		// must not - otherwise every rate() query over it is wrong.
		for (int i = 0; i < 12; i++)
			FcTestSupport.rotate(metrics);

		assertEquals("rolling window has moved on", 0,
				metrics.getRollingCount(org.frontcache.resilience.FcEventType.SUCCESS));
		assertEquals("but the exported counter is monotonic", 1,
				metrics.getTotalCount(org.frontcache.resilience.FcEventType.SUCCESS));
	}

	@Test
	public void otlpStepAndHeadersAreParsed()
	{
		assertEquals(Duration.ofSeconds(60), FcMetricsExport.parseStep("60s"));
		assertEquals(Duration.ofMinutes(5), FcMetricsExport.parseStep("5m"));
		assertEquals(Duration.ofMillis(500), FcMetricsExport.parseStep("500ms"));
		assertEquals("a bare number means seconds", Duration.ofSeconds(30), FcMetricsExport.parseStep("30"));
		assertEquals("garbage falls back to the default rather than failing startup",
				Duration.ofSeconds(60), FcMetricsExport.parseStep("every minute"));

		Map<String, String> headers = FcMetricsExport.parseHeaders("Authorization=Bearer abc,X-Scope=team");
		assertEquals(2, headers.size());
		assertEquals("Bearer abc", headers.get("Authorization"));
		assertEquals("team", headers.get("X-Scope"));
		assertTrue(FcMetricsExport.parseHeaders("").isEmpty());
		assertTrue(FcMetricsExport.parseHeaders(null).isEmpty());
	}

	// ---- combine-and-reduce --------------------------------------------------------------

	/**
	 * The combine series and their label vocabulary (docs/archive/combine-reduce-proposal.md section 12).
	 *
	 * The pair that carries the feature's whole claim is asserted by value, not merely by presence:
	 * members{result="combined"} over batches{result="ok"} is the average number of fragments one
	 * origin request served, and it is the only place that ratio exists.
	 */
	@Test
	public void combineSeriesAreExportedWithTheirOutcomeLabels()
	{
		FcCombineMetrics.record(FcCombineMetrics.Batch.OK);
		FcCombineMetrics.record(FcCombineMetrics.Member.COMBINED, 3);
		FcCombineMetrics.record(FcCombineMetrics.Member.CACHED, 12);
		FcCombineMetrics.record(FcCombineMetrics.Batch.UNSUPPORTED);
		FcCombineMetrics.record(FcCombineMetrics.Batch.ERROR);
		FcCombineMetrics.record(FcCombineMetrics.Error.ECHO);

		enable("prometheus");
		String scrape = FcMetricsExport.scrape();

		assertTrue("no combine batch series in:\n" + scrape,
				scrape.contains("frontcache_include_combine_batches_total"));
		assertTrue("no combine member series", scrape.contains("frontcache_include_combine_members_total"));
		assertTrue("no combine error series", scrape.contains("frontcache_include_combine_errors_total"));

		assertTrue("ok batches not exported",
				scrape.contains("frontcache_include_combine_batches_total{result=\"ok\"} 1"));
		assertTrue("the members-per-batch numerator is wrong",
				scrape.contains("frontcache_include_combine_members_total{result=\"combined\"} 3"));
		assertTrue("an origin with no combine handler must be visible",
				scrape.contains("frontcache_include_combine_batches_total{result=\"unsupported\"} 1"));
		assertTrue("the series to alert on carries WHICH way the envelope was malformed",
				scrape.contains("frontcache_include_combine_errors_total{kind=\"echo\"} 1"));
	}

	/**
	 * Bound whether or not anything has combined yet. A zero here is a true statement - and on a node
	 * whose markup was supposed to have been switched over, the absence of the series and a flat zero
	 * mean very different things.
	 */
	@Test
	public void combineSeriesExistBeforeAnythingHasBeenCombined()
	{
		enable("prometheus");

		String scrape = FcMetricsExport.scrape();

		assertTrue("a node that has not combined yet must still report zeros:\n" + scrape,
				scrape.contains("frontcache_include_combine_batches_total{result=\"ok\"} 0"));
		assertTrue(scrape.contains("frontcache_include_combine_members_total{result=\"individual\"} 0"));
	}

	/** Restarts must read as resets, not as a large negative rate. */
	@Test
	public void combineSeriesAreCountersNotGauges()
	{
		enable("prometheus");

		int seen = 0;
		for (Meter meter : FcMetricsExport.registry().getMeters())
			if (meter.getId().getName().startsWith("frontcache.include.combine."))
			{
				seen++;
				assertEquals(meter.getId().getName() + " must be a counter",
						Meter.Type.COUNTER, meter.getId().getType());
			}

		assertEquals("four batch outcomes, four member outcomes, three error kinds", 11, seen);
	}

	// ---- fixtures -----------------------------------------------------------

	private static class Ok extends FcCommand<String> {
		Ok(String key) { super(Setter.withGroupKey("test.example.com").andCommandKey(key).andThreadPoolKey("OriginPool")); }
		@Override protected String run() { return "ok"; }
	}

	private static class Boom extends FcCommand<String> {
		Boom(String key) { super(Setter.withGroupKey("test.example.com").andCommandKey(key).andThreadPoolKey("OriginPool")); }
		@Override protected String run() { throw new RuntimeException("origin down"); }
		@Override protected String getFallback() { return "fallback"; }
	}

	// ---- guard rules -----------------------------------------------------------------------

	/**
	 * The label vocabulary of the guard series, which is deliberately the LOG's vocabulary: a rule's
	 * {@code result} label reads the same as the disposition column written to
	 * frontcache-failed-requests.log, so a Grafana panel and a Kibana panel name the same event the
	 * same way. Wiring the series to a running engine is covered end to end by
	 * StandaloneMetricsExportTests - here only the mapping, which is where a silent divergence would
	 * start.
	 */
	@Test
	public void guardRuleOutcomeLabelsUseTheSameWordsAsTheLog()
	{
		assertEquals(GuardAction.DISPOSITION_REJECTED, FcMetricsExport.resultOf(rule(GuardAction.reject(429, "no"), false)));
		assertEquals(GuardAction.DISPOSITION_REDIRECTED, FcMetricsExport.resultOf(rule(GuardAction.redirect(302, "/x"), false)));

		// dry-run wins over the action: the rule did not do the thing its action describes
		assertEquals(GuardAction.DISPOSITION_DRY_RUN, FcMetricsExport.resultOf(rule(GuardAction.reject(429, "no"), true)));

		// the one label with no log counterpart - an 'allow' rule writes no line, but "is this
		// exemption doing anything?" is still worth a series
		assertEquals("allowed", FcMetricsExport.resultOf(rule(GuardAction.allow(), false)));
	}

	@Test
	public void guardSeriesAreAbsentUntilAnEngineExists()
	{
		// A scrape must never be what builds the engine, so with none built there is simply nothing to
		// report - not a zero-valued series standing in for a node that has not started.
		enable("prometheus");
		FcMetricsExport.refresh();

		for (Meter meter : FcMetricsExport.registry().getMeters())
			assertFalse("guard series without an engine: " + meter.getId().getName(),
					meter.getId().getName().startsWith("frontcache.guard."));
	}

	private static GuardRule rule(GuardAction action, boolean dryRun)
	{
		GuardPredicate always = new GuardPredicate() {
			@Override
			public boolean test(org.frontcache.core.RequestContext context) {
				return true;
			}

			@Override
			public String describe() {
				return "always";
			}
		};

		return new GuardRule("a-rule", Arrays.asList(always), action, dryRun);
	}

}
