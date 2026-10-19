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
package org.frontcache.resilience;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The named thread pools Frontcache owns, replacing {@code HystrixThreadPool}.
 *
 * Resilience4j ships a ThreadPoolBulkhead and it is deliberately not used here. It requires
 * queueCapacity >= 1, and Hystrix's maxQueueSize=-1 - what OriginPool runs with - means "no
 * queue", i.e. a SynchronousQueue that rejects rather than buffers. That difference is not cosmetic:
 * a queue in front of a slow origin converts fast rejection (and a fallback) into latency, which is
 * the opposite of what the isolation is for. Owning a plain ThreadPoolExecutor also means the
 * dashboard's thread-pool numbers can be read straight off it. See proposal section 5.2.
 *
 * Pools are node-level resources, not per-domain ones: {@link #all()} returns every pool with no
 * filtering, which is the fix for the thread-pool panel that has never rendered (section 6.6).
 */
public class FcThreadPools {

	private static final Logger logger = LoggerFactory.getLogger(FcThreadPools.class);

	private static final ConcurrentMap<String, ThreadPoolExecutor> POOLS = new ConcurrentHashMap<String, ThreadPoolExecutor>();

	/**
	 * Rejections per pool. Hystrix reported rollingCountCommandRejections on its thread-pool frames and
	 * a ThreadPoolExecutor keeps no such count, so the rejection handler keeps one - otherwise the most
	 * operationally interesting number on that panel would always read zero.
	 */
	private static final ConcurrentMap<String, java.util.concurrent.atomic.LongAdder> REJECTIONS =
			new ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>();

	private FcThreadPools() { }

	public static ThreadPoolExecutor pool(String poolKey)
	{
		ThreadPoolExecutor pool = POOLS.get(poolKey);
		if (null != pool)
			return pool;

		synchronized (FcThreadPools.class) {
			pool = POOLS.get(poolKey);
			if (null == pool)
			{
				pool = create(poolKey, FcResilienceConfig.getInstance());
				POOLS.put(poolKey, pool);
			}
		}
		return pool;
	}

	static ThreadPoolExecutor create(String poolKey, FcResilienceConfig config)
	{
		int coreSize = config.poolCoreSize(poolKey);
		int maximumSize = Math.max(coreSize, config.poolMaximumSize(poolKey));
		int maxQueueSize = config.poolMaxQueueSize(poolKey);
		int keepAliveMinutes = config.poolKeepAliveMinutes(poolKey);

		// maxQueueSize < 1 -> SynchronousQueue: a submit with no free thread is rejected immediately,
		// which is what Hystrix does with its default of -1. Anything else here would make a saturated
		// node queue silently instead of shedding, and the change would only show up under load.
		java.util.concurrent.BlockingQueue<Runnable> queue = (maxQueueSize < 1)
				? new SynchronousQueue<Runnable>()
				: new LinkedBlockingQueue<Runnable>(maxQueueSize);

		ThreadPoolExecutor pool = new ThreadPoolExecutor(coreSize, maximumSize,
				keepAliveMinutes, TimeUnit.MINUTES, queue, new FcThreadFactory(poolKey),
				// Counts, then behaves exactly like AbortPolicy: throws RejectedExecutionException,
				// which FcCommand maps to THREAD_POOL_REJECTED - the same outcome Hystrix reports.
				new CountingAbortPolicy(poolKey));

		logger.info("Thread pool '" + poolKey + "' coreSize=" + coreSize + " maximumSize=" + maximumSize
				+ " maxQueueSize=" + maxQueueSize + " queue=" + queue.getClass().getSimpleName());

		return pool;
	}

	/**
	 * Every pool, unfiltered. The dashboard's thread-pool frames are emitted from this to every
	 * authorized subscriber: a thread pool belongs to the node, so there is no per-domain answer to
	 * give - which is precisely why the old {@code isOwnerGroup} comparison (the POOL NAME against the
	 * caller's domain) could never be true and the panel was always empty.
	 */
	public static Collection<String> all()
	{
		return Collections.unmodifiableCollection(POOLS.keySet());
	}

	public static ThreadPoolExecutor existing(String poolKey)
	{
		return POOLS.get(poolKey);
	}

	public static long rejectionCount(String poolKey)
	{
		java.util.concurrent.atomic.LongAdder rejections = REJECTIONS.get(poolKey);
		return (null == rejections) ? 0 : rejections.sum();
	}

	/**
	 * Re-applies pool geometry to the live pools from a freshly read config, in place.
	 *
	 * <h2>Three of the four numbers are mutable; the fourth is not</h2>
	 *
	 * {@code coreSize}, {@code maximumSize} and {@code keepAliveMinutes} are settable on a running
	 * {@link ThreadPoolExecutor}. <b>{@code maxQueueSize} is not</b>: it selects the queue TYPE at
	 * construction - {@code < 1} means a {@link SynchronousQueue} that rejects rather than buffers -
	 * and an executor's queue cannot be replaced. Recreating the pool would abandon or kill in-flight
	 * origin calls, so a changed {@code maxQueueSize} is <b>reported and not applied</b>. Silently
	 * ignoring it is how an operator concludes the setting does nothing.
	 *
	 * @return one human-readable warning per setting that could not be applied; empty when everything
	 *         in the file is now live
	 */
	public static List<String> reapplyGeometry(FcResilienceConfig config)
	{
		List<String> warnings = new ArrayList<String>();

		synchronized (FcThreadPools.class) {
			for (Map.Entry<String, ThreadPoolExecutor> entry : POOLS.entrySet())
			{
				String poolKey = entry.getKey();
				ThreadPoolExecutor pool = entry.getValue();

				int coreSize = config.poolCoreSize(poolKey);
				int maximumSize = Math.max(coreSize, config.poolMaximumSize(poolKey));

				// Order matters: setCorePoolSize throws if the new core exceeds the current maximum, and
				// setMaximumPoolSize throws if the new maximum is below the current core. Growing needs
				// the maximum raised first, shrinking needs the core lowered first.
				if (maximumSize >= pool.getCorePoolSize())
				{
					pool.setMaximumPoolSize(maximumSize);
					pool.setCorePoolSize(coreSize);
				} else {
					pool.setCorePoolSize(coreSize);
					pool.setMaximumPoolSize(maximumSize);
				}

				pool.setKeepAliveTime(config.poolKeepAliveMinutes(poolKey), TimeUnit.MINUTES);

				int maxQueueSize = config.poolMaxQueueSize(poolKey);
				boolean wantsQueue = maxQueueSize >= 1;
				boolean hasQueue = !(pool.getQueue() instanceof SynchronousQueue);
				int liveCapacity = hasQueue ? pool.getQueue().remainingCapacity() + pool.getQueue().size() : -1;

				if (wantsQueue != hasQueue || (wantsQueue && maxQueueSize != liveCapacity))
					warnings.add("threadpool." + poolKey + ".maxQueueSize is now " + maxQueueSize
							+ " in the file but the live pool has " + (hasQueue ? "a queue of " + liveCapacity : "no queue")
							+ " - a queue cannot be resized or replaced on a running pool, so this setting"
							+ " needs a node RESTART and is NOT applied");

				logger.info("Thread pool '" + poolKey + "' geometry re-applied: coreSize=" + coreSize
						+ " maximumSize=" + maximumSize);
			}
		}

		return warnings;
	}

	/** Test seam - shuts every pool down and forgets it. */
	static void shutdownAll()
	{
		synchronized (FcThreadPools.class) {
			for (ThreadPoolExecutor pool : POOLS.values())
				pool.shutdownNow();
			POOLS.clear();
			REJECTIONS.clear();
		}
	}

	private static class CountingAbortPolicy extends ThreadPoolExecutor.AbortPolicy {

		private final String poolKey;

		CountingAbortPolicy(String poolKey) {
			this.poolKey = poolKey;
			REJECTIONS.putIfAbsent(poolKey, new java.util.concurrent.atomic.LongAdder());
		}

		@Override
		public void rejectedExecution(Runnable r, ThreadPoolExecutor executor)
		{
			java.util.concurrent.atomic.LongAdder rejections = REJECTIONS.get(poolKey);
			if (null != rejections)
				rejections.increment();

			super.rejectedExecution(r, executor);
		}
	}

	private static class FcThreadFactory implements ThreadFactory {

		private final String poolKey;
		private final AtomicInteger counter = new AtomicInteger(0);

		FcThreadFactory(String poolKey) {
			this.poolKey = poolKey;
		}

		@Override
		public Thread newThread(Runnable r)
		{
			// Daemon, so a pool with idle threads can never hold the JVM up on shutdown - Hystrix's
			// pools behave the same way, and the servlet container has no hook that would join them.
			Thread t = new Thread(r, "fc-" + poolKey + "-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	}
}
