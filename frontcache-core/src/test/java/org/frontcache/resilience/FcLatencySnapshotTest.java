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
import org.junit.Before;
import org.junit.Test;

/**
 * {@link FcCommandMetrics.LatencySnapshot} - the one-merge read of the rolling latency ring.
 *
 * Merging the ring allocates a Histogram sized for the maximum tracked latency at three significant
 * digits - on the order of ten thousand counters - and folds every bucket into it. It used to happen
 * once per {@code getLatencyMean()} and once per {@code getLatencyPercentiles()}, which meant four
 * merges per dashboard frame and seven per Prometheus scrape, per command.
 *
 * Collapsing that to one is only safe if the numbers are unchanged, so that is what this pins: the
 * snapshot must report exactly what the two separate accessors did, and its percentile map must keep
 * the exact key set and order the dashboard parses.
 */
public class FcLatencySnapshotTest {

	@Before
	public void setUp() {
		FcCommandMetrics.reset();
	}

	@After
	public void tearDown() {
		FcCommandMetrics.reset();
	}

	private static FcCommandMetrics withLatencies(String key, long... millis) {
		FcCommandMetrics metrics = FcCommandMetrics.getInstance(key, "test-domain", 10000);
		for (long ms : millis)
			metrics.recordLatency(ms);
		return metrics;
	}

	/**
	 * The equivalence that makes the change safe. Both accessors still exist and still work; the
	 * snapshot simply pays for the merge once instead of twice.
	 */
	@Test
	public void reportsExactlyWhatTheSeparateAccessorsDo() {
		long[] samples = new long[200];
		for (int i = 0; i < samples.length; i++)
			samples[i] = i + 1;

		FcCommandMetrics metrics = withLatencies("Snapshot-Equivalence", samples);

		FcCommandMetrics.LatencySnapshot snapshot = metrics.latencySnapshot();

		assertEquals("mean must match getLatencyMean()", metrics.getLatencyMean(), snapshot.getMean());
		assertEquals("percentiles must match getLatencyPercentiles()",
				metrics.getLatencyPercentiles(), snapshot.getPercentiles());
	}

	/** The dashboard indexes these keys directly and plots what it finds - shape matters as much as value. */
	@Test
	public void thePercentileMapKeepsItsExactKeySetAndOrder() {
		FcCommandMetrics metrics = withLatencies("Snapshot-Keys", 1, 5, 25, 100);

		assertEquals("[0, 25, 50, 75, 90, 95, 99, 99.5, 100]",
				metrics.latencySnapshot().getPercentiles().keySet().toString());
	}

	/** An untouched command must report zeros, not an empty map or a NaN mean. */
	@Test
	public void anEmptyWindowReportsZeros() {
		FcCommandMetrics metrics = FcCommandMetrics.getInstance("Snapshot-Empty", "test-domain", 10000);

		FcCommandMetrics.LatencySnapshot snapshot = metrics.latencySnapshot();

		assertEquals(0, snapshot.getMean());
		assertEquals("[0, 25, 50, 75, 90, 95, 99, 99.5, 100]", snapshot.getPercentiles().keySet().toString());
		for (Map.Entry<String, Long> percentile : snapshot.getPercentiles().entrySet())
			assertEquals(percentile.getKey() + "th percentile of nothing", Long.valueOf(0), percentile.getValue());
	}

	/** Sanity on the numbers themselves, so "one merge" cannot quietly become "merge of nothing". */
	@Test
	public void theNumbersAreTheRealOnes() {
		long[] samples = new long[100];
		for (int i = 0; i < samples.length; i++)
			samples[i] = i + 1; // 1..100

		FcCommandMetrics.LatencySnapshot snapshot = withLatencies("Snapshot-Values", samples).latencySnapshot();

		assertTrue("mean of 1..100 should sit near 50, got " + snapshot.getMean(),
				Math.abs(snapshot.getMean() - 50) <= 2);

		Map<String, Long> percentiles = snapshot.getPercentiles();
		assertTrue("p50 near 50, got " + percentiles.get("50"),
				Math.abs(percentiles.get("50") - 50) <= 2);
		assertTrue("p100 is the max, got " + percentiles.get("100"),
				Math.abs(percentiles.get("100") - 100) <= 2);
		assertTrue("percentiles must be non-decreasing",
				percentiles.get("50") <= percentiles.get("90")
						&& percentiles.get("90") <= percentiles.get("100"));
	}

	/**
	 * A snapshot is a value, not a view. Callers hold one across a whole dashboard frame or scrape, so
	 * later recordings must not change what it already reported.
	 */
	@Test
	public void aSnapshotIsImmutableOnceTaken() {
		FcCommandMetrics metrics = withLatencies("Snapshot-Immutable", 10, 10, 10);

		FcCommandMetrics.LatencySnapshot taken = metrics.latencySnapshot();
		long meanBefore = taken.getMean();
		Long p100Before = taken.getPercentiles().get("100");

		for (int i = 0; i < 100; i++)
			metrics.recordLatency(5000);

		assertEquals("the held snapshot must not move", meanBefore, taken.getMean());
		assertEquals("the held snapshot must not move", p100Before, taken.getPercentiles().get("100"));
		assertTrue("...while a fresh one does", metrics.latencySnapshot().getMean() > meanBefore);
	}
}
