/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

/**
 * A cheap snapshot of the JVM this node runs in: heap, process CPU, uptime.
 *
 * The companion of {@link org.frontcache.cache.CacheStats} - that one answers "what is in the cache",
 * this one answers "how is the process holding up" - and it follows the same two rules, for the same
 * reason. Everything is read from beans the JVM already maintains, because this is polled by a console
 * that never stops polling; and every field can be {@link #UNKNOWN}, because a number this JVM cannot
 * produce has to be distinguishable from a real zero. A heap gauge flatlining at 0 is a lie somebody
 * eventually builds an alert on.
 *
 * <h2>Micrometer-free on purpose</h2>
 *
 * Micrometer ships JVM binders that produce all of this. It is deliberately not used here: metrics
 * export is optional and off by default ({@code front-cache.metrics.export=none}), and the management
 * API has to answer whether or not anyone enabled it. Same reasoning as {@code CacheStats}, which
 * exposes plain numbers so that {@code org.frontcache.metrics} stays the only package that knows what
 * a meter is.
 *
 * <h2>Nothing here throws</h2>
 *
 * Every reader is guarded. This feeds an endpoint on a poll loop, and a management response that fails
 * because one bean was unavailable is worse than one reporting {@link #UNKNOWN} for that field.
 */
public class FcNodeStats {

	/** The JVM does not report this number. Distinct from zero. */
	public static final long UNKNOWN = -1;

	/** {@link #UNKNOWN} for the CPU load, which is a fraction rather than a count. */
	public static final double UNKNOWN_LOAD = -1.0;

	public static final FcNodeStats NOT_TRACKED =
			new FcNodeStats(UNKNOWN, UNKNOWN, UNKNOWN_LOAD, UNKNOWN);

	private final long heapUsedBytes;
	private final long heapMaxBytes;
	private final double cpuProcessLoad;
	private final long uptimeMillis;

	public FcNodeStats(long heapUsedBytes, long heapMaxBytes, double cpuProcessLoad, long uptimeMillis) {
		this.heapUsedBytes = heapUsedBytes;
		this.heapMaxBytes = heapMaxBytes;
		this.cpuProcessLoad = cpuProcessLoad;
		this.uptimeMillis = uptimeMillis;
	}

	/** Heap currently in use, or {@link #UNKNOWN}. */
	public long getHeapUsedBytes() {
		return heapUsedBytes;
	}

	/**
	 * The heap ceiling, or {@link #UNKNOWN}.
	 *
	 * {@code MemoryUsage.getMax()} already returns -1 when the maximum is undefined, which is the same
	 * value {@link #UNKNOWN} carries - so an unbounded heap reports as untracked rather than as a
	 * ceiling of zero, and a caller rendering "used / max" has nothing to divide by. That is correct:
	 * there is no percentage to show.
	 */
	public long getHeapMaxBytes() {
		return heapMaxBytes;
	}

	/**
	 * Recent CPU used by THIS JVM as a fraction of all available cores (0..1), or {@link #UNKNOWN_LOAD}.
	 *
	 * Process CPU, not system CPU: on a shared host the system figure answers a question about the
	 * neighbours, not about this node.
	 *
	 * The HotSpot bean computes this over its own internal window and returns a negative value until it
	 * has two samples, so the first reading after a restart is {@link #UNKNOWN_LOAD}. That is why the
	 * number is taken from the bean rather than from a {@code getProcessCpuTime()} delta kept here: a
	 * delta would be "since whoever last asked", which quietly means something different the moment a
	 * second console starts polling.
	 */
	public double getCpuProcessLoad() {
		return cpuProcessLoad;
	}

	/** Milliseconds since JVM start, or {@link #UNKNOWN}. */
	public long getUptimeMillis() {
		return uptimeMillis;
	}

	public boolean isTracked(long value) {
		return UNKNOWN != value;
	}

	public boolean isTracked(double load) {
		return !(load < 0);
	}

	/** Reads the JVM now. Never throws, never blocks. */
	public static FcNodeStats current() {
		return new FcNodeStats(heapUsed(), heapMax(), processCpuLoad(), uptime());
	}

	private static long heapUsed() {
		try {
			MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
			return (null == memory) ? UNKNOWN : memory.getHeapMemoryUsage().getUsed();

		} catch (Exception e) {
			return UNKNOWN;
		}
	}

	private static long heapMax() {
		try {
			MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
			if (null == memory)
				return UNKNOWN;

			long max = memory.getHeapMemoryUsage().getMax();
			return (max < 0) ? UNKNOWN : max;

		} catch (Exception e) {
			return UNKNOWN;
		}
	}

	/**
	 * Guarded by an instanceof rather than a cast: {@code getProcessCpuLoad()} lives on the
	 * {@code com.sun.management} extension of the bean, which a non-HotSpot JVM need not implement.
	 * There the answer is "unknown", not a ClassCastException out of a management endpoint.
	 */
	private static double processCpuLoad() {
		try {
			OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
			if (!(os instanceof com.sun.management.OperatingSystemMXBean))
				return UNKNOWN_LOAD;

			double load = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuLoad();

			// negative until the bean has two samples of its own; NaN has been seen on some platforms
			if (Double.isNaN(load) || load < 0)
				return UNKNOWN_LOAD;

			return (load > 1.0) ? 1.0 : load;

		} catch (Throwable t) {
			// Throwable, not Exception: a JVM without the com.sun extension on its module path fails
			// this with an Error, and a missing CPU number must not fail the whole response.
			return UNKNOWN_LOAD;
		}
	}

	private static long uptime() {
		try {
			RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
			return (null == runtime) ? UNKNOWN : runtime.getUptime();

		} catch (Exception e) {
			return UNKNOWN;
		}
	}

	@Override
	public String toString() {
		return "FcNodeStats{heapUsedBytes=" + heapUsedBytes + ", heapMaxBytes=" + heapMaxBytes
				+ ", cpuProcessLoad=" + cpuProcessLoad + ", uptimeMillis=" + uptimeMillis + "}";
	}

}
