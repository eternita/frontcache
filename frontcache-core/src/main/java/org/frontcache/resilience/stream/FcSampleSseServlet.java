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
package org.frontcache.resilience.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.frontcache.core.FCUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the SSE dashboard stream at {@code /fc-dashboard.stream} and at the pre-2.7
 * {@code /hystrix.stream}, from Frontcache's own metrics instead of Hystrix's.
 *
 * The SSE framing, the 500 ms cadence and the {@code maxConcurrentConnections} throttle are exactly
 * what they were. What changed, in two steps:
 *
 * <h2>PR 3 removed RxJava</h2>
 *
 * {@code Observable.concatMap(...).observeOn(Schedulers.io())} became one scheduled publisher plus one
 * queue per subscriber. Frames are built ONCE per tick rather than once per subscriber, so fifteen
 * dashboards cost one serialization rather than fifteen.
 *
 * <h2>2.7 removed the parked thread</h2>
 *
 * Until now each connection held a container thread for its entire life - originally asleep, then
 * blocked on its own queue. At the default cap that is five threads, at a tuned fifteen it is fifteen,
 * doing nothing but waiting 500 ms at a time. On a node whose thread pool also serves traffic, leaving
 * a dashboard tab open all afternoon costs a request-serving thread all afternoon.
 *
 * This is now Servlet 3.1 async with <b>non-blocking</b> output: {@code doGet} validates, calls
 * {@code startAsync()}, registers a {@link WriteListener} and <b>returns</b>, handing the container
 * thread straight back. No thread is dedicated to a viewer at all - writes happen either on the
 * publisher thread (when the connection is ready to take a frame) or on a container thread the
 * container hands us via {@code onWritePossible()} (when it was not, and now is).
 *
 * <h3>Why the writes are non-blocking rather than "publisher writes and blocks"</h3>
 *
 * Because that is the whole difficulty of the change. A single publisher thread doing blocking writes
 * would be simple and would reintroduce precisely what PR 3 fixed: one reader behind TCP backpressure
 * stalls every other dashboard. {@code isReady()} is what avoids it - a write is attempted only when
 * the container promises it will not block, so a stalled reader just stops being written to. Its own
 * queue fills, its oldest frames are dropped, and no other connection notices.
 *
 * <h3>The idle handoff</h3>
 *
 * {@code onWritePossible()} is only called on a not-ready -> ready transition, so a subscriber that
 * drains its queue while still ready will never be called again on its own. That is why
 * {@link Subscriber#offer} also drains: whichever of the two threads finds work to do does it, under
 * one lock per subscriber so that only one of them is inside {@code isReady()}/{@code write()} at a
 * time. The lock is held only across non-blocking writes, so a container thread can never be parked
 * on it for longer than it takes to fill a socket buffer.
 *
 * <h3>The connection count</h3>
 *
 * The cap is now released from {@link Subscriber#close}, not from a {@code finally} around the request,
 * because the request ends immediately and the connection does not. {@code close()} is
 * compare-and-set guarded so the decrement happens exactly once however the connection ends - client
 * disconnect, write error, container timeout, or shutdown. Getting this wrong would not fail visibly
 * at once: the endpoint would simply start answering 503 forever after N lifetime connections, which
 * is why {@code StandaloneDashboardStreamTests} asserts a closed connection frees its slot.
 *
 * <h2>Two behavior changes, both from proposal section 14.6</h2>
 *
 * - A wrong or absent API key gets {@code 401}, where it used to get {@code 200}
 *   and an endless stream of empty frames. The header is this endpoint's only authentication, so an
 *   unauthenticated read should be refused rather than answered with an empty success.
 * - {@code 503} now means only what it says: the service is shutting down, or the connection cap is
 *   reached. The old code answered every case, including "your key is wrong", the same way.
 */
public abstract class FcSampleSseServlet extends HttpServlet {

	private static final long serialVersionUID = 7939894788065973626L;

	private static final Logger logger = LoggerFactory.getLogger(FcSampleSseServlet.class);

	/** Publish cadence in ms. Named for the field it replaced, which was a poll timeout. */
	protected static final int DEFAULT_PAUSE_POLLER_THREAD_DELAY_IN_MS = 500;

	/**
	 * Frames a slow subscriber may fall behind by before the oldest are dropped. Two seconds of ticks
	 * for a handful of commands - enough to ride out a hiccup, small enough to bound the memory.
	 */
	static final int SUBSCRIBER_QUEUE_CAPACITY = 64;

	private static final CopyOnWriteArrayList<Subscriber> SUBSCRIBERS = new CopyOnWriteArrayList<Subscriber>();

	private static volatile ScheduledExecutorService publisher;

	/* Set to true upon shutdown, so it's OK to be shared among all SampleSseServlets */
	private static volatile boolean isDestroyed = false;

	private final int publishDelayInMs;

	protected FcSampleSseServlet(int pausePollerThreadDelayInMs) {
		this.publishDelayInMs = pausePollerThreadDelayInMs;
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
	 * Accepts the connection and returns. Everything after this happens on the publisher thread or on a
	 * container thread delivering {@code onWritePossible()} - never on this one.
	 */
	private void handleRequest(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (!FCUtils.isAuthorizedApiKeyHeader(request)) {
			// Refused, not answered with an empty stream - see the class javadoc. Logged at info because
			// this is either a misconfigured console or someone probing.
			logger.info("Rejecting " + request.getRequestURI() + ": missing or wrong API key from " + FCUtils.getClientIP(request));
			response.setHeader("WWW-Authenticate", "Bearer realm=\"frontcache\"");
			response.sendError(401, "Missing or invalid API key - send 'Authorization: Bearer <api-key>'");
			return;
		}

		/* ensure we aren't allowing more connections than we want */
		int numberConnections = incrementAndGetCurrentConcurrentConnections();
		boolean accepted = false;
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
			// the response is a stream of frames, and a proxy buffering it would defeat the point
			response.setHeader("X-Accel-Buffering", "no");

			AsyncContext async = request.startAsync();
			// A metrics stream has no natural end, so it must not be given one. The container's default
			// async timeout would otherwise complete a perfectly healthy connection after 30 s.
			async.setTimeout(0);

			Subscriber subscriber = new Subscriber(this, async, response.getOutputStream());
			async.addListener(subscriber);

			// setWriteListener BEFORE the subscriber is published to the publisher thread. The other
			// order looks more natural and is a race: isReady() before a write listener is registered is
			// an IllegalStateException, so a tick landing in that window would kill the connection at
			// birth - intermittently, and only under load, which is the worst way to find out.
			subscriber.start();

			SUBSCRIBERS.add(subscriber);
			startPublisher();

			accepted = true;

		} finally {
			// Only for the paths that never became a subscriber. A live connection releases its slot in
			// Subscriber.close(), which is the only place that can know when the connection actually ends.
			if (!accepted)
				decrementCurrentConcurrentConnections();
		}
	}

	private void startPublisher()
	{
		if (null != publisher)
			return;

		synchronized (FcSampleSseServlet.class) {
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
			}, 0, publishDelayInMs, TimeUnit.MILLISECONDS);

			publisher = local;
		}
	}

	private static void stopPublisher()
	{
		synchronized (FcSampleSseServlet.class) {
			if (null != publisher) {
				publisher.shutdownNow();
				publisher = null;
			}
		}

		// Outside the lock: close() completes an AsyncContext, and completing one while holding a lock
		// the container's own callback threads may want is a deadlock waiting to be reported as a hang.
		for (Subscriber subscriber : SUBSCRIBERS)
			subscriber.close();
	}

	/** Serialize once, encode once, fan out to every subscriber. */
	static void publishTick()
	{
		if (SUBSCRIBERS.isEmpty())
			return;

		List<String> frames = FcDashboardJson.frames();

		byte[][] encoded = new byte[frames.size()][];
		for (int i = 0; i < frames.size(); i++)
			encoded[i] = ("data: " + frames.get(i) + "\n\n").getBytes(StandardCharsets.UTF_8);

		for (Subscriber subscriber : SUBSCRIBERS)
			for (byte[] frame : encoded)
				subscriber.offer(frame);
	}

	/** Package-private for tests. */
	static int subscriberCount()
	{
		return SUBSCRIBERS.size();
	}

	/**
	 * One connection: a bounded queue of encoded frames, and the two entry points that drain it.
	 *
	 * Package-private so {@code FcStreamSubscriberTest} can drive the backpressure and drop-oldest
	 * behaviour directly - both are only observable through a stalled connection, which is awkward to
	 * arrange over a socket and trivial to arrange against a fake {@code ServletOutputStream}.
	 */
	static final class Subscriber implements WriteListener, AsyncListener {

		private final FcSampleSseServlet servlet;

		private final AsyncContext async;

		private final ServletOutputStream out;

		/** Guarded by itself. An ArrayDeque, not a BlockingQueue: nothing blocks on it any more. */
		private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>(SUBSCRIBER_QUEUE_CAPACITY);

		private final AtomicBoolean closed = new AtomicBoolean(false);

		/** Serializes isReady()/write() between the publisher thread and the container's callbacks. */
		private final Object writeLock = new Object();

		Subscriber(FcSampleSseServlet servlet, AsyncContext async, ServletOutputStream out) {
			this.servlet = servlet;
			this.async = async;
			this.out = out;
		}

		void start()
		{
			out.setWriteListener(this);
		}

		/**
		 * Queue a frame and write it if this connection is idle and ready.
		 *
		 * Called on the publisher thread. It may end up performing the write itself, which is safe
		 * precisely because {@code isReady()} gates it: the container has promised the write will not
		 * block, so the publisher cannot be held up by a slow reader.
		 */
		void offer(byte[] frame)
		{
			if (closed.get())
				return;

			synchronized (queue) {
				// Drop the oldest rather than the newest: a dashboard wants current numbers, and a stale
				// frame is worth less than the one behind it.
				while (queue.size() >= SUBSCRIBER_QUEUE_CAPACITY)
					queue.poll();

				queue.add(frame);
			}

			drain();
		}

		/** The container telling us this connection went from not-ready to ready. */
		@Override
		public void onWritePossible()
		{
			drain();
		}

		private void drain()
		{
			if (closed.get())
				return;

			synchronized (writeLock) {
				boolean wrote = false;
				try {
					while (out.isReady()) {
						byte[] frame;
						synchronized (queue) {
							frame = queue.poll();
						}

						// Nothing left to send while still ready: the container will NOT call
						// onWritePossible() again, so the next offer() is what wakes this connection.
						if (null == frame) {
							// And flush before going idle. Without this the container aggregates frames
							// into its output buffer and releases them when it fills - measured at ~3 s
							// of silence and then eight frames at once, which is a dashboard that jumps
							// rather than ticks. isReady() was true this iteration, so this does not
							// block. Only when we wrote: an empty flush on every publish tick would be
							// a syscall per idle connection per 500 ms.
							if (wrote)
								out.flush();

							return;
						}

						out.write(frame);
						wrote = true;
					}
					// Not ready - stop writing and wait for onWritePossible(). No flush here: the
					// container is already draining what it took, which is why it went not-ready.
				} catch (IOException e) {
					// the usual reason is the operator closing the dashboard tab
					logger.debug("Dashboard stream connection ended", e);
					close();
				} catch (IllegalStateException e) {
					// isReady()/write() after the container has finished with this request
					logger.debug("Dashboard stream connection already completed", e);
					close();
				}
			}
		}

		/**
		 * Ends the connection and releases its slot in the concurrency cap, exactly once however the
		 * connection ended.
		 */
		void close()
		{
			if (!closed.compareAndSet(false, true))
				return;

			SUBSCRIBERS.remove(this);
			servlet.decrementCurrentConcurrentConnections();

			try {
				async.complete();
			} catch (Exception e) {
				// already completed by the container (timeout, error, client gone) - nothing to do
				logger.debug("AsyncContext was already complete", e);
			}
		}

		// ---- AsyncListener: every way the container can end this connection ----

		@Override
		public void onComplete(AsyncEvent event) {
			close();
		}

		@Override
		public void onTimeout(AsyncEvent event) {
			close();
		}

		@Override
		public void onError(AsyncEvent event) {
			close();
		}

		@Override
		public void onStartAsync(AsyncEvent event) {
			// this stream is never dispatched and re-started; nothing to re-register
		}

		// ---- WriteListener ----

		@Override
		public void onError(Throwable t) {
			logger.debug("Dashboard stream write failed", t);
			close();
		}

		/** Package-private for tests. */
		int queueSize()
		{
			synchronized (queue) {
				return queue.size();
			}
		}

		/** Package-private for tests. */
		boolean isClosed()
		{
			return closed.get();
		}
	}
}
