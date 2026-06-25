/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.standalone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.TestUtils;
import org.frontcache.tests.base.TestsBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 *
 * Validates the cache-by-cache scenario: a request sent to the standalone node
 * (FC1, :9080) is forwarded to its origin {@code origin.fc1-test.org:8080}, which
 * is the FC2 filter. After the request the same content must be cached in BOTH
 * Frontcache instances - FC1 and FC2.
 *
 */
public class StandaloneCacheStorageTests extends TestsBase {

	// include-free, cacheable fixture (maxage="1h", renders "a") - one top-level
	// cache entry per node, so assertions stay unambiguous.
	private static final String PATH = "common/debug/a.jsp";

	// FC1 (standalone) caches under the request URL it received.
	private static final String FC1_KEY = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_FC1 + PATH;

	// FC2 (filter) caches under the URL FC1 used to call its origin
	// (origin host alias from FRONTCACHE_HOME_STANDALONE/conf/frontcache.properties).
	private static final String FC2_KEY = "http://origin." + TestConfig.TEST_DOMAIN_FC1 + ":8080/" + PATH;

	@Before
	public void setUp() throws Exception {
		super.setUp(); // builds frontcacheClientStandalone (:9080) & frontcacheClientFilter (:8080), cleans both caches
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	private static int cachedEntries(FrontCacheClient client) {
		return Integer.parseInt(client.getCacheState().get(CacheProcessor.CACHED_ENTRIES));
	}

	/**
	 * One request through FC1 must populate both FC1's and FC2's caches.
	 *
	 * @throws Exception
	 */
	@Test
	public void contentCachedInBothNodes() throws Exception {

		// baseline - both caches are empty after setUp cleanup
		assertEquals("FC1 (standalone) cache should start empty", 0, cachedEntries(frontcacheClientStandalone));
		assertEquals("FC2 (filter) cache should start empty", 0, cachedEntries(frontcacheClientFilter));

		// 1st request to the standalone node -> FC1 -> origin (FC2 filter) -> web app.
		// FC1 cache is empty, so this request must be served dynamically.
		HtmlPage page = webClient.getPage(getStandaloneBaseURLDomainFC1() + PATH);
		assertEquals("a", page.getPage().asText());

		String trace1 = page.getWebResponse().getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");
		assertEquals("1st request must be dynamic (FC1 cache miss): " + trace1,
				false, TestUtils.isRequestFromCache(trace1));

		// both nodes must now hold a cached entry
		assertTrue("FC1 (standalone) should have cached the page", cachedEntries(frontcacheClientStandalone) > 0);
		assertTrue("FC2 (filter) should have cached the page", cachedEntries(frontcacheClientFilter) > 0);

		// and the cached content in each node must be the page body
		WebResponse fc1Cached = frontcacheClientStandalone.getFromCache(FC1_KEY);
		assertNotNull("FC1 (standalone) should have the page in cache for key " + FC1_KEY, fc1Cached);
		assertEquals("a", new String(fc1Cached.getContent()));

		WebResponse fc2Cached = frontcacheClientFilter.getFromCache(FC2_KEY);
		assertNotNull("FC2 (filter) should have the page in cache for key " + FC2_KEY, fc2Cached);
		assertEquals("a", new String(fc2Cached.getContent()));

		// 2nd request - FC1 now serves the page from its own cache.
		page = webClient.getPage(getStandaloneBaseURLDomainFC1() + PATH);
		assertEquals("a", page.getPage().asText());

		String trace2 = page.getWebResponse().getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");
		assertEquals("2nd request must be served from cache: " + trace2,
				true, TestUtils.isRequestFromCache(trace2));
	}

}
