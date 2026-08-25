/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Test;

/**
 * The rolling-window and latency machinery that produces the dashboard's numbers.
 *
 * These are the fields Resilience4j's own metrics cannot supply - per-event-type rolling counters and
 * latency percentiles - which is why Frontcache owns them (proposal section 7.3).
 */
public class FcCommandMetricsTest {

	@After
	public void tearDown()
	{
		FcCommandMetrics.reset();
	}

	private static FcCommandMetrics metrics(String key, int rollingStatsMs)
	{
		return FcCommandMetrics.getInstance(key, "test.example.com", rollingStatsMs);
	}

	@Test
	public void eventsAccumulateAcrossTheWindowAndFallOutOfIt()
	{
		FcCommandMetrics metrics = metrics("Rolling", 3000);   // 3 one-second buckets

		metrics.markEvent(FcEventType.SUCCESS);
		metrics.markEvent(FcEventType.SUCCESS);
		metrics.markEvent(FcEventType.FAILURE);

		assertEquals(2, metrics.getRollingCount(FcEventType.SUCCESS));
		assertEquals(1, metrics.getRollingCount(FcEventType.FAILURE));

		// Rotating a full window's worth of buckets retires everything recorded above.
		metrics.rotate();
		assertEquals("still inside the window", 2, metrics.getRollingCount(FcEventType.SUCCESS));
		metrics.rotate();
		metrics.rotate();
		assertEquals("rotated out of the window", 0, metrics.getRollingCount(FcEventType.SUCCESS));
		assertEquals(0, metrics.getRollingCount(FcEventType.FAILURE));
	}

	/**
	 * Hystrix's HealthCounts shape: successes plus the outcomes that mean the dependency call did not
	 * complete, and short-circuits deliberately excluded. Counting short-circuits would pin an open
	 * circuit's error rate at 100% and stop it recovering.
	 */
	@Test
	public void errorPercentageMatchesHystrixHealthCounts()
	{
		FcCommandMetrics metrics = metrics("Health", 10000);

		for (int i = 0; i < 3; i++)
			metrics.markEvent(FcEventType.SUCCESS);
		metrics.markEvent(FcEventType.FAILURE);

		assertEquals(4, metrics.getRollingTotalRequests());
		assertEquals(1, metrics.getRollingErrorCount());
		assertEquals(25, metrics.getErrorPercentage());

		metrics.markEvent(FcEventType.SHORT_CIRCUITED);
		assertEquals("short-circuits are not in the health counts", 4, metrics.getRollingTotalRequests());
		assertEquals(25, metrics.getErrorPercentage());

		metrics.markEvent(FcEventType.TIMEOUT);
		metrics.markEvent(FcEventType.SEMAPHORE_REJECTED);
		metrics.markEvent(FcEventType.THREAD_POOL_REJECTED);
		assertEquals(7, metrics.getRollingTotalRequests());
		assertEquals(4, metrics.getRollingErrorCount());
	}

	@Test
	public void emptyMetricsReadAsZeroRatherThanThrowing()
	{
		FcCommandMetrics metrics = metrics("Empty", 10000);

		assertEquals(0, metrics.getErrorPercentage());
		assertEquals(0, metrics.getLatencyMean());
		assertEquals(0, metrics.getCurrentConcurrentExecutionCount());
		for (Map.Entry<String, Long> percentile : metrics.getLatencyPercentiles().entrySet())
			assertEquals("percentile " + percentile.getKey(), Long.valueOf(0), percentile.getValue());
	}

	/**
	 * The dashboard divides and plots this map directly, so its key set is part of the wire contract -
	 * see DashboardJsonKeysTest.
	 */
	@Test
	public void latencyPercentilesHaveTheShapeTheDashboardExpects()
	{
		FcCommandMetrics metrics = metrics("Latency", 10000);

		for (int i = 1; i <= 100; i++)
			metrics.recordLatency(i);

		assertEquals("[0, 25, 50, 75, 90, 95, 99, 99.5, 100]", metrics.getLatencyPercentiles().keySet().toString());

		Map<String, Long> percentiles = metrics.getLatencyPercentiles();
		assertTrue("p50 of 1..100 should sit near 50, got " + percentiles.get("50"),
				Math.abs(percentiles.get("50").longValue() - 50) <= 2);
		assertTrue("p100 is the max, got " + percentiles.get("100"), percentiles.get("100").longValue() >= 100);
		assertTrue("mean of 1..100 should sit near 50, got " + metrics.getLatencyMean(),
				Math.abs(metrics.getLatencyMean() - 50) <= 2);
	}

	@Test
	public void concurrencyGaugeTracksInFlightExecutions()
	{
		FcCommandMetrics metrics = metrics("Concurrency", 10000);

		long first = metrics.markStart(0, "first");
		long second = metrics.markStart(0, "second");
		assertEquals(2, metrics.getCurrentConcurrentExecutionCount());

		metrics.markEnd(first);
		assertEquals(1, metrics.getCurrentConcurrentExecutionCount());
		metrics.markEnd(second);
		assertEquals(0, metrics.getCurrentConcurrentExecutionCount());
	}

	/**
	 * A request that hangs must produce one log line and one counter increment, not one per sweep -
	 * otherwise a single stuck request at a 250ms cadence writes 14,400 lines an hour.
	 */
	@Test
	public void watchdogReportsEachOverrunExactlyOnce() throws Exception
	{
		FcCommandMetrics metrics = metrics("Sweep-Once", 10000);

		long execution = metrics.markStart(50, "GET /slow.jsp");
		Thread.sleep(120);

		metrics.sweepOverruns();
		metrics.sweepOverruns();
		metrics.sweepOverruns();

		assertEquals(1, metrics.getRollingCount(FcEventType.BUDGET_OVERRUN_OBSERVED));

		metrics.markEnd(execution);
		metrics.sweepOverruns();
		assertEquals("nothing left in flight to report", 1, metrics.getRollingCount(FcEventType.BUDGET_OVERRUN_OBSERVED));
	}

	@Test
	public void executionsWithNoBudgetAreNotWatched() throws Exception
	{
		FcCommandMetrics metrics = metrics("No-Budget", 10000);

		// budgetMs 0 = the thread path, which has a real TimeLimiter; watching it too would report the
		// same overrun twice.
		metrics.markStart(0, "thread-isolated");
		Thread.sleep(50);
		metrics.sweepOverruns();

		assertEquals(0, metrics.getRollingCount(FcEventType.BUDGET_OVERRUN_OBSERVED));
	}

	@Test
	public void instancesAreKeyedByCommandKeyAndListable()
	{
		FcCommandMetrics first = metrics("Listed-One", 10000);
		FcCommandMetrics second = metrics("Listed-Two", 10000);

		assertTrue(first == metrics("Listed-One", 10000));
		assertTrue(FcCommandMetrics.all().contains(first));
		assertTrue(FcCommandMetrics.all().contains(second));
		assertEquals(2, FcCommandMetrics.all().size());
	}
}
