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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.SiteKeys;
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
		return HttpClients.custom()
				.setDefaultRequestConfig(RequestConfig.custom()
						.setSocketTimeout(SOCKET_TIMEOUT_MS)
						.setConnectTimeout(SOCKET_TIMEOUT_MS)
						.setConnectionRequestTimeout(SOCKET_TIMEOUT_MS)
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

		List<String> frames = readFrames(STREAM_URL, SiteKeys.TEST_SITE_KEY_LOCALHOST);

		assertFalse("no SSE frames arrived at all", frames.isEmpty());

		boolean sawCommand = false;
		boolean sawThreadPool = false;
		String commandFrame = null;
		for (String frame : frames)
		{
			if (frame.contains("\"type\":\"HystrixCommand\""))
			{
				sawCommand = true;
				if (frame.contains("\"name\":\"Cache-Origin-http\""))
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
		assertTrue("no Cache-Origin-http frame among " + frames.size() + " frames", null != commandFrame);
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
	public void streamRefusesAMissingSiteKey() throws Exception
	{
		assertEquals("an unauthenticated read must be refused, not answered with an empty stream",
				401, statusFor(STREAM_URL, null));
	}

	@Test
	public void streamRefusesAWrongSiteKey() throws Exception
	{
		assertEquals(401, statusFor(STREAM_URL, "wrong-site-key"));
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

		List<String> frames = readFrames(LEGACY_STREAM_URL, SiteKeys.TEST_SITE_KEY_LOCALHOST);

		assertFalse("no SSE frames arrived on the pre-2.7 path", frames.isEmpty());

		boolean sawCommand = false;
		for (String frame : frames)
			sawCommand |= frame.contains("\"type\":\"HystrixCommand\"");

		assertTrue("expected command frames on the pre-2.7 path, got " + frames.size() + " frames", sawCommand);
	}

	@Test
	public void legacyPathIsGatedTheSameWay() throws Exception
	{
		assertEquals("the pre-2.7 path must not be a way around the site-key check",
				401, statusFor(LEGACY_STREAM_URL, null));
		assertEquals(401, statusFor(LEGACY_STREAM_URL, "wrong-site-key"));
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
		get.addHeader("Authorization", "Bearer " + SiteKeys.TEST_SITE_KEY_LOCALHOST);

		CloseableHttpClient httpClient = client();
		try {
			long start = System.currentTimeMillis();
			CloseableHttpResponse response = httpClient.execute(get);
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());

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
				response.close();
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
	 * operator seeing "MaxConcurrentConnections" knows to raise the cap rather than fix a site key.
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

	private static int statusFor(String url, String siteKey) throws Exception
	{
		HttpGet get = new HttpGet(url);
		if (null != siteKey)
			get.addHeader("Authorization", "Bearer " + siteKey);

		CloseableHttpClient httpClient = client();
		try {
			CloseableHttpResponse response = httpClient.execute(get);
			try {
				return response.getStatusLine().getStatusCode();
			} finally {
				get.abort();
				response.close();
			}
		} finally {
			httpClient.close();
		}
	}

	private static List<String> readFrames(String url, String siteKey) throws Exception
	{
		List<String> frames = new ArrayList<String>();

		HttpGet get = new HttpGet(url);
		get.addHeader("Authorization", "Bearer " + siteKey);

		CloseableHttpClient httpClient = client();
		try {
			CloseableHttpResponse response = httpClient.execute(get);
			try {
				assertEquals("stream should be served for a valid site key",
						200, response.getStatusLine().getStatusCode());

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
				response.close();
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
		final CloseableHttpResponse response;
		final int status;

		OpenStream(CloseableHttpClient httpClient, HttpGet get, CloseableHttpResponse response) {
			this.httpClient = httpClient;
			this.get = get;
			this.response = response;
			this.status = response.getStatusLine().getStatusCode();
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
		get.addHeader("Authorization", "Bearer " + SiteKeys.TEST_SITE_KEY_LOCALHOST);

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
}
