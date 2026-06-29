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
 * Validates CacheProcessor.purge() on L1L2CacheProcessor: expired entries are
 * removed from both tiers (L1 Ehcache + L2 Lucene), while still-fresh and
 * cache-forever entries are preserved.
 */
public class L1L2CacheProcessorPurgeTest {

	private static final String DOMAIN = "test-domain";

	private CacheProcessor cacheProcessor;

	@Before
	public void setUp() {
		cacheProcessor = new L1L2CacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.cache-dir",
				"/tmp/l1l2-purge-test-" + System.currentTimeMillis() + "/");
		cacheProcessor.init(props);
	}

	@After
	public void tearDown() {
		cacheProcessor.destroy();
	}

	/**
	 * Build a cacheable response with an explicit effective expiration timestamp.
	 *
	 * @param expireMillis absolute epoch millis, or CacheProcessor.CACHE_FOREVER (-1)
	 */
	private WebResponse response(String url, long expireMillis, String cacheLevel) {
		WebResponse r = new WebResponse(url, ("content of " + url).getBytes());
		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, expireMillis);
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, expireMillis);
		r.setExpireTimeMap(expireTimeMap);
		r.setStatusCode(200);
		r.setCacheLevel(cacheLevel); // null -> L2 (Lucene), "L1" -> Ehcache
		return r;
	}

	private void put(String url, long expireMillis, String cacheLevel) {
		cacheProcessor.putToCache(DOMAIN, url, response(url, expireMillis, cacheLevel));
	}

	@Test
	public void purgeRemovesExpiredL2EntriesAndKeepsFreshAndForever() {
		long now = System.currentTimeMillis();
		String expired = "http://test.example.com/l2/expired.html";
		String fresh = "http://test.example.com/l2/fresh.html";
		String forever = "http://test.example.com/l2/forever.html";

		put(expired, now - 60000, null);              // expired a minute ago
		put(fresh, now + 600000, null);               // expires in 10 minutes
		put(forever, CacheProcessor.CACHE_FOREVER, null);

		assertEquals(3, cacheProcessor.getCachedKeys().size());

		cacheProcessor.purge();

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(2, keys.size());
		assertFalse("expired entry must be purged", keys.contains(expired));
		assertTrue("fresh entry must survive", keys.contains(fresh));
		assertTrue("cache-forever entry must survive", keys.contains(forever));
	}

	@Test
	public void purgeRemovesExpiredL1Entries() {
		long now = System.currentTimeMillis();
		String expired = "http://test.example.com/l1/expired.html";
		String fresh = "http://test.example.com/l1/fresh.html";

		put(expired, now - 60000, FCHeaders.CACHE_LEVEL_L1);
		put(fresh, now + 600000, FCHeaders.CACHE_LEVEL_L1);

		assertEquals(2, cacheProcessor.getCachedKeys().size());

		cacheProcessor.purge();

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(1, keys.size());
		assertFalse(keys.contains(expired));
		assertTrue(keys.contains(fresh));
	}

	@Test
	public void purgeSpansBothTiers() {
		long now = System.currentTimeMillis();
		String l1Expired = "http://test.example.com/l1/expired.html";
		String l2Expired = "http://test.example.com/l2/expired.html";
		String l1Fresh = "http://test.example.com/l1/fresh.html";
		String l2Fresh = "http://test.example.com/l2/fresh.html";

		put(l1Expired, now - 60000, FCHeaders.CACHE_LEVEL_L1);
		put(l2Expired, now - 60000, null);
		put(l1Fresh, now + 600000, FCHeaders.CACHE_LEVEL_L1);
		put(l2Fresh, now + 600000, null);

		cacheProcessor.purge();

		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(2, keys.size());
		assertTrue(keys.contains(l1Fresh));
		assertTrue(keys.contains(l2Fresh));
		assertFalse(keys.contains(l1Expired));
		assertFalse(keys.contains(l2Expired));
	}
}
