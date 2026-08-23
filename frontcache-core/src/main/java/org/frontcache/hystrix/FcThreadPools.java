/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.util.Collection;
import java.util.Collections;
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
 * queueCapacity >= 1, and Hystrix's maxQueueSize=-1 - what OriginHitsPool runs with - means "no
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
				// AbortPolicy throws RejectedExecutionException, which FcCommand maps to
				// THREAD_POOL_REJECTED - the same outcome Hystrix reports.
				new ThreadPoolExecutor.AbortPolicy());

		logger.info("Thread pool '" + poolKey + "' coreSize=" + coreSize + " maximumSize=" + maximumSize
				+ " maxQueueSize=" + maxQueueSize + " queue=" + queue.getClass().getSimpleName());

		return pool;
	}

	/**
	 * Every pool, unfiltered. The dashboard's thread-pool frames are emitted from this to every
	 * authorized subscriber: a thread pool belongs to the node, so there is no per-domain answer to
	 * give - which is precisely why the old {@code isOwnerGroup} comparison ("OriginHitsPool".equals(
	 * domain)) could never be true and the panel was always empty.
	 */
	public static Collection<String> all()
	{
		return Collections.unmodifiableCollection(POOLS.keySet());
	}

	public static ThreadPoolExecutor existing(String poolKey)
	{
		return POOLS.get(poolKey);
	}

	/** Test seam - shuts every pool down and forgets it. */
	static void shutdownAll()
	{
		synchronized (FcThreadPools.class) {
			for (ThreadPoolExecutor pool : POOLS.values())
				pool.shutdownNow();
			POOLS.clear();
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
