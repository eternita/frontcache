/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix.stream;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.frontcache.core.FCUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the SSE metrics stream at the unchanged {@code /hystrix.stream}, from Frontcache's own metrics
 * instead of Hystrix's.
 *
 * The SSE framing, the 500 ms cadence, the {@code maxConcurrentConnections} throttle and the parked
 * request thread are all preserved - moving to Servlet 3.1 async is a real improvement (one container
 * thread per dashboard viewer is not free) but it is an independent change and is left as follow-on
 * work. What goes away is RxJava: {@code Observable.concatMap(...).observeOn(Schedulers.io())} becomes a
 * single scheduled publisher plus one queue per subscriber.
 *
 * The threading is slightly better than what it replaces, and deliberately so:
 *
 * - Frames are built ONCE per tick on the publisher thread, not once per subscriber, so fifteen
 *   dashboards cost one serialization rather than fifteen.
 * - Each connection drains its own bounded queue and does its own blocking write, on the request thread
 *   that was previously just sleeping. A stalled client can therefore only stall itself; with a single
 *   shared writer thread, one client behind TCP backpressure would stall the stream for everyone.
 * - The queue is bounded and drops its OLDEST frame when full. A dashboard only ever wants the latest
 *   numbers, and an unbounded queue behind a stalled reader is a memory leak with a 500 ms fuse.
 *
 * Two behavior changes, both from proposal section 14.6:
 *
 * - A wrong or absent {@code x-frontcache-site-key} gets {@code 401}, where it used to get {@code 200}
 *   and an endless stream of empty frames. The header is this endpoint's only authentication, so an
 *   unauthenticated read should be refused rather than answered with an empty success.
 * - {@code 503} now means only what it says: the service is shutting down, or the connection cap is
 *   reached. The old code answered every case, including "your key is wrong", the same way.
 */
public abstract class FrontcacheHystrixSampleSseServlet extends HttpServlet {

	private static final long serialVersionUID = 7939894788065973626L;

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheHystrixSampleSseServlet.class);

	/** Publish cadence, and how often a parked connection wakes to check it is still wanted. */
	protected static final int DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS = 500;

	/**
	 * Frames a slow subscriber may fall behind by before the oldest are dropped. Two seconds of ticks
	 * for a handful of commands - enough to ride out a hiccup, small enough to bound the memory.
	 */
	private static final int SUBSCRIBER_QUEUE_CAPACITY = 64;

	private static final CopyOnWriteArrayList<Subscriber> SUBSCRIBERS = new CopyOnWriteArrayList<Subscriber>();

	private static volatile ScheduledExecutorService publisher;

	/* Set to true upon shutdown, so it's OK to be shared among all SampleSseServlets */
	private static volatile boolean isDestroyed = false;

	private final int pausePollerThreadDelayInMs;

	protected FrontcacheHystrixSampleSseServlet(int pausePollerThreadDelayInMs) {
		this.pausePollerThreadDelayInMs = pausePollerThreadDelayInMs;
	}

	protected abstract int getMaxNumberConcurrentConnectionsAllowed();

	protected abstract int getNumberCurrentConnections();

	protected abstract int incrementAndGetCurrentConcurrentConnections();

	protected abstract void decrementCurrentConcurrentConnections();

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (isDestroyed) {
			response.sendError(503, "Service has been shut down.");
		} else {
			handleRequest(request, response);
		}
	}

	/**
	 * WebSphere won't shutdown a servlet until after a 60 second timeout if there is an instance of the
	 * servlet executing a request. Add this method to enable a hook to notify Frontcache to shutdown.
	 * You must invoke this method at shutdown, perhaps from some other servlet's destroy() method.
	 */
	public static void shutdown() {
		isDestroyed = true;
		stopPublisher();
	}

	@Override
	public void init() throws ServletException {
		isDestroyed = false;
	}

	@Override
	public void destroy() {
		/* set marker so the loops can break out */
		isDestroyed = true;
		stopPublisher();
		super.destroy();
	}

	/**
	 * - maintain an open connection with the client
	 * - on initial connection send latest data of each requested event type
	 * - subsequently send all changes for each requested event type
	 */
	private void handleRequest(HttpServletRequest request, final HttpServletResponse response)
			throws ServletException, IOException {

		if (!FCUtils.isAuthorizedSiteKeyHeader(request)) {
			// Refused, not answered with an empty stream - see the class javadoc. Logged at info because
			// on a management port this is either a misconfigured console or someone probing.
			logger.info("Rejecting /hystrix.stream: missing or wrong site key from " + FCUtils.getClientIP(request));
			response.sendError(401, "Missing or invalid " + org.frontcache.core.FCHeaders.X_FRONTCACHE_SITE_KEY);
			return;
		}

		/* ensure we aren't allowing more connections than we want */
		int numberConnections = incrementAndGetCurrentConcurrentConnections();
		Subscriber subscriber = null;
		try {
			int maxNumberConnectionsAllowed = getMaxNumberConcurrentConnectionsAllowed();
			if (numberConnections > maxNumberConnectionsAllowed) {
				response.sendError(503, "MaxConcurrentConnections reached: " + maxNumberConnectionsAllowed);
				return;
			}

			/* initialize response */
			response.setHeader("Content-Type", "text/event-stream;charset=UTF-8");
			response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
			response.setHeader("Pragma", "no-cache");

			PrintWriter writer = response.getWriter();

			subscriber = new Subscriber();
			SUBSCRIBERS.add(subscriber);
			startPublisher();

			// This thread is the writer for its own connection: it blocks on its queue and writes what
			// arrives, rather than sleeping while another thread writes into its response.
			while (subscriber.open && !isDestroyed) {
				String frame = subscriber.queue.poll(pausePollerThreadDelayInMs, TimeUnit.MILLISECONDS);
				if (null == frame)
					continue;

				writer.print("data: " + frame + "\n\n");
				// explicitly check for client disconnect - PrintWriter does not throw exceptions
				if (writer.checkError())
					break;

				writer.flush();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			if (null != subscriber) {
				subscriber.open = false;
				SUBSCRIBERS.remove(subscriber);
			}
			decrementCurrentConcurrentConnections();
		}
	}

	private static void startPublisher()
	{
		if (null != publisher)
			return;

		synchronized (FrontcacheHystrixSampleSseServlet.class) {
			if (null != publisher)
				return;

			ScheduledExecutorService local = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r)
				{
					Thread t = new Thread(r, "fc-metrics-stream");
					t.setDaemon(true);
					return t;
				}
			});

			local.scheduleAtFixedRate(new Runnable() {
				@Override
				public void run()
				{
					try {
						publishTick();
					} catch (Throwable t) {
						// A throw here would kill the scheduled task for the life of the JVM and the
						// stream would go quiet with no error anywhere. Swallow and log.
						logger.error("Metrics stream tick failed", t);
					}
				}
			}, 0, DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS, TimeUnit.MILLISECONDS);

			publisher = local;
		}
	}

	private static void stopPublisher()
	{
		synchronized (FrontcacheHystrixSampleSseServlet.class) {
			if (null != publisher) {
				publisher.shutdownNow();
				publisher = null;
			}
			for (Subscriber subscriber : SUBSCRIBERS)
				subscriber.open = false;
		}
	}

	/** Serialize once, fan out to every subscriber. */
	static void publishTick()
	{
		if (SUBSCRIBERS.isEmpty())
			return;

		List<String> frames = FcDashboardJson.frames();
		for (Subscriber subscriber : SUBSCRIBERS)
			for (String frame : frames)
				subscriber.offer(frame);
	}

	/** Package-private for tests. */
	static int subscriberCount()
	{
		return SUBSCRIBERS.size();
	}

	private static class Subscriber {

		private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(SUBSCRIBER_QUEUE_CAPACITY);
		private volatile boolean open = true;

		void offer(String frame)
		{
			// Drop the oldest rather than the newest: a dashboard wants current numbers, and a stale
			// frame is worth less than the one behind it.
			while (!queue.offer(frame))
				if (null == queue.poll())
					return;
		}
	}
}
