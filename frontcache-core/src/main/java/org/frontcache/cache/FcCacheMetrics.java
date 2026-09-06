/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache;

import java.util.concurrent.atomic.LongAdder;

/**
 * Cache read outcomes, counted per tier.
 *
 * <h2>Why these are counted here and not derived</h2>
 *
 * Hit ratio is the number an operator alerts on, and nothing in the process could produce it before
 * this class. The dashboard's {@code Cache-Hit} command counts cache <i>lookups</i> - every one of
 * them, hit or miss - because it measures the call, not the answer. The request log has the per-request
 * verdict but is a log, not a metric. So the counters are taken where the answer is actually known:
 * inside each {@code getFromCacheImpl}, which is the single read path every cache processor goes
 * through ({@code CacheProcessorBase.getFromCache} → {@code FC_ThroughCache} → the impl).
 *
 * <h2>Why the tier is part of it</h2>
 *
 * "Hit ratio 90%" and "hit ratio 90%, nine tenths of it off disk" call for different actions - the
 * second says L1 is undersized. Splitting hits by tier is what makes the number actionable, and it is
 * cheap: the tier is already known at the point the hit happens.
 *
 * <h2>Monotonic, never rolling</h2>
 *
 * Unlike {@link org.frontcache.resilience.FcCommandMetrics}, which keeps a rolling window because the
 * SSE dashboard needs a 10-second view, these only ever go up. Their consumer is Prometheus, which
 * computes its own windows with {@code rate()} - and a counter that decreases reads as a process
 * restart there. Nothing needs the rolling form, so it is not built.
 */
public class FcCacheMetrics {

	/** Where a cache read was answered from, or that it was not. */
	public enum Outcome {

		/** Served from the in-memory tier (L1, or the whole cache for the in-memory processor). */
		HIT_L1("hit", "L1"),

		/** Served from the on-disk tier (L2 - the Lucene index). */
		HIT_L2("hit", "L2"),

		/** Not cached: the caller goes to the origin. */
		MISS("miss", "none");

		private final String result;
		private final String tier;

		Outcome(String result, String tier) {
			this.result = result;
			this.tier = tier;
		}

		/** {@code hit} or {@code miss} - the label the hit ratio is computed from. */
		public String getResult() {
			return result;
		}

		/** {@code L1}, {@code L2}, or {@code none} for a miss. */
		public String getTier() {
			return tier;
		}
	}

	private static final LongAdder[] counts = newCounts();

	private FcCacheMetrics() { }

	private static LongAdder[] newCounts()
	{
		LongAdder[] adders = new LongAdder[Outcome.values().length];
		for (int i = 0; i < adders.length; i++)
			adders[i] = new LongAdder();

		return adders;
	}

	/**
	 * Records one cache read. Called on the request path, so it is a {@link LongAdder} increment and
	 * nothing else - no allocation, no lock, no branch on whether export is even enabled. Counting
	 * unconditionally keeps the numbers correct for a node that has export turned on later, and costs
	 * less than the check would.
	 */
	public static void record(Outcome outcome)
	{
		counts[outcome.ordinal()].increment();
	}

	/** Convenience for the common two-branch case: a resolved response, or null for a miss. */
	public static void recordHitOrMiss(Object cachedResponse, Outcome hitOutcome)
	{
		record((null == cachedResponse) ? Outcome.MISS : hitOutcome);
	}

	/** @return reads with this outcome since JVM start */
	public static long count(Outcome outcome)
	{
		return counts[outcome.ordinal()].sum();
	}

	/** Test seam - back to zero. */
	public static void reset()
	{
		for (LongAdder adder : counts)
			adder.reset();
	}

}
