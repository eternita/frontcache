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
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Validates CacheProcessor.getCachedKeys() against the InMemoryCacheProcessor
 * implementation: keys reflect exactly what was put to the cache, with no
 * duplicates, and shrink as entries are removed.
 */
public class InMemoryCacheProcessorGetCachedKeysTest {

	private static final String DOMAIN = "test-domain";

	private CacheProcessor cacheProcessor;

	@Before
	public void setUp() {
		cacheProcessor = new InMemoryCacheProcessor();
		Properties props = new Properties();
		// big enough that nothing is evicted for the small test payloads below
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M");
		cacheProcessor.init(props);
	}

	@After
	public void tearDown() {
		cacheProcessor.destroy();
	}

	private void put(String url) {
		cacheProcessor.putToCache(DOMAIN, url, new WebResponse(url, ("content of " + url).getBytes()));
	}

	@Test
	public void emptyCacheReturnsEmptyKeyList() {
		List<String> keys = cacheProcessor.getCachedKeys();
		assertNotNull("getCachedKeys() must never return null", keys);
		assertTrue("expected no keys for an empty cache", keys.isEmpty());
	}

	@Test
	public void keysReflectAllCachedEntries() {
		String url1 = "http://test.example.com/page1.html";
		String url2 = "http://test.example.com/page2.html";
		String url3 = "http://test.example.com/section/page3.html";

		put(url1);
		put(url2);
		put(url3);

		List<String> keys = cacheProcessor.getCachedKeys();
		assertNotNull(keys);
		assertEquals(3, keys.size());
		assertTrue(keys.contains(url1));
		assertTrue(keys.contains(url2));
		assertTrue(keys.contains(url3));
	}

	@Test
	public void cachingSameUrlTwiceYieldsSingleKey() {
		String url = "http://test.example.com/page1.html";

		put(url);
		put(url); // overwrite - keys are unique per url

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(1, keys.size());
		assertTrue(keys.contains(url));
	}

	@Test
	public void removedEntriesDisappearFromKeys() {
		String url1 = "http://test.example.com/page1.html";
		String url2 = "http://test.example.com/page2.html";

		put(url1);
		put(url2);
		assertEquals(2, cacheProcessor.getCachedKeys().size());

		cacheProcessor.removeFromCache(DOMAIN, url1);

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(1, keys.size());
		assertFalse(keys.contains(url1));
		assertTrue(keys.contains(url2));

		cacheProcessor.removeFromCacheAll(DOMAIN);
		assertTrue(cacheProcessor.getCachedKeys().isEmpty());
	}
}
