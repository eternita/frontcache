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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the two "invalidate all" flavours on L1L2CacheProcessor.
 *
 * The domain-scoped one only drops entries stamped with the given domain - entries pushed in by
 * index replication carry the SOURCE edge's domain, which is how a production edge kept serving
 * ~336k replicated pages after a "*" invalidation. The unscoped one empties the node.
 */
public class L1L2CacheProcessorRemoveAllTest {

	private static final String LOCAL_DOMAIN = "fc-edge.test.com";
	private static final String REPLICATED_DOMAIN = "fc-eu.test.com"; // as stamped by the source edge

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

	private void put(String domain, String url, String cacheLevel) {
		WebResponse r = new WebResponse(url, ("content of " + url).getBytes());
		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, CacheProcessor.CACHE_FOREVER);
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, CacheProcessor.CACHE_FOREVER);
		r.setExpireTimeMap(expireTimeMap);
		r.setStatusCode(200);
		r.setCacheLevel(cacheLevel); // null -> L2 (Lucene), "L1" -> Ehcache
		cacheProcessor.putToCache(domain, url, r);
	}

	@Test
	public void domainScopedRemoveAllKeepsEntriesOfOtherDomains() {
		put(LOCAL_DOMAIN, "http://test.com/local.html", null);
		put(REPLICATED_DOMAIN, "http://test.com/replicated.html", null);
		put(LOCAL_DOMAIN, "http://test.com/local-l1.html", FCHeaders.CACHE_LEVEL_L1);
		put(REPLICATED_DOMAIN, "http://test.com/replicated-l1.html", FCHeaders.CACHE_LEVEL_L1);

		assertEquals(4, cacheProcessor.getCachedKeys().size());

		cacheProcessor.removeFromCacheAll(LOCAL_DOMAIN);

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals("only the entries of the other domain survive", 2, keys.size());
		assertTrue(keys.contains("/replicated.html")); // L2 keys are stored as paths
		assertTrue(keys.contains("http://test.com/replicated-l1.html"));
	}

	@Test
	public void unscopedRemoveAllEmptiesBothTiersForEveryDomain() {
		put(LOCAL_DOMAIN, "http://test.com/local.html", null);
		put(REPLICATED_DOMAIN, "http://test.com/replicated.html", null);
		put(LOCAL_DOMAIN, "http://test.com/local-l1.html", FCHeaders.CACHE_LEVEL_L1);
		put(REPLICATED_DOMAIN, "http://test.com/replicated-l1.html", FCHeaders.CACHE_LEVEL_L1);

		assertEquals(4, cacheProcessor.getCachedKeys().size());

		cacheProcessor.removeFromCacheAll();

		assertEquals("nothing may survive", 0, cacheProcessor.getCachedKeys().size());
	}
}
