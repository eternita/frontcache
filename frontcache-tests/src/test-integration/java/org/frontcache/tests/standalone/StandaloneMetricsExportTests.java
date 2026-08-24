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

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.TestsBase;
import org.junit.Test;

/**
 * End-to-end coverage of the Prometheus scrape endpoint.
 *
 * The unit tests cover the gate and the exposition in isolation; this covers the part they cannot - that
 * the exporter is actually wired into engine startup, that the Micrometer binders see the real
 * registries, and that a scraper gets a parseable body from a running node. The standalone test node sets
 * {@code front-cache.metrics.export=prometheus}; the filter node does not, which is what keeps the
 * "off by default" path exercised too.
 */
public class StandaloneMetricsExportTests extends TestsBase {

	private static final String METRICS_URL = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "fc-metrics";

	@Test
	public void scrapeExposesCommandAndPoolSeriesFromRealTraffic() throws Exception
	{
		for (int i = 0; i < 3; i++)
			webClient.getPage(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/jsp-read/a.jsp");

		CloseableHttpClient client = HttpClients.createDefault();
		try {
			CloseableHttpResponse response = client.execute(new HttpGet(METRICS_URL));
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());
				assertTrue("Prometheus text format expected, got "
								+ response.getFirstHeader("Content-Type").getValue(),
						response.getFirstHeader("Content-Type").getValue().contains("version=0.0.4"));

				String body = EntityUtils.toString(response.getEntity());

				// The command taxonomy - the part Resilience4j's own metrics do not carry, and the reason
				// FcCommandMetrics is bridged into Micrometer rather than replaced by it.
				assertTrue("no command event series:\n" + head(body), body.contains("frontcache_command_events"));
				assertTrue("Input-Requests not exported", body.contains("command=\"Input-Requests\""));
				assertTrue("Cache-Origin-http not exported", body.contains("command=\"Cache-Origin-http\""));
				assertTrue("success events not exported", body.contains("event=\"success\""));

				// Resilience4j's binders, under our prefix.
				assertTrue("circuit breaker series missing", body.contains("frontcache_resilience4j_circuitbreaker"));

				// The pool the console's panel reports on.
				assertTrue("thread pool series missing", body.contains("pool=\"OriginHitsPool\""));

				// Cardinality: nothing may be labelled by request URL.
				assertFalse("a series is labelled with a URL path - unbounded cardinality on a page cache",
						body.contains("=\"/common/") || body.contains("jsp-read/a.jsp"));
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

	private static String head(String body)
	{
		return body.length() > 2000 ? body.substring(0, 2000) : body;
	}
}
