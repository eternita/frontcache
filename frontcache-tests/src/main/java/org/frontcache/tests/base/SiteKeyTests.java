/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

import org.frontcache.client.FrontCacheClient;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * Management API (FrontCacheIOServlet) site-key validation.
 *
 * The management endpoint gates every action against the configured
 * front-cache.site-key (test-site-key-localhost in both test FRONTCACHE_HOME dirs).
 * A request carrying the matching site-key header is served; a request with a
 * wrong or blank site key gets an "Access denied" response.
 *
 */
public abstract class SiteKeyTests extends TestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	public abstract String getFrontCacheBaseURLLocalhost();

	@Test
	public void validSiteKey() throws Exception {

		FrontCacheClient client = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);

		String response = client.removeFromCacheAll();

		// matching site key -> action is executed
		Assert.assertNotEquals("expected invalidate action to run, got: " + response, -1, response.indexOf("invalidate"));
		Assert.assertNotEquals("expected OK status, got: " + response, -1, response.indexOf("OK"));
		Assert.assertEquals("unexpected access denied, got: " + response, -1, response.indexOf("Access denied"));
		return;
	}

	@Test
	public void invalidSiteKey() throws Exception {

		FrontCacheClient client = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), "wrong-site-key");

		String response = client.removeFromCacheAll();

		// site key does not match -> access denied, action not executed
		Assert.assertNotEquals("expected access denied, got: " + response, -1, response.indexOf("Access denied"));
		Assert.assertEquals("action must not run for a wrong site key, got: " + response, -1, response.indexOf("invalidate"));
		return;
	}

	@Test
	public void blankSiteKey() throws Exception {

		FrontCacheClient client = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), "");

		String response = client.removeFromCacheAll();

		// blank site key -> access denied, action not executed
		Assert.assertNotEquals("expected access denied, got: " + response, -1, response.indexOf("Access denied"));
		Assert.assertEquals("action must not run for a blank site key, got: " + response, -1, response.indexOf("invalidate"));
		return;
	}

}
