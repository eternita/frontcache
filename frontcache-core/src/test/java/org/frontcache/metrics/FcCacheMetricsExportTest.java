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
import static org.junit.Assert.assertTrue;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.cache.FcCacheMetrics;
import org.frontcache.resilience.FcTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.micrometer.core.instrument.Meter;

/**
 * Cache metrics in the Prometheus export.
 *
 * Hit ratio, eviction rate and index size are what an operator alerts on, and none of them are
 * resilience data - a page cache that has quietly stopped hitting looks perfectly healthy through
 * circuit-breaker metrics, because every origin call succeeds.
 */
public class FcCacheMetricsExportTest {

	@Before
	public void setUp()
	{
		FcCacheMetrics.reset();
	}

	@After
	public void tearDown()
	{
		FcMetricsExport.stop();
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FcCacheMetrics.reset();
		FcTestSupport.resetAll();
	}

	private static void enable()
	{
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();
		FcMetricsExport.refresh();
	}

	// ---- the counters themselves ---------------------------------------------------------------

	@Test
	public void readsAreCountedPerTier()
	{
		FcCacheMetrics.record(FcCacheMetrics.Outcome.HIT_L1);
		FcCacheMetrics.record(FcCacheMetrics.Outcome.HIT_L1);
		FcCacheMetrics.record(FcCacheMetrics.Outcome.HIT_L2);
		FcCacheMetrics.record(FcCacheMetrics.Outcome.MISS);

		assertEquals(2, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L1));
		assertEquals(1, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L2));
		assertEquals(1, FcCacheMetrics.count(FcCacheMetrics.Outcome.MISS));
	}

	/** The two-branch helper the read paths actually call: null means the origin is next. */
	@Test
	public void aNullResponseIsAMissAndAnythingElseIsAHit()
	{
		FcCacheMetrics.recordHitOrMiss(null, FcCacheMetrics.Outcome.HIT_L2);
		FcCacheMetrics.recordHitOrMiss("a response", FcCacheMetrics.Outcome.HIT_L2);

		assertEquals(1, FcCacheMetrics.count(FcCacheMetrics.Outcome.MISS));
		assertEquals(1, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L2));
		assertEquals("a hit must not also count as an L1 hit",
				0, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L1));
	}

	/**
	 * Counting is unconditional - it does not check whether export is enabled. So a node that has export
	 * switched on later reports real totals rather than starting from whenever the exporter woke up.
	 */
	@Test
	public void countingHappensWhetherOrNotExportIsOn()
	{
		FcCacheMetrics.record(FcCacheMetrics.Outcome.HIT_L1);

		assertFalse(FcMetricsExport.isEnabled());
		assertEquals(1, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L1));

		enable();

		assertTrue("a total taken before the exporter started must still be exported",
				scrape().contains("frontcache_cache_reads_total{result=\"hit\",tier=\"L1\"} 1.0"));
	}

	// ---- the exported series -------------------------------------------------------------------

	@Test
	public void hitRatioIsDerivableFromTheExportedSeries()
	{
		FcCacheMetrics.record(FcCacheMetrics.Outcome.HIT_L1);
		FcCacheMetrics.record(FcCacheMetrics.Outcome.HIT_L2);
		FcCacheMetrics.record(FcCacheMetrics.Outcome.MISS);
		enable();

		String scrape = scrape();

		// hit ratio = sum(result="hit") / sum(all) - so both labels have to be on the wire, and the two
		// tiers have to be separable, or "90% hit ratio, all of it off disk" is indistinguishable from
		// "90% hit ratio, all of it in memory".
		assertTrue(scrape, scrape.contains("frontcache_cache_reads_total{result=\"hit\",tier=\"L1\"} 1.0"));
		assertTrue(scrape, scrape.contains("frontcache_cache_reads_total{result=\"hit\",tier=\"L2\"} 1.0"));
		assertTrue(scrape, scrape.contains("frontcache_cache_reads_total{result=\"miss\",tier=\"none\"} 1.0"));
	}

	/**
	 * A counter, not a gauge - the same reason as the command events: Prometheus reads a counter's drop
	 * to zero as a restart and a gauge's as a real decrease, so as a gauge every restart would render as
	 * a large negative rate().
	 */
	@Test
	public void readCountersAreCountersNotGauges()
	{
		enable();

		boolean found = false;
		for (Meter meter : FcMetricsExport.registry().getMeters())
			if ("frontcache.cache.reads".equals(meter.getId().getName()))
			{
				found = true;
				assertEquals("cache reads must be a counter so restarts read as resets",
						Meter.Type.COUNTER, meter.getId().getType());
			}

		assertTrue("no cache read series registered at all", found);
	}

	/**
	 * The read counters must appear even before a cache processor exists. A zero here means "no reads
	 * yet", which is true; a missing series would look like the feature is not there.
	 */
	@Test
	public void readSeriesExistWithNoCacheProcessor()
	{
		enable();

		assertTrue(scrape().contains("frontcache_cache_reads_total"));
	}

	// ---- "unknown" is not zero -----------------------------------------------------------------

	/**
	 * A processor that does not track a number must produce NO series for it, not a zero. A gauge
	 * reading a flat zero is a line somebody eventually alerts on, and it would be measuring nothing.
	 *
	 * Asserted as an invariant over whatever is registered rather than as "these series are absent",
	 * because whether a cache processor exists is JVM-wide state this test does not own - another test
	 * in the same fork could create one. The invariant holds either way, which is what makes it worth
	 * asserting.
	 */
	@Test
	public void everyRegisteredSizeSeriesHasATrackedNumberBehindIt()
	{
		assertEquals(CacheStats.UNKNOWN, CacheStats.NOT_TRACKED.getL1Entries());
		assertEquals(CacheStats.UNKNOWN, CacheStats.NOT_TRACKED.getL1Evictions());
		assertEquals(CacheStats.UNKNOWN, CacheStats.NOT_TRACKED.getL2Entries());
		assertEquals(CacheStats.UNKNOWN, CacheStats.NOT_TRACKED.getL2DiskBytes());

		enable();

		CacheProcessor processor = CacheManager.existingInstance();
		CacheStats stats = (null == processor) ? CacheStats.NOT_TRACKED : processor.getCacheStats();

		assertEquals("an L1 entry gauge exists exactly when L1 entries are tracked",
				stats.isTracked(stats.getL1Entries()), hasSeries("frontcache.cache.entries", "tier", "L1"));
		assertEquals("an L2 entry gauge exists exactly when L2 entries are tracked",
				stats.isTracked(stats.getL2Entries()), hasSeries("frontcache.cache.entries", "tier", "L2"));
		assertEquals("a disk-size gauge exists exactly when the on-disk size is tracked",
				stats.isTracked(stats.getL2DiskBytes()), hasSeries("frontcache.cache.disk.bytes", "tier", "L2"));
		assertEquals("an eviction counter exists exactly when evictions are tracked",
				stats.isTracked(stats.getL1Evictions()), hasSeries("frontcache.cache.evictions", "tier", "L1"));
	}

	private static boolean hasSeries(String name, String tagKey, String tagValue)
	{
		for (Meter meter : FcMetricsExport.registry().getMeters())
			if (name.equals(meter.getId().getName()) && tagValue.equals(meter.getId().getTag(tagKey)))
				return true;

		return false;
	}

	/** UNKNOWN has to be distinguishable from a real zero, or the rule above cannot be applied. */
	@Test
	public void unknownIsNotConfusableWithZero()
	{
		CacheStats empty = new CacheStats(0, 0, 0, 0);

		assertTrue("a genuinely empty cache reports zero", empty.isTracked(empty.getL1Entries()));
		assertFalse("an untracked number is not zero",
				CacheStats.NOT_TRACKED.isTracked(CacheStats.NOT_TRACKED.getL1Entries()));
	}

	private static String scrape()
	{
		FcMetricsExport.refresh();
		String scrape = FcMetricsExport.scrape();
		assertNotNull("prometheus export is not enabled", scrape);
		return scrape;
	}

}
