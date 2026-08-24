/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache.impl;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * "Invalidate all" must empty BOTH tiers of L1L2CacheProcessor, leaving nothing behind.
 *
 * There used to be a second, domain-scoped flavour that only dropped entries stamped with the
 * caller's domain. Entries pushed in by index replication carry the SOURCE edge's domain, so it
 * walked straight past them - which is how a production edge kept serving ~336k replicated pages
 * after a "*" invalidation. Retiring multi-domain support removed the scoped flavour along with the
 * domain stamp, so that failure mode no longer has a way to happen; this test pins the behaviour
 * that replaced it.
 */
public class L1L2CacheProcessorRemoveAllTest {

	private CacheProcessor cacheProcessor;

	@Before
	public void setUp() {
		cacheProcessor = new L1L2CacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.cache-dir",
				"/tmp/l1l2-removeall-test-" + System.currentTimeMillis() + "/");
		cacheProcessor.init(props);
	}

	@After
	public void tearDown() {
		cacheProcessor.destroy();
	}

	private void put(String url, String cacheLevel) {
		WebResponse r = new WebResponse(url, ("content of " + url).getBytes());
		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, CacheProcessor.CACHE_FOREVER);
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, CacheProcessor.CACHE_FOREVER);
		r.setExpireTimeMap(expireTimeMap);
		r.setStatusCode(200);
		r.setCacheLevel(cacheLevel); // null -> L2 (Lucene), "L1" -> Ehcache
		cacheProcessor.putToCache(url, r);
	}

	@Test
	public void removeAllEmptiesBothTiers() {
		put("http://test.com/l2-a.html", null);
		put("http://test.com/l2-b.html", null);
		put("http://test.com/l1-a.html", FCHeaders.CACHE_LEVEL_L1);
		put("http://test.com/l1-b.html", FCHeaders.CACHE_LEVEL_L1);

		assertEquals(4, cacheProcessor.getCachedKeys().size());

		cacheProcessor.removeFromCacheAll();

		assertEquals("nothing may survive", 0, cacheProcessor.getCachedKeys().size());
	}

	/**
	 * An entry that arrived by replication is indistinguishable from a locally-cached one, so
	 * "invalidate all" must take it too. Before the domain stamp went away, this was the case that
	 * silently survived.
	 */
	@Test
	public void removeAllTakesReplicatedEntriesToo() {
		// as written by FrontCacheIOServlet's put-to-cache on behalf of a source edge
		put("/replicated-from-another-edge.html", null);
		put("http://test.com/local.html", null);

		assertEquals(2, cacheProcessor.getCachedKeys().size());

		cacheProcessor.removeFromCacheAll();

		assertEquals("replicated entries must not survive an invalidate-all",
				0, cacheProcessor.getCachedKeys().size());
	}
}
