/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import org.frontcache.FCConfig;
import org.frontcache.hystrix.FcCommand;
import org.frontcache.hystrix.FcCommandMetrics;
import org.frontcache.hystrix.FcTestSupport;
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
	}

	private static void enable(String mode)
	{
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, mode);
		FcMetricsExport.start();
	}

	private static void executeSomeTraffic()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command.Cache-Origin-http.execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.threadpool.OriginHitsPool.coreSize", "2");
		FcTestSupport.installConfig(props);

		new Ok("Cache-Origin-http").execute();
		new Boom("Cache-Origin-http").execute();
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
		assertTrue("command key must be the label", scrape.contains("command=\"Cache-Origin-http\""));

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
		assertTrue("pool label missing", scrape.contains("pool=\"OriginHitsPool\""));
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

		TreeSet<String> allowed = new TreeSet<String>(java.util.Arrays.asList(
				"command", "event", "quantile", "pool", "name", "kind", "state"));

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

		FcCommandMetrics metrics = FcCommandMetrics.existing("Cache-Origin-http");
		assertEquals(1, metrics.getTotalCount(org.frontcache.hystrix.FcEventType.SUCCESS));

		// Rotate the whole rolling window out: the dashboard's number goes to zero, the exported one
		// must not - otherwise every rate() query over it is wrong.
		for (int i = 0; i < 12; i++)
			FcTestSupport.rotate(metrics);

		assertEquals("rolling window has moved on", 0,
				metrics.getRollingCount(org.frontcache.hystrix.FcEventType.SUCCESS));
		assertEquals("but the exported counter is monotonic", 1,
				metrics.getTotalCount(org.frontcache.hystrix.FcEventType.SUCCESS));
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

	// ---- fixtures -----------------------------------------------------------

	private static class Ok extends FcCommand<String> {
		Ok(String key) { super(Setter.withGroupKey("test.example.com").andCommandKey(key).andThreadPoolKey("OriginHitsPool")); }
		@Override protected String run() { return "ok"; }
	}

	private static class Boom extends FcCommand<String> {
		Boom(String key) { super(Setter.withGroupKey("test.example.com").andCommandKey(key).andThreadPoolKey("OriginHitsPool")); }
		@Override protected String run() { throw new RuntimeException("origin down"); }
		@Override protected String getFallback() { return "fallback"; }
	}
}
