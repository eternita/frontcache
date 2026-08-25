/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.frontcache.console.service.FrontcacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

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
 * Relays an edge's dashboard SSE stream to the browser, because the console is served from a different
 * origin than the node it monitors and {@code EventSource} has no CORS story worth relying on.
 *
 * Replaces the Netflix {@code com.netflix.hystrix.dashboard.stream.ProxyStreamServlet} that the console
 * carried since 1.x, along with the rest of the vendored dashboard.
 *
 * <h2>The edge allowlist</h2>
 *
 * The Netflix original proxied a GET to <em>whatever URL the caller put in {@code origin}</em>, forwarded
 * every other query parameter to it, and attached the console's {@code front-cache.site-key} on the way -
 * so anyone who could reach the console could aim it at an arbitrary internal host and be handed the
 * shared secret in the request headers. That made sense for a dashboard whose whole purpose was pointing
 * at ad-hoc streams; it does not for a console with a configured edge list.
 *
 * So {@code origin} must name an edge the console already knows ({@code frontcache.console.urls}, plus
 * anything added at runtime through the Edges page). Nothing else is contacted, and no other query
 * parameter is passed on. This costs nothing operationally - the page only ever asks for edges the
 * console itself listed.
 *
 * <h2>Nothing blocks, at either end</h2>
 *
 * A relay has two sides and both used to be blocking: a container thread sat in a
 * {@code while (is.read())} loop for the life of every dashboard tab. Making only the servlet side
 * async would have moved that thread from the container's pool to one of ours, which is not a saving.
 *
 * So both sides are non-blocking:
 *
 * <ul>
 * <li><b>Upstream</b> is the JDK's own {@code java.net.http.HttpClient} with
 *     {@code BodyHandlers.ofPublisher()}, which hands back a {@link Flow.Publisher} of chunks over the
 *     JDK's shared selector threads. Deliberately not an async Apache client: that would be a new
 *     dependency and a second HTTP stack inside one war, and the node's own migration was fought over
 *     exactly this kind of accretion. The blocking Apache client stays for the console's other calls,
 *     which are short request/response exchanges where it is the right tool.</li>
 * <li><b>Downstream</b> is Servlet 3.1 async with a {@link WriteListener}, the same shape as the node's
 *     {@code FcSampleSseServlet}.</li>
 * </ul>
 *
 * <h3>The backpressure is now end to end, which is the real gain</h3>
 *
 * The two sides are joined by demand: a chunk is requested from the upstream publisher only once
 * everything already received has been written to the browser. So a viewer on a stalled connection
 * stops us reading from the node, which applies TCP backpressure to the node, which its own stream
 * handles by dropping that subscriber's oldest frames. Every link in the chain degrades where it should,
 * instead of the middle one buffering an unbounded amount of an endless stream in the console's heap.
 *
 * This is also why <b>nothing is dropped here</b>, unlike in the node's stream. The node drops oldest
 * because it knows what a frame is and that a stale one is worthless. A relay does not: dropping bytes
 * mid-stream would cut an SSE frame in half and corrupt everything after it. Demand is what bounds the
 * memory instead: one delivery is requested at a time, so what is held is one delivery's worth of
 * buffers - not one buffer, since a delivery may carry several, but never an unbounded backlog.
 */
public class DashboardStreamProxyServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(DashboardStreamProxyServlet.class);

	/**
	 * The path the node serves the stream on. Appended to an allowlisted edge URL - never taken from the
	 * request, so the browser cannot choose what is fetched from the edge.
	 *
	 * Deliberately still the pre-2.7 path, not {@code /fc-dashboard.stream}: the console is deployed
	 * separately from the nodes it monitors, so it has to work against a mixed fleet, and only the old
	 * path exists on a 2.6 node. 2.7+ serves both. Switch this when the oldest supported node is 2.7 -
	 * see docs/hystrix-to-resilience4j-proposal.md section 8.2.
	 */
	static final String STREAM_PATH = "hystrix.stream";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * How long a relay may go without receiving a byte before it is ended.
	 *
	 * Replaces the blocking client's 10 s socket timeout, and is worth replacing rather than dropping:
	 * the node emits every 500 ms, so ten seconds of silence means the stream is wedged, and an
	 * {@code EventSource} whose connection is merely quiet never reconnects - the dashboard would sit on
	 * stale numbers indefinitely. Ending the relay is what makes the browser retry.
	 */
	private static final long IDLE_LIMIT_MS = 10000;

	private static final long REAPER_PERIOD_MS = 2000;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			// Pinned to 1.1: the JDK client would otherwise try an h2c upgrade on every connection to a
			// node that does not offer one. SSE gains nothing from HTTP/2 here and the fallback is a
			// wasted round trip per dashboard.
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	private static final CopyOnWriteArrayList<Relay> RELAYS = new CopyOnWriteArrayList<Relay>();

	private static volatile ScheduledExecutorService reaper;

	@Autowired
	private FrontcacheService frontcacheService;

	@Override
	public void init() throws ServletException {
		WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
		AutowireCapableBeanFactory beanFactory = context.getAutowireCapableBeanFactory();
		beanFactory.autowireBean(this);
	}

	@Override
	public void destroy() {
		stopReaper();
		for (Relay relay : RELAYS)
			relay.close();

		super.destroy();
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		String origin = request.getParameter("origin");
		if (null == origin) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Required parameter 'origin' missing");
			return;
		}

		String streamUrl = resolveStreamUrl(origin, frontcacheService.getFrontCacheAgentURLs());
		if (null == streamUrl) {
			logger.warn("Refusing to proxy '{}' - not one of the console's edges", origin);
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Unknown edge");
			return;
		}

		// Everything past here happens on a JDK HttpClient thread or a container callback. This one
		// goes back to the pool now.
		AsyncContext async = request.startAsync();
		async.setTimeout(0);

		open(streamUrl, frontcacheService.getSiteKey(), async, response);
	}

	private void open(String streamUrl, String siteKey, AsyncContext async, HttpServletResponse response) {

		HttpRequest.Builder upstream = HttpRequest.newBuilder(URI.create(streamUrl)).GET();
		if (null != siteKey)
			upstream.header("Authorization", "Bearer " + siteKey);

		HTTP.sendAsync(upstream.build(), HttpResponse.BodyHandlers.ofPublisher())
				.whenComplete((upstreamResponse, failure) -> {
					if (null != failure) {
						// The edge is down, refused, or unresolvable. Without this the browser would be
						// handed an empty 200 and show a blank panel with nothing anywhere to say why.
						logger.error("Could not open " + streamUrl, failure);
						refuse(async, response, HttpServletResponse.SC_BAD_GATEWAY,
								"Could not read the metrics stream");
						return;
					}

					if (200 != upstreamResponse.statusCode()) {
						// Propagate the refusal rather than swallowing it. The node answers 401 for a
						// missing or wrong site key (docs/hystrix-to-resilience4j-proposal.md section
						// 14.6), and an operator looking at a blank dashboard needs to see that.
						logger.warn("Upstream {} returned {} - propagating to the client", streamUrl,
								upstreamResponse.statusCode());
						refuse(async, response, upstreamResponse.statusCode(),
								"Metrics stream returned " + upstreamResponse.statusCode());
						return;
					}

					start(streamUrl, async, response, upstreamResponse.body());
				});
	}

	/** Nothing has been written yet, so the response is uncommitted and can still carry a status. */
	private static void refuse(AsyncContext async, HttpServletResponse response, int status, String message) {

		try {
			response.sendError(status, message);
		} catch (Exception e) {
			logger.debug("Could not send " + status + " to the dashboard", e);
		} finally {
			try {
				async.complete();
			} catch (Exception e) {
				logger.debug("AsyncContext was already complete", e);
			}
		}
	}

	private void start(String streamUrl, AsyncContext async, HttpServletResponse response,
			Flow.Publisher<List<ByteBuffer>> body) {

		try {
			response.setContentType("text/event-stream");
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Cache-Control", "no-cache, no-store");
			// the stream is a relay of SSE frames, and a proxy buffering it would defeat the point
			response.setHeader("X-Accel-Buffering", "no");

			Relay relay = new Relay(streamUrl, async, response.getOutputStream());
			async.addListener(relay);

			RELAYS.add(relay);
			startReaper();

			// Write listener first, then subscribe: a chunk arriving before the listener is registered
			// would find isReady() throwing IllegalStateException.
			relay.start();
			body.subscribe(relay);

		} catch (Exception e) {
			logger.error("Could not start the dashboard relay", e);
			refuse(async, response, HttpServletResponse.SC_BAD_GATEWAY, "Could not start the metrics stream");
		}
	}

	/**
	 * @return the URL of the dashboard stream on that edge, or null if {@code origin} is not an edge this
	 *         console knows.
	 */
	static String resolveStreamUrl(String origin, Set<String> edgeUrls) {

		String requested = normalize(origin);

		for (String edgeUrl : edgeUrls)
			if (normalize(edgeUrl).equals(requested))
				return normalize(edgeUrl) + STREAM_PATH;

		return null;
	}

	/** Edge URLs are configured by hand, so tolerate a missing or doubled trailing slash. */
	private static String normalize(String url) {

		String normalized = url.trim();

		// the page asks for "<edge>/hystrix.stream"; compare on the edge itself
		if (normalized.endsWith(STREAM_PATH))
			normalized = normalized.substring(0, normalized.length() - STREAM_PATH.length());

		while (normalized.endsWith("/"))
			normalized = normalized.substring(0, normalized.length() - 1);

		return normalized + "/";
	}

	// ---- the idle reaper ------------------------------------------------------------------------

	private static void startReaper()
	{
		if (null != reaper)
			return;

		synchronized (DashboardStreamProxyServlet.class) {
			if (null != reaper)
				return;

			ScheduledExecutorService local = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r)
				{
					Thread t = new Thread(r, "fc-console-stream-reaper");
					t.setDaemon(true);
					return t;
				}
			});

			local.scheduleAtFixedRate(new Runnable() {
				@Override
				public void run()
				{
					try {
						long cutoff = System.currentTimeMillis() - IDLE_LIMIT_MS;
						for (Relay relay : RELAYS)
							if (relay.lastActivityMs() < cutoff) {
								// WARN, not INFO: an edge that accepted the connection and then went
								// quiet is an operational condition, and it is the only notice anyone
								// gets - the browser just reconnects.
								logger.warn("Ending a dashboard relay to {} - no data for over {} ms",
										relay.streamUrl(), IDLE_LIMIT_MS);
								relay.close();
							}
					} catch (Throwable t) {
						// a throw here would kill the task for the life of the JVM
						logger.error("Dashboard relay reaper failed", t);
					}
				}
			}, REAPER_PERIOD_MS, REAPER_PERIOD_MS, TimeUnit.MILLISECONDS);

			reaper = local;
		}
	}

	private static void stopReaper()
	{
		synchronized (DashboardStreamProxyServlet.class) {
			if (null != reaper) {
				reaper.shutdownNow();
				reaper = null;
			}
		}
	}

	/** Package-private for tests. */
	static int relayCount()
	{
		return RELAYS.size();
	}

	/**
	 * One dashboard connection: subscriber to the upstream chunks, write listener for the browser.
	 *
	 * Package-private so {@code DashboardRelayTest} can drive both sides directly - the interesting
	 * behaviour is what happens when one of them misbehaves, which is awkward to arrange over sockets.
	 */
	static final class Relay implements Flow.Subscriber<List<ByteBuffer>>, WriteListener, AsyncListener {

		private final String streamUrl;

		private final AsyncContext async;

		private final ServletOutputStream out;

		/** Guarded by itself. Holds one delivery's buffers at most, because demand is one at a time. */
		private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();

		private final AtomicBoolean closed = new AtomicBoolean(false);

		/** True while a request(1) is outstanding, so we never ask for a second chunk in flight. */
		private final AtomicBoolean demanded = new AtomicBoolean(false);

		/** Serializes isReady()/write() between the HttpClient thread and the container's callbacks. */
		private final Object writeLock = new Object();

		private volatile Flow.Subscription subscription;

		private volatile long lastActivity = System.currentTimeMillis();

		Relay(String streamUrl, AsyncContext async, ServletOutputStream out) {
			this.streamUrl = streamUrl;
			this.async = async;
			this.out = out;
		}

		String streamUrl()
		{
			return streamUrl;
		}

		void start()
		{
			out.setWriteListener(this);
		}

		long lastActivityMs()
		{
			return lastActivity;
		}

		// ---- upstream ----

		@Override
		public void onSubscribe(Flow.Subscription subscription)
		{
			this.subscription = subscription;

			// Nothing is requested yet: the browser side has not reported ready, and asking for chunks we
			// cannot write is how a relay ends up buffering an endless stream.
			requestIfDrained();
		}

		@Override
		public void onNext(List<ByteBuffer> chunks)
		{
			demanded.set(false);
			lastActivity = System.currentTimeMillis();

			synchronized (queue) {
				for (ByteBuffer chunk : chunks) {
					byte[] bytes = new byte[chunk.remaining()];
					chunk.get(bytes);
					if (bytes.length > 0)
						queue.add(bytes);
				}
			}

			drain();
		}

		/**
		 * Serves BOTH {@link Flow.Subscriber#onError} and {@link WriteListener#onError} - the two
		 * interfaces declare the same signature, and the response to either is the same: this connection
		 * is over. Whichever end broke, the other is released by {@link #close()}.
		 */
		@Override
		public void onError(Throwable throwable)
		{
			logger.debug("Dashboard relay failed", throwable);
			close();
		}

		@Override
		public void onComplete()
		{
			// the node's stream has no natural end, so this means the edge went away
			logger.debug("Dashboard relay upstream ended");
			close();
		}

		// ---- browser ----

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
						byte[] chunk;
						synchronized (queue) {
							chunk = queue.poll();
						}

						if (null == chunk) {
							// Flush before going idle. Without this the container aggregates frames and
							// releases them when its buffer fills, which turns a stream that ticks twice
							// a second into one that jumps every few seconds.
							if (wrote)
								out.flush();

							break;
						}

						out.write(chunk);
						wrote = true;
						lastActivity = System.currentTimeMillis();
					}
				} catch (IOException e) {
					// the usual reason is the operator closing the dashboard tab
					logger.debug("Dashboard relay connection ended", e);
					close();
					return;
				} catch (IllegalStateException e) {
					logger.debug("Dashboard relay already completed", e);
					close();
					return;
				}
			}

			requestIfDrained();
		}

		/** Ask the node for more only once the browser has taken everything we already have. */
		private void requestIfDrained()
		{
			Flow.Subscription current = subscription;
			if (null == current || closed.get())
				return;

			synchronized (queue) {
				if (!queue.isEmpty())
					return;
			}

			if (demanded.compareAndSet(false, true))
				current.request(1);
		}

		void close()
		{
			if (!closed.compareAndSet(false, true))
				return;

			RELAYS.remove(this);

			Flow.Subscription current = subscription;
			if (null != current)
				try { current.cancel(); } catch (Exception e) { logger.debug("Could not cancel upstream", e); }

			try {
				async.complete();
			} catch (Exception e) {
				logger.debug("AsyncContext was already complete", e);
			}
		}

		// ---- AsyncListener: every way the container can end this connection ----

		@Override public void onComplete(AsyncEvent event) { close(); }
		@Override public void onTimeout(AsyncEvent event) { close(); }
		@Override public void onError(AsyncEvent event) { close(); }
		@Override public void onStartAsync(AsyncEvent event) { /* never re-dispatched */ }

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

		/** Package-private for tests. */
		boolean hasOutstandingDemand()
		{
			return demanded.get();
		}
	}

}
