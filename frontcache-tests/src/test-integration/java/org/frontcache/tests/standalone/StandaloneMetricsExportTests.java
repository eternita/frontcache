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

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
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
 *
 * A scrape carries the API key as {@code Authorization: Bearer}, the one form every Frontcache caller
 * uses and the only one Prometheus itself can send. {@code unauthenticatedScrapeIsRefused} is the
 * reason that matters: this endpoint used to answer anyone who could reach the connector.
 */
public class StandaloneMetricsExportTests extends TestsBase {

	private static final String METRICS_URL = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "fc-metrics";

	/** What a Prometheus scrape_config sends as authorization.credentials. */
	private static HttpGet authenticatedScrape()
	{
		HttpGet get = new HttpGet(METRICS_URL);
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);
		return get;
	}

	@Test
	public void scrapeExposesCommandAndPoolSeriesFromRealTraffic() throws Exception
	{
		for (int i = 0; i < 3; i++)
			webClient.getPage(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/jsp-read/a.jsp");

		CloseableHttpClient client = HttpClients.createDefault();
		try {
			ClassicHttpResponse response = client.execute(authenticatedScrape());
			try {
				assertEquals(200, response.getCode());
				assertTrue("Prometheus text format expected, got "
								+ response.getFirstHeader("Content-Type").getValue(),
						response.getFirstHeader("Content-Type").getValue().contains("version=0.0.4"));

				String body = EntityUtils.toString(response.getEntity());

				// The command taxonomy - the part Resilience4j's own metrics do not carry, and the reason
				// FcCommandMetrics is bridged into Micrometer rather than replaced by it.
				assertTrue("no command event series:\n" + head(body), body.contains("frontcache_command_events"));
				assertTrue("Input-Request not exported", body.contains("command=\"Input-Request\""));
				assertTrue("Cache-Miss not exported", body.contains("command=\"Cache-Miss\""));
				assertTrue("success events not exported", body.contains("event=\"success\""));

				// Resilience4j's binders, under our prefix.
				assertTrue("circuit breaker series missing", body.contains("frontcache_resilience4j_circuitbreaker"));

				// The pool the console's panel reports on.
				assertTrue("thread pool series missing", body.contains("pool=\"OriginPool\""));

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
			ClassicHttpResponse response = client.execute(authenticatedScrape());
			try {
				assertEquals(200, response.getCode());
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

	/**
	 * The combine series, as deltas across one real page render.
	 *
	 * Values, not presence. The claim the whole feature rests on is "N fragments, one origin request",
	 * and it is exported as a ratio of two counters -
	 * {@code members{result="combined"} / batches{result="ok"}} - so a test that only asserted the
	 * series existed would pass with the ratio wrong. A three-cell page on an empty cache must move
	 * those two by exactly 3 and exactly 1.
	 *
	 * The second assertion is the accounting invariant: every combine candidate lands in exactly one
	 * member bucket. A member counted twice, or not at all, makes the ratio quietly wrong in a way no
	 * single counter reveals.
	 */
	@Test
	public void scrapeExposesCombineSeriesFromRealTraffic() throws Exception
	{
		frontcacheClientStandalone.removeFromCacheAll();
		frontcacheClientFilter.removeFromCacheAll();

		String before = scrapeBody();

		webClient.getPage(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/combine/page.jsp");

		String after = scrapeBody();

		assertTrue("no combine series:\n" + head(after), after.contains("frontcache_include_combine_batches_total"));

		assertEquals("three cells must have cost exactly one combined origin call",
				1, delta(before, after, "frontcache_include_combine_batches_total{result=\"ok\"}"));
		assertEquals("and that one call must have served all three",
				3, delta(before, after, "frontcache_include_combine_members_total{result=\"combined\"}"));

		long members = 0;
		for (String result : new String[]{"cached", "combined", "individual", "fallback"})
			members += delta(before, after, "frontcache_include_combine_members_total{result=\"" + result + "\"}");

		assertEquals("every combine candidate must land in exactly one bucket", 3, members);

		assertFalse("a combine series is labelled with a URL - unbounded cardinality",
				after.contains("frontcache_include_combine_members_total{result=\"combined\",url"));
	}

	private String scrapeBody() throws Exception
	{
		CloseableHttpClient client = HttpClients.createDefault();
		try {
			ClassicHttpResponse response = client.execute(authenticatedScrape());
			try {
				assertEquals(200, response.getCode());
				return EntityUtils.toString(response.getEntity());
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

	/** How far one Prometheus series moved between two scrapes. An absent series reads as 0. */
	private static long delta(String before, String after, String series)
	{
		return value(after, series) - value(before, series);
	}

	private static long value(String body, String series)
	{
		for (String line : body.split("\n"))
			if (line.startsWith(series + " "))
				return (long) Double.parseDouble(line.substring(series.length() + 1).trim());

		return 0;
	}

	/**
	 * The endpoint's access control, over real HTTP.
	 *
	 * It publishes traffic volumes, error rates, circuit state and this node's heap - and before the
	 * API key was required here, the only thing in front of it was the since-removed management-port
	 * check, which compared a Host header the caller supplies. Anyone who could reach a connector could
	 * read all of it. The unit tests cover the branch; this covers that the branch is actually in the
	 * request path of a running node.
	 */
	@Test
	public void unauthenticatedScrapeIsRefused() throws Exception
	{
		CloseableHttpClient client = HttpClients.createDefault();
		try {
			ClassicHttpResponse response = client.execute(new HttpGet(METRICS_URL));
			try {
				assertEquals("a scrape with no credentials must not be served", 401,
						response.getCode());
			} finally {
				response.close();
			}

			HttpGet wrongKey = new HttpGet(METRICS_URL);
			wrongKey.addHeader("Authorization", "Bearer not-this-nodes-key");
			response = client.execute(wrongKey);
			try {
				assertEquals(401, response.getCode());
			} finally {
				response.close();
			}

		} finally {
			client.close();
		}
	}

	/**
	 * Just the {@code # TYPE} lines. {@link #head(String)} truncates alphabetically-early series, which
	 * is exactly the wrong window when the assertion that failed is about frontcache_guard_*.
	 */
	private static String typeLines(String body)
	{
		StringBuilder types = new StringBuilder();
		for (String line : body.split("\n"))
			if (line.startsWith("# TYPE "))
				types.append(line).append('\n');

		return types.toString();
	}

	private static String head(String body)
	{
		return body.length() > 2000 ? body.substring(0, 2000) : body;
	}

	/**
	 * The guard series, added in phase 2 of the rate-limit work.
	 *
	 * Their counts existed before this only behind {@code get-guard-rules}, i.e. reachable by the
	 * console and by nothing else - so "is this rule firing?" and "is a new dry-run rule about to
	 * refuse real visitors?" could not be alerted on. The standalone node carries a rate rule scoped to
	 * /common/rate-limit/ (see FRONTCACHE_HOME_STANDALONE/conf/guard-rules.conf), so driving that path
	 * over its limit is what makes the counter move.
	 */
	@Test
	public void scrapeExposesGuardRuleCountsAndRateLimitBuckets() throws Exception
	{
		// The 429s are the point of this test, so they must not abort it. webClient is rebuilt per test
		// by TestsBase.setUp(), so this needs no restoring.
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);

		// 3 per 2-second slot on this path - a few extra guarantee at least one rejection, whether or
		// not the burst straddles a window boundary
		for (int i = 0; i < 8; i++)
			webClient.getPage(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "common/rate-limit/a.jsp");

		CloseableHttpClient client = HttpClients.createDefault();
		try {
			ClassicHttpResponse response = client.execute(authenticatedScrape());
			try {
				assertEquals(200, response.getCode());
				String body = EntityUtils.toString(response.getEntity());

				assertTrue("no guard action series:\n" + head(body), body.contains("frontcache_guard_actions"));

				// The rule that did the rejecting, labelled with the log's own disposition word.
				assertTrue("rate rule not exported:\n" + head(body),
						body.contains("name=\"ratelimit-e2e\""));
				assertTrue("rejections not labelled as the log labels them:\n" + head(body),
						body.contains("result=\"rejected\""));

				// The built-ins report through the same model, so they are exported too - which is what
				// makes "how much traffic is structurally broken?" a query rather than a log grep.
				assertTrue("built-in guard rules not exported:\n" + head(body),
						body.contains("name=\"uri-too-long\""));

				// Exemptions are counted even though they write no log line: an allow rule with no hits
				// is dead config sitting in front of the rules that matter.
				assertTrue("allow rules not exported:\n" + head(body),
						body.contains("result=\"allowed\""));

				// The slot-array sizing signal for the rate-limit bucket.
				assertTrue("rate-limit takeover series missing:\n" + head(body),
						body.contains("frontcache_guard_ratelimit_takeovers"));

				// Monotonic, so counters - a reload resets these, and Prometheus must read that as a
				// counter reset rather than as a real decrease.
				assertTrue("guard actions must be a counter:\n" + typeLines(body),
						body.contains("# TYPE frontcache_guard_actions_total counter"));

				// Same cardinality rule as everywhere else: the limiter key never becomes a label. It is
				// recorded in the guard log line instead, where retention bounds it.
				for (String line : body.split("\n"))
					if (line.startsWith("frontcache_guard_"))
						assertFalse("guard series must never be labelled by client address: " + line,
								line.contains("127.0.0.1") || line.contains("#"));
			} finally {
				response.close();
			}
		} finally {
			client.close();
		}
	}

}
