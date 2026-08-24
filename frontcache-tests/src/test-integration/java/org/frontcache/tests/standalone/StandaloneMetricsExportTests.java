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

	/**
	 * The cache series, against a real L1/L2 cache with real traffic through it.
	 *
	 * Worth doing end to end rather than only in a unit test: the sizes are read from a live Ehcache and
	 * a live Lucene IndexWriter, and the whole reason those accessors exist is that the obvious ones
	 * (open a DirectoryReader per call) are too expensive to put behind a scrape. A unit test with a
	 * hand-built processor would not exercise the same wiring the server actually runs.
	 */
	@Test
	public void scrapeExposesCacheSeriesFromRealTraffic() throws Exception
	{
		String url = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/jsp-read/a.jsp";

		// first request populates the cache (a miss), the rest are hits
		for (int i = 0; i < 3; i++)
			webClient.getPage(url);

		CloseableHttpClient client = HttpClients.createDefault();
		try {
			CloseableHttpResponse response = client.execute(new HttpGet(METRICS_URL));
			try {
				assertEquals(200, response.getStatusLine().getStatusCode());
				String body = EntityUtils.toString(response.getEntity());

				// Hit ratio is the number an operator alerts on, and it needs both labels on the wire.
				assertTrue("no cache read series:\n" + head(body),
						body.contains("frontcache_cache_reads_total"));
				assertTrue("hits not exported", body.contains("result=\"hit\""));
				assertTrue("misses not exported - a ratio needs a denominator",
						body.contains("result=\"miss\""));

				// Tier attribution: "90% hit ratio, all of it off disk" is a different situation from
				// "90% hit ratio, all of it in memory", and only the tier label distinguishes them.
				assertTrue("L1 tier not exported", body.contains("tier=\"L1\""));
				assertTrue("L2 tier not exported", body.contains("tier=\"L2\""));

				// Sizes, read from the live cache rather than by reopening the index.
				assertTrue("cache entry gauge missing", body.contains("frontcache_cache_entries"));
				assertTrue("on-disk size gauge missing", body.contains("frontcache_cache_disk_bytes"));
				assertTrue("eviction counter missing", body.contains("frontcache_cache_evictions"));

				assertFalse("a cache series is labelled with a URL - unbounded cardinality",
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
