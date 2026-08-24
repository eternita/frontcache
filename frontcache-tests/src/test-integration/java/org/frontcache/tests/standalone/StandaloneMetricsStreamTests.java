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
import org.frontcache.core.FCHeaders;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.SiteKeys;
import org.frontcache.tests.base.TestsBase;
import org.junit.Test;

/**
 * End-to-end coverage of {@code /hystrix.stream}, which the suite has never had.
 *
 * That gap is why the console's thread-pool panel could be empty for years without anything going red:
 * the endpoint was only mapped in the real server webapp, so no test ever opened it. The standalone test
 * webapp now maps it too.
 *
 * Asserted here: an authorized reader gets both frame types - including the thread-pool frames that
 * Hystrix filtered out of existence (proposal section 6.6, defect 1) - and an unauthorized reader gets
 * 401 rather than an endless stream of empty frames (section 14.6).
 */
public class StandaloneMetricsStreamTests extends TestsBase {

	private static final String STREAM_URL = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "hystrix.stream";

	/** The stream never ends, so every read is bounded and the request is aborted when we have enough. */
	private static final int SOCKET_TIMEOUT_MS = 3000;
	private static final long READ_DEADLINE_MS = 8000;

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

		List<String> frames = readFrames(SiteKeys.TEST_SITE_KEY_LOCALHOST);

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
				401, statusFor(null));
	}

	@Test
	public void streamRefusesAWrongSiteKey() throws Exception
	{
		assertEquals(401, statusFor("wrong-site-key"));
	}

	// ---- helpers ------------------------------------------------------------

	private static int statusFor(String siteKey) throws Exception
	{
		HttpGet get = new HttpGet(STREAM_URL);
		if (null != siteKey)
			get.addHeader(FCHeaders.X_FRONTCACHE_SITE_KEY, siteKey);

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

	private static List<String> readFrames(String siteKey) throws Exception
	{
		List<String> frames = new ArrayList<String>();

		HttpGet get = new HttpGet(STREAM_URL);
		get.addHeader(FCHeaders.X_FRONTCACHE_SITE_KEY, siteKey);

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
}
