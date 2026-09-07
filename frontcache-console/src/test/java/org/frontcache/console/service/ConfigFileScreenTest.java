/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.console.model.EdgeConfigFile;
import org.frontcache.io.ActionResponse;
import org.frontcache.io.ConfigFile;
import org.frontcache.io.SetConfigActionResponse;
import org.junit.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * The console side of editing a config file: which edges a screen shows, and when the Edit button is
 * offered at all.
 *
 * The edges here are deliberately unreachable. What is under test is the console's own bookkeeping -
 * per-edge identity and the gates - and nothing about that should need a node.
 */
public class ConfigFileScreenTest {

	private static Config config(String hocon) {
		return ConfigFactory.parseString(hocon);
	}

	// ---- the regression that made this feature unsafe ----------------------------------------

	/**
	 * <b>Two edges on one host at different ports must produce two tabs.</b>
	 *
	 * {@code FrontCacheClient.getName()} strips the scheme and everything from the first colon, so
	 * localhost:8080 and localhost:9080 - this project's own e2e topology - are both called
	 * "localhost". The config screens used to collect their fan-out into a {@code Map} keyed by that
	 * name, so one of the two silently vanished from the page.
	 *
	 * Read-only that cost a tab. With a Save button on the tab, an edit addressed by name would have
	 * gone to whichever edge won the {@code put} - not necessarily the one whose file was on screen.
	 */
	@Test
	public void twoEdgesOnOneHostKeepTheirOwnTabs() {

		FrontcacheService service = new FrontcacheService();
		service.addEdge("http://127.0.0.1:1/");
		service.addEdge("http://127.0.0.1:2/");

		List<EdgeConfigFile> edges = service.getConfigFiles(ConfigFile.BOTS);

		assertEquals("both edges must be on the page", 2, edges.size());

		assertEquals("the display names DO collide - that is the whole point",
				edges.get(0).getName(), edges.get(1).getName());

		Set<String> urls = new HashSet<String>();
		for (EdgeConfigFile edge : edges)
			urls.add(edge.getUrl());

		assertEquals("...and the URLs, which is what a save is addressed to, must not", 2, urls.size());
	}

	/**
	 * Edge order is the console config's order, which is the order every other screen uses. A save
	 * names a node in its button, so a tab strip that reordered itself between page loads would be a
	 * way to click the wrong one.
	 */
	@Test
	public void edgesComeBackInConfiguredOrder() {

		FrontcacheService service = new FrontcacheService();
		service.addEdge("http://127.0.0.1:1/");
		service.addEdge("http://127.0.0.2:1/");
		service.addEdge("http://127.0.0.3:1/");

		List<EdgeConfigFile> edges = service.getConfigFiles(ConfigFile.BOTS);

		assertEquals("http://127.0.0.1:1/", edges.get(0).getUrl());
		assertEquals("http://127.0.0.2:1/", edges.get(1).getUrl());
		assertEquals("http://127.0.0.3:1/", edges.get(2).getUrl());
	}

	// ---- save to all edges -------------------------------------------------------------------

	/**
	 * One result per edge, including the ones that failed.
	 *
	 * Each node is a separate HTTP call to a separate machine, so a partial result is the normal
	 * outcome of "Save to all nodes" - a summary line would have to pick a winner between "saved" and
	 * "failed", and either choice is wrong for some of the fleet.
	 */
	@Test
	public void savingToAllEdgesReportsEveryEdgeIncludingFailures() {

		FrontcacheService service = new FrontcacheService();
		service.addEdge("http://127.0.0.1:1/");
		service.addEdge("http://127.0.0.1:2/");

		Map<String, SetConfigActionResponse> results = service.saveConfigFileToAllEdges(
				ConfigFile.BOTS, "Googlebot\n", null, "http://127.0.0.1:1/");

		assertEquals(2, results.size());
		assertTrue(results.containsKey("http://127.0.0.1:1/"));
		assertTrue(results.containsKey("http://127.0.0.1:2/"));

		// This console has no credentials configured, so editing is off and every edge is refused
		// before a single byte reaches the network - see the gate tests below.
		for (SetConfigActionResponse result : results.values())
			assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, result.getResponseStatus());
	}

	// ---- the gate ----------------------------------------------------------------------------

	/**
	 * <b>No credentials means no editing, whatever the property says.</b>
	 *
	 * An unauthenticated console is readable by anyone who can reach the port. It must not also be a
	 * way to rewrite the fleet's guard rules, bot classification and origin timeouts. This is the
	 * mirror of the node's own rule ({@code ApiKey.isAuthorizedToWrite} refuses a write on a node with
	 * no API key) and it exists for the same reason: neither permissive state was chosen by anyone,
	 * both are what an un-migrated config file produces.
	 */
	@Test
	public void editingIsOffWithoutCredentials() {
		assertFalse(FrontcacheService.configEditingEnabled(config(""), false));
		assertFalse(FrontcacheService.configEditingEnabled(config("config-editing = true"), false));
	}

	/** A config written before this feature existed has no such key, and editing is on. */
	@Test
	public void editingIsOnByDefaultForAnAuthenticatedConsole() {
		assertTrue(FrontcacheService.configEditingEnabled(config(""), true));
		assertTrue(FrontcacheService.configEditingEnabled(null, true));
	}

	/** A read-only console. */
	@Test
	public void editingCanBeTurnedOffExplicitly() {
		assertFalse(FrontcacheService.configEditingEnabled(config("config-editing = false"), true));
	}

	/**
	 * A save is refused by the console itself when editing is off, rather than being sent to the node
	 * and refused there. Both ends check; this is the one that keeps the request off the wire.
	 */
	@Test
	public void aSaveIsRefusedWhenEditingIsOff() {

		FrontcacheService service = new FrontcacheService(); // no config file -> no credentials
		assertFalse(service.isConfigEditingEnabled());

		SetConfigActionResponse response =
				service.saveConfigFile("http://127.0.0.1:1/", ConfigFile.BOTS, "Googlebot\n", null);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
		assertEquals("bots.conf", response.getFile());
	}
}
