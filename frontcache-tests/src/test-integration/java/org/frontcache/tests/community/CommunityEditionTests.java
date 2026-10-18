/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.community;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.io.ActionResponse;
import org.frontcache.io.NodeStatsActionResponse;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.frontcache.tests.base.TestsBase;
import org.junit.Test;

/**
 * Principle 3 of docs/ip-protection-apache-core-and-prop-pro-proposal.md section 3.1 - <b>a missing Pro jar
 * is a non-event</b> - on the running Community topology (section 5.4): both nodes and the console run with
 * no Pro jar on their class path.
 *
 * <ul>
 * <li>a Pro management action answers a ping, the "unknown action" contract a Pro console already reads
 *     as "not available on this node";</li>
 * <li>the console renders no Pro screen - no License tab, no Cache Warmer entry, no Pro script or
 *     stylesheet - and the Pro URLs are not there at all;</li>
 * <li>metrics export, which D3 kept in the core, still serves - with no Pro series in it.</li>
 * </ul>
 *
 * frontcache-pro-tests runs the same topology with the Pro jars, where every one of these is the other way.
 */
public class CommunityEditionTests extends TestsBase {

	/** The e2e console - :frontcache-tests:consoleJetty, credentials from console/conf/frontcache-console.conf. */
	private static final String CONSOLE = "http://localhost:7090/";
	private static final String CONSOLE_AUTH = "Basic " + Base64.getEncoder()
			.encodeToString("e2e:e2e-console-password".getBytes(StandardCharsets.UTF_8));

	private static final List<String> PRO_ACTIONS = List.of(
			"warmer-status", "warmer-lists", "warmer-start", "get-license", "reload-license", "get-usage-report");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Test
	public void proActionsAnswerAPingOnBothNodes() {
		for (FrontCacheClient node : List.of(frontcacheClientStandalone, frontcacheClientFilter))
			for (String action : PRO_ACTIONS)
			{
				ActionResponse response = node.call(Map.of("action", action), ActionResponse.class);
				assertNotNull(action + " on " + node.getFrontCacheURL() + " - no answer", response);
				assertTrue(action + " on " + node.getFrontCacheURL() + " answered " + response.getAction()
						+ " - a Community node knows no Pro action", FrontCacheClient.isPing(response));
			}
	}

	@Test
	public void bothNodesReportNoModule() {
		for (FrontCacheClient node : List.of(frontcacheClientStandalone, frontcacheClientFilter))
		{
			NodeStatsActionResponse stats = node.getNodeStatsActionResponse();
			assertNotNull(node.getFrontCacheURL(), stats);
			assertEquals(node.getFrontCacheURL(), List.of(), stats.getModules());
		}
	}

	/** D3: metrics export is Community. The standalone e2e node exports to Prometheus. */
	@Test
	public void metricsExportStillServesWithNoProSeries() throws Exception {
		HttpResponse<String> scrape = http.send(HttpRequest.newBuilder(
				URI.create(TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST + "fc-metrics"))
				.header("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, scrape.statusCode());
		assertTrue(scrape.body(), scrape.body().contains("frontcache_"));
		assertFalse(scrape.body(), scrape.body().contains("frontcache_license_"));
		assertFalse(scrape.body(), scrape.body().contains("frontcache_warmer_"));
	}

	@Test
	public void theConsoleShowsNoLicenseTabAndNoCacheWarmerEntry() throws Exception {
		// the top menu is on every page; the cache submenu on the cache pages
		for (String page : List.of("realtime", "edges", "guard-rules", "cache-view", "cache-other"))
		{
			HttpResponse<String> response = console(page);
			assertEquals(page, 200, response.statusCode());
			String html = response.body();
			assertTrue(page + " did not render the menu:\n" + html, html.contains("navbar"));

			assertFalse(page + " has a License tab", html.contains("href=\"license\""));
			assertFalse(page + " has a Cache Warmer entry", html.contains("Cache Warmer") || html.contains("cache-warmer"));
			assertFalse(page + " links the warmer's stylesheet", html.contains("fc-warmer"));
			assertFalse(page + " loads the cluster script", html.contains("fc-dashboard-cluster"));
		}
	}

	@Test
	public void theProScreensAndFilesAreNotThere() throws Exception {
		for (String path : List.of("license", "license-report.json", "cache-warmer", "cache-warmer-status.json",
				"js/fc-warmer.js", "css/fc-warmer.css", "js/fc-dashboard-cluster.js"))
			assertEquals(path, 404, console(path).statusCode());
	}

	private HttpResponse<String> console(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(CONSOLE + path))
				.header("Authorization", CONSOLE_AUTH)
				.timeout(Duration.ofSeconds(30))
				.GET().build(), HttpResponse.BodyHandlers.ofString());
	}
}
