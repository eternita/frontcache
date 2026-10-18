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
package org.frontcache.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger logger = LoggerFactory.getLogger(FcNodeStats.class);

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
	 * Said once, at WARN, when this JVM cannot produce a process CPU figure at all.
	 *
	 * Not for the transient case - the bean returns a negative value until it has two samples of its
	 * own, and warning about the first reading after every restart would train an operator to ignore
	 * the line. This is for the permanent one, where the bean is simply not there, and it is worth a
	 * line because the symptom is a single dash in one column of one console screen: everything else
	 * in the response is correct, so nothing looks broken enough to investigate.
	 *
	 * That is not hypothetical. An embedded-runtime bundle shipped for a whole release with
	 * jdk.management missing from its jlink module set, and reported "JVM CPU -" on every node.
	 */
	private static final AtomicBoolean cpuUnavailableReported = new AtomicBoolean(false);

	/**
	 * Guarded by an instanceof rather than a cast: {@code getProcessCpuLoad()} lives on the
	 * {@code com.sun.management} extension of the bean, which a non-HotSpot JVM need not implement.
	 * There the answer is "unknown", not a ClassCastException out of a management endpoint.
	 *
	 * <b>The instanceof itself is what throws when the extension is absent.</b> On a jlink'd runtime
	 * built without {@code jdk.management} the interface is not on the module path at all, so the test
	 * against it raises {@code NoClassDefFoundError} rather than evaluating to false - which is why
	 * the catch below is a Throwable and why both paths end in the same report.
	 */
	private static double processCpuLoad() {
		try {
			OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
			if (!(os instanceof com.sun.management.OperatingSystemMXBean))
				return cpuUnavailable(null == os ? "no OperatingSystemMXBean" : os.getClass().getName());

			double load = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuLoad();

			// negative until the bean has two samples of its own; NaN has been seen on some platforms.
			// Deliberately NOT reported: this one clears itself.
			if (Double.isNaN(load) || load < 0)
				return UNKNOWN_LOAD;

			return (load > 1.0) ? 1.0 : load;

		} catch (Throwable t) {
			// Throwable, not Exception: a JVM without the com.sun extension on its module path fails
			// this with an Error, and a missing CPU number must not fail the whole response.
			return cpuUnavailable(t.toString());
		}
	}

	/** {@link #UNKNOWN_LOAD}, plus one line about why - see {@link #cpuUnavailableReported}. */
	private static double cpuUnavailable(String detail) {

		if (cpuUnavailableReported.compareAndSet(false, true))
			logger.warn("Process CPU is not available in this JVM, so 'JVM CPU' reads as unknown on the"
					+ " console's Edges status - and frontcache_process_cpu_usage is empty too, since"
					+ " Micrometer's ProcessorMetrics reads the same bean."
					+ " com.sun.management.OperatingSystemMXBean is unusable here ({}). On an embedded"
					+ " runtime that means the jlink module set is missing jdk.management - see"
					+ " scripts/dist/build-runtime-bundles.sh. Heap and uptime are unaffected.", detail);

		return UNKNOWN_LOAD;
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
