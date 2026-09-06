/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.standalone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.frontcache.tests.base.TestsBase;
import org.junit.Test;

/**
 * End-to-end coverage of the dashboard SSE stream, which the suite had none of before this migration.
 *
 * That gap is why the console's thread-pool panel could be empty for years without anything going red:
 * the endpoint was only mapped in the real server webapp, so no test ever opened it. The standalone test
 * webapp now maps it too.
 *
 * Asserted here: an authorized reader gets both frame types - including the thread-pool frames that
 * Hystrix filtered out of existence (proposal section 6.6, defect 1) - an unauthorized reader gets 401
 * rather than an endless stream of empty frames (section 14.6), and BOTH url-patterns behave the same,
 * since the pre-2.7 /hystrix.stream is kept indefinitely for reverse proxies and external Turbine
 * (section 8). The JSON field names and the {@code HystrixCommand} / {@code HystrixThreadPool} type
 * literals are deliberately unchanged - they are the dashboard's wire contract, not internal names.
 *
 * The concurrency cap is asserted here too, and only became worth asserting in 2.7: the stream moved to
 * Servlet 3.1 async, so the request no longer lives as long as the connection and the slot is released
 * from the subscriber's close rather than from a {@code finally}. A dropped release is invisible until
 * the sixth dashboard of the process's life is refused forever, which no unit test over mocks can
 * really settle - it needs real sockets closing at the far end.
 */
public class StandaloneDashboardStreamTests extends TestsBase {

	private static final String STREAM_URL = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "fc-dashboard.stream";

	/** The pre-2.7 path, mapped to the same servlet and kept indefinitely. */
	private static final String LEGACY_STREAM_URL = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "hystrix.stream";

	/** The stream never ends, so every read is bounded and the request is aborted when we have enough. */
	private static final int SOCKET_TIMEOUT_MS = 3000;
	private static final long READ_DEADLINE_MS = 8000;

	/**
	 * {@code resilience.config.stream.maxConcurrentConnections}. The standalone test home sets no
	 * override, so this is the built-in default.
	 */
	private static final int MAX_STREAMS = 5;

	/** How long to allow for the server to notice a socket the client closed and reclaim its slot. */
	private static final long SLOT_RELEASE_DEADLINE_MS = 10000;

	/**
	 * A healthy stream delivers its first frame within one publish tick (500 ms), measured at ~20 ms.
	 * An unflushed one sat at ~2.9 s. Four times the cadence separates them with room to spare.
	 */
	private static final long FIRST_FRAME_DEADLINE_MS = 2000;

	private static CloseableHttpClient client()
	{
		// HttpClient 5: the socket read timeout is SocketConfig on the connection manager, not
		// RequestConfig. This test reads an SSE stream, so the read timeout is what bounds it.
		PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setDefaultSocketConfig(SocketConfig.custom()
				.setSoTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT_MS))
				.build());
		connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT_MS))
				.build());

		return HttpClients.custom()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectionRequestTimeout(Timeout.ofMilliseconds(SOCKET_TIMEOUT_MS))
						.build())
				.build();
	}

	/** Drive real traffic first: no commands have executed means nothing to report. */
	private void generateTraffic() throws Exception
	{
		for (int i = 0; i < 3; i++)
			webClient.getPage(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/jsp-read/a.jsp");
	}

	@Test
	public void streamEmitsCommandAndThreadPoolFrames() throws Exception
	{
		generateTraffic();

		List<String> frames = readFrames(STREAM_URL, ApiKeys.TEST_API_KEY_LOCALHOST);

		assertFalse("no SSE frames arrived at all", frames.isEmpty());

		boolean sawCommand = false;
		boolean sawThreadPool = false;
		String commandFrame = null;
		for (String frame : frames)
		{
			if (frame.contains("\"type\":\"HystrixCommand\""))
			{
				sawCommand = true;
				if (frame.contains("\"name\":\"Cache-Miss\""))
					commandFrame = frame;
			}
			if (frame.contains("\"type\":\"HystrixThreadPool\""))
				sawThreadPool = true;
		}

		assertTrue("expected HystrixCommand frames, got " + frames.size() + " frames", sawCommand);
		assertTrue("expected HystrixThreadPool frames - this is the panel that never rendered under"
				+ " Hystrix, because the pool name was compared against the caller's domain", sawThreadPool);

		// Every field the vendored dashboard's validateData() asserts. A missing one throws in the
		// browser and blanks the panel with no server-side symptom, so it is worth asserting over the
		// wire and not only in a unit test.
		assertTrue("no Cache-Miss frame among " + frames.size() + " frames", null != commandFrame);
		for (String required : new String[] {
				"reportingHosts", "type", "name", "group", "isCircuitBreakerOpen", "errorPercentage",
				"errorCount", "requestCount", "rollingCountCollapsedRequests", "rollingCountExceptionsThrown",
				"rollingCountFailure", "rollingCountFallbackFailure", "rollingCountFallbackRejection",
				"rollingCountFallbackSuccess", "rollingCountResponsesFromCache", "rollingCountSemaphoreRejected",
				"rollingCountShortCircuited", "rollingCountSuccess", "rollingCountThreadPoolRejected",
				"rollingCountTimeout", "rollingCountBadRequests", "currentConcurrentExecutionCount",
				"latencyExecute_mean", "latencyExecute", "propertyValue_circuitBreakerRequestVolumeThreshold",
				"propertyValue_circuitBreakerSleepWindowInMilliseconds",
				"propertyValue_circuitBreakerErrorThresholdPercentage", "propertyValue_circuitBreakerForceOpen",
				"propertyValue_executionIsolationStrategy",
				"propertyValue_executionIsolationThreadTimeoutInMilliseconds",
				"propertyValue_executionIsolationThreadInterruptOnTimeout",
				"propertyValue_executionIsolationSemaphoreMaxConcurrentRequests",
				"propertyValue_fallbackIsolationSemaphoreMaxConcurrentRequests",
				"propertyValue_requestCacheEnabled", "propertyValue_requestLogEnabled",
				"propertyValue_metricsRollingStatisticalWindowInMilliseconds" })
			assertTrue("dashboard asserts '" + required + "' and it is not on the wire: " + commandFrame,
					commandFrame.contains("\"" + required + "\""));
	}

	@Test
	public void streamRefusesAMissingApiKey() throws Exception
	{
		assertEquals("an unauthenticated read must be refused, not answered with an empty stream",
				401, statusFor(STREAM_URL, null));
	}

	@Test
	public void streamRefusesAWrongApiKey() throws Exception
	{
		assertEquals(401, statusFor(STREAM_URL, "wrong-api-key"));
	}

	/**
	 * The legacy path is the same servlet, so the interesting risk is not the handler but the mapping:
	 * a servlet-mapping with two url-patterns is easy to get wrong in a way that leaves the second one
	 * falling through to FrontCacheServlet's /* and being proxied to the origin as a 404 page. Assert
	 * frames, not just a 200.
	 */
	@Test
	public void legacyPathStillServesTheSameStream() throws Exception
	{
		generateTraffic();

		List<String> frames = readFrames(LEGACY_STREAM_URL, ApiKeys.TEST_API_KEY_LOCALHOST);

		assertFalse("no SSE frames arrived on the pre-2.7 path", frames.isEmpty());

		boolean sawCommand = false;
		for (String frame : frames)
			sawCommand |= frame.contains("\"type\":\"HystrixCommand\"");

		assertTrue("expected command frames on the pre-2.7 path, got " + frames.size() + " frames", sawCommand);
	}

	@Test
	public void legacyPathIsGatedTheSameWay() throws Exception
	{
		assertEquals("the pre-2.7 path must not be a way around the api-key check",
				401, statusFor(LEGACY_STREAM_URL, null));
		assertEquals(401, statusFor(LEGACY_STREAM_URL, "wrong-api-key"));
	}

	/**
	 * Frames arrive as they are produced, not in bursts.
	 *
	 * The async rewrite dropped the old loop's explicit flush, and nothing failed: the container simply
	 * aggregated frames into its output buffer and released them when it filled - measured at 2.9 s of
	 * silence followed by eight frames at once. Every other test here still passed, because they assert
	 * that frames arrive and not when. A dashboard that jumps every three seconds instead of ticking
	 * twice a second is a regression an operator would notice long before a test would.
	 */
	@Test
	public void theFirstFrameArrivesWithinAPublishTick() throws Exception
	{
		generateTraffic();

		HttpGet get = new HttpGet(STREAM_URL);
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);

		CloseableHttpClient httpClient = client();
		try {
			long start = System.currentTimeMillis();
			ClassicHttpResponse response = httpClient.execute(get);
			try {
				assertEquals(200, response.getCode());

				BufferedReader reader = new BufferedReader(new InputStreamReader(
						response.getEntity().getContent(), StandardCharsets.UTF_8));

				String line;
				while (null != (line = reader.readLine()))
					if (line.startsWith("data: "))
						break;

				long elapsed = System.currentTimeMillis() - start;

				assertTrue("no frame arrived at all", null != line);
				assertTrue("the first frame took " + elapsed + "ms - the stream is being buffered rather"
						+ " than flushed per frame", elapsed < FIRST_FRAME_DEADLINE_MS);
			} finally {
				get.abort();
				closeQuietly(response);
			}
		} finally {
			httpClient.close();
		}
	}

	/**
	 * The cap refuses the reader past the limit, and - the half that would break silently - closing a
	 * reader gives its slot back.
	 *
	 * Asserted over real sockets on purpose. The release now happens when the container notices the
	 * far end is gone, which is exactly the part that mocks cannot stand in for.
	 */
	@Test
	public void theCapRefusesExtraReadersAndAClosedReaderFreesItsSlot() throws Exception
	{
		// Other tests in this class abort their streams; the server may not have noticed yet.
		awaitAFreeSlot("before the cap test could start, no slot ever came free");

		List<OpenStream> held = new ArrayList<OpenStream>();
		int refusedAt = -1;
		try {
			for (int i = 0; i < MAX_STREAMS + 3; i++)
			{
				OpenStream stream = openStream();
				if (200 != stream.status)
				{
					stream.close();
					refusedAt = i;
					break;
				}
				held.add(stream);
			}

			assertEquals("the reader past the cap must be refused", MAX_STREAMS, refusedAt);
		} finally {
			for (OpenStream stream : held)
				stream.close();
		}

		awaitAFreeSlot("closing every reader did not free a slot - the endpoint would now answer 503"
				+ " forever, which is what a missed release in Subscriber.close() looks like");
	}

	/**
	 * The refusal is a 503 and not a 401: the two mean different things since section 14.6, and an
	 * operator seeing "MaxConcurrentConnections" knows to raise the cap rather than fix an API key.
	 */
	@Test
	public void theCapRefusalIsDistinguishableFromAnAuthorizationRefusal() throws Exception
	{
		awaitAFreeSlot("no slot ever came free");

		List<OpenStream> held = new ArrayList<OpenStream>();
		try {
			OpenStream refused = null;
			for (int i = 0; i < MAX_STREAMS + 3 && null == refused; i++)
			{
				OpenStream stream = openStream();
				if (200 == stream.status)
					held.add(stream);
				else
					refused = stream;
			}

			assertTrue("expected to be refused within " + (MAX_STREAMS + 3) + " readers", null != refused);
			assertEquals(503, refused.status);
			refused.close();
		} finally {
			for (OpenStream stream : held)
				stream.close();
		}
	}

	// ---- helpers ------------------------------------------------------------

	private static int statusFor(String url, String apiKey) throws Exception
	{
		HttpGet get = new HttpGet(url);
		if (null != apiKey)
			get.addHeader("Authorization", "Bearer " + apiKey);

		CloseableHttpClient httpClient = client();
		try {
			ClassicHttpResponse response = httpClient.execute(get);
			try {
				return response.getCode();
			} finally {
				get.abort();
				closeQuietly(response);
			}
		} finally {
			httpClient.close();
		}
	}

	private static List<String> readFrames(String url, String apiKey) throws Exception
	{
		List<String> frames = new ArrayList<String>();

		HttpGet get = new HttpGet(url);
		get.addHeader("Authorization", "Bearer " + apiKey);

		CloseableHttpClient httpClient = client();
		try {
			ClassicHttpResponse response = httpClient.execute(get);
			try {
				assertEquals("stream should be served for a valid API key",
						200, response.getCode());

				BufferedReader reader = new BufferedReader(new InputStreamReader(
						response.getEntity().getContent(), StandardCharsets.UTF_8));

				long deadline = System.currentTimeMillis() + READ_DEADLINE_MS;
				boolean sawCommand = false;
				boolean sawThreadPool = false;

				String line;
				while (System.currentTimeMillis() < deadline && null != (line = reader.readLine()))
				{
					if (!line.startsWith("data: "))
						continue;

					String frame = line.substring("data: ".length());
					frames.add(frame);

					sawCommand |= frame.contains("\"type\":\"HystrixCommand\"");
					sawThreadPool |= frame.contains("\"type\":\"HystrixThreadPool\"");

					// Both frame types seen - no reason to hold the connection open any longer.
					if (sawCommand && sawThreadPool)
						break;
				}
			} finally {
				// The stream is infinite; abort before close or close() blocks on more data.
				get.abort();
				closeQuietly(response);
			}
		} finally {
			httpClient.close();
		}

		return frames;
	}

	/**
	 * A stream held open. Each gets its own client because the pooling connection manager allows two
	 * connections per route by default, and this test needs six.
	 */
	private static class OpenStream {

		final CloseableHttpClient httpClient;
		final HttpGet get;
		final ClassicHttpResponse response;
		final int status;

		OpenStream(CloseableHttpClient httpClient, HttpGet get, ClassicHttpResponse response) {
			this.httpClient = httpClient;
			this.get = get;
			this.response = response;
			this.status = response.getCode();
		}

		void close() {
			// abort before close: the entity is an endless stream and close() would wait on it
			try { get.abort(); } catch (Exception e) { /* closing anyway */ }
			try { response.close(); } catch (Exception e) { /* closing anyway */ }
			try { httpClient.close(); } catch (Exception e) { /* closing anyway */ }
		}
	}

	private static OpenStream openStream() throws Exception
	{
		HttpGet get = new HttpGet(STREAM_URL);
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);

		CloseableHttpClient httpClient = client();
		try {
			return new OpenStream(httpClient, get, httpClient.execute(get));
		} catch (Exception e) {
			httpClient.close();
			throw e;
		}
	}

	/** Opens and immediately closes a stream, retrying until one is accepted or the deadline passes. */
	private static void awaitAFreeSlot(String message) throws Exception
	{
		long deadline = System.currentTimeMillis() + SLOT_RELEASE_DEADLINE_MS;

		while (System.currentTimeMillis() < deadline)
		{
			OpenStream probe = openStream();
			int status = probe.status;
			probe.close();

			if (200 == status)
				return;

			Thread.sleep(250);
		}

		fail(message);
	}

	/**
	 * Closes an abandoned streaming response. Always paired with {@code get.abort()}: the abort has
	 * already discarded the connection, so ChunkedInputStream.close()'s attempt to drain the
	 * remaining chunks - which on an endless SSE stream could never finish anyway - throws
	 * "Socket closed". HttpClient 4.5 swallowed that inside CloseableHttpResponse.close(); 5.x
	 * propagates it. Nothing here is recoverable or informative.
	 */
	private static void closeQuietly(ClassicHttpResponse response) {
		try {
			response.close();
		} catch (IOException e) {
			// expected - see above
		}
	}
}
