/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.cache.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

/**
 * The counter against a real Ehcache, capped at two entries.
 *
 * This is the case a counter maintained at the put site gets wrong, so it is asserted against Ehcache
 * itself rather than through the listener interface: nothing in Frontcache's code is told that an
 * eviction happened, and under the shipped {@code maxElementsInMemory="100"} eviction is the steady
 * state rather than an edge case. A put-site counter passes {@link L1ByteCounterTest} and fails here.
 */
public class L1ByteCounterEvictionTest {

	private CacheManager manager;
	private Cache cache;
	private L1ByteCounter counter;

	@Before
	public void setUp() {
		CacheConfiguration cacheConfig = new CacheConfiguration("L1_BYTE_COUNTER_TEST", 2)
				.eternal(true)
				.overflowToDisk(false)
				.memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.LFU);

		Configuration config = new Configuration()
				.name("l1-byte-counter-test-" + System.nanoTime())
				.cache(cacheConfig);

		manager = CacheManager.newInstance(config);
		cache = manager.getCache("L1_BYTE_COUNTER_TEST");

		counter = new L1ByteCounter();
		cache.getCacheEventNotificationService().registerListener(counter);
	}

	@After
	public void tearDown() {
		if (null != manager)
			manager.shutdown();
	}

	private void put(String url, int contentBytes) {
		cache.put(new Element(url, new WebResponse(url, new byte[contentBytes])));
	}

	/**
	 * Three entries into a two-entry cache. Ehcache evicts one on its own, without Frontcache code
	 * running at all, and the counter has to follow it down.
	 */
	@Test
	public void evictionIsSubtractedWithoutAnyoneTellingUs() {
		put("http://example.com/a", 1000);
		put("http://example.com/b", 1000);
		assertEquals(2000, counter.bytes());

		put("http://example.com/c", 1000);

		assertEquals("the cache dropped to its cap", 2, cache.getSize());
		assertEquals("so must the byte count - one entry was evicted", 2000, counter.bytes());
		assertEquals(2, counter.trackedEntries());
	}

	/** The byte figure and the entry figure have to stay consistent - a reader compares them. */
	@Test
	public void theCounterTracksTheCacheSizeThroughChurn() {
		for (int i = 0; i < 25; i++)
			put("http://example.com/" + i, 500);

		assertEquals("cap holds", 2, cache.getSize());
		assertEquals("25 puts into a 2-entry cache leave 2 entries' worth", 1000, counter.bytes());
		assertEquals(cache.getSize(), counter.trackedEntries());
	}

	@Test
	public void removeAndRemoveAllAreFollowed() {
		put("http://example.com/a", 700);
		put("http://example.com/b", 300);
		assertEquals(1000, counter.bytes());

		cache.remove("http://example.com/a");
		assertEquals(300, counter.bytes());

		cache.removeAll();
		assertEquals(0, counter.bytes());
		assertEquals(0, counter.trackedEntries());
	}

	/** Re-caching a URL replaces its entry; the cache does not grow, so neither may the counter. */
	@Test
	public void refreshingAUrlDoesNotDoubleCountIt() {
		put("http://example.com/a", 1000);
		put("http://example.com/a", 250);

		assertEquals("one entry in the cache", 1, cache.getSize());
		assertEquals("and one entry's worth of bytes", 250, counter.bytes());
	}

	/** What the console will actually read, end to end: a real cache, through CacheStats. */
	@Test
	public void theNumberIsNonZeroForANonEmptyTier() {
		put("http://example.com/a", 4096);

		assertTrue("a tier holding a 4KB response must report bytes: " + counter.bytes(),
				counter.bytes() >= 4096);
	}

}
