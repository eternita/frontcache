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
package org.frontcache.cache.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Properties;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Validates CacheProcessor.getCachedKeys() against the L1L2CacheProcessor (the
 * production default). The interesting bit here is that getCachedKeys() must
 * merge keys from BOTH tiers - L1 (Ehcache, in-memory) and L2 (Lucene, on-disk).
 */
public class L1L2CacheProcessorGetCachedKeysTest {

	private static final String DOMAIN = "test-domain";

	private CacheProcessor cacheProcessor;

	@Before
	public void setUp() {
		cacheProcessor = new L1L2CacheProcessor();
		Properties props = new Properties();
		// point the L2 Lucene index at a throwaway dir so the test doesn't need FRONTCACHE_HOME.
		// CACHE_RELATIVE_DIR ("cache/l2-lucene-index/") is appended to this base by the processor.
		props.setProperty("front-cache.cache-processor.impl.cache-dir",
				"/tmp/l1l2-getcachedkeys-test-" + System.currentTimeMillis() + "/");
		// no ehcache-config property -> processor falls back to the default Ehcache CacheManager
		cacheProcessor.init(props);
	}

	@After
	public void tearDown() {
		cacheProcessor.destroy();
	}

	/** Build a response that passes putToCache's cacheability checks (non-empty, subject-to-cache, status < 500). */
	private WebResponse cacheable(String url) {
		WebResponse response = new WebResponse(url, ("content of " + url).getBytes(), "30d", null);
		response.setStatusCode(200);
		return response;
	}

	private void putL2(String url) { // default cache level -> Lucene
		cacheProcessor.putToCache(DOMAIN, url, cacheable(url));
	}

	private void putL1(String url) { // explicit L1 -> Ehcache
		WebResponse response = cacheable(url);
		response.setCacheLevel(FCHeaders.CACHE_LEVEL_L1);
		cacheProcessor.putToCache(DOMAIN, url, response);
	}

	@Test
	public void emptyCacheReturnsEmptyKeyList() {
		List<String> keys = cacheProcessor.getCachedKeys();
		assertNotNull("getCachedKeys() must never return null", keys);
		assertTrue("expected no keys for an empty cache", keys.isEmpty());
	}

	@Test
	public void l2KeysAreReturned() {
		String url1 = "http://test.example.com/l2/page1.html";
		String url2 = "http://test.example.com/l2/page2.html";
		putL2(url1);
		putL2(url2);

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(2, keys.size());
		assertTrue(keys.contains(url1));
		assertTrue(keys.contains(url2));
	}

	@Test
	public void l1KeysAreReturned() {
		String url1 = "http://test.example.com/l1/page1.html";
		String url2 = "http://test.example.com/l1/page2.html";
		putL1(url1);
		putL1(url2);

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(2, keys.size());
		assertTrue(keys.contains(url1));
		assertTrue(keys.contains(url2));
	}

	@Test
	public void keysAreMergedAcrossL1AndL2() {
		String l1Url = "http://test.example.com/l1/page.html";
		String l2Url = "http://test.example.com/l2/page.html";
		putL1(l1Url);
		putL2(l2Url);

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals("keys from both tiers must be present", 2, keys.size());
		assertTrue("L1 (Ehcache) key missing", keys.contains(l1Url));
		assertTrue("L2 (Lucene) key missing", keys.contains(l2Url));
	}

	@Test
	public void removedEntriesDisappearFromKeys() {
		String l1Url = "http://test.example.com/l1/page.html";
		String l2Url = "http://test.example.com/l2/page.html";
		putL1(l1Url);
		putL2(l2Url);
		assertEquals(2, cacheProcessor.getCachedKeys().size());

		// removeFromCache scans both tiers by substring filter
		cacheProcessor.removeFromCache(DOMAIN, l2Url);
		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(1, keys.size());
		assertFalse(keys.contains(l2Url));
		assertTrue(keys.contains(l1Url));
	}
}
