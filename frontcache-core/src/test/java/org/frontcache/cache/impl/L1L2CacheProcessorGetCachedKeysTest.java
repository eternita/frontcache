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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
		cacheProcessor.putToCache(url, cacheable(url));
	}

	private void putL1(String url) { // explicit L1 -> Ehcache
		WebResponse response = cacheable(url);
		response.setCacheLevel(FCHeaders.CACHE_LEVEL_L1);
		cacheProcessor.putToCache(url, response);
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
		// L2 (Lucene) keys are stored as paths only (scheme, host, port stripped)
		assertTrue(keys.contains("/l2/page1.html"));
		assertTrue(keys.contains("/l2/page2.html"));
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
		// L2 (Lucene) keys are stored as paths only (scheme, host, port stripped)
		assertTrue("L2 (Lucene) key missing", keys.contains("/l2/page.html"));
	}

	@Test
	public void removedEntriesDisappearFromKeys() {
		String l1Url = "http://test.example.com/l1/page.html";
		String l2Url = "http://test.example.com/l2/page.html";
		putL1(l1Url);
		putL2(l2Url);
		assertEquals(2, cacheProcessor.getCachedKeys().size());

		// removeFromCache scans both tiers by substring filter
		cacheProcessor.removeFromCache(l2Url);
		List<String> keys = cacheProcessor.getCachedKeys();
		assertEquals(1, keys.size());
		// L2 key was stored as path, so the path form must be gone
		assertFalse(keys.contains("/l2/page.html"));
		assertTrue(keys.contains(l1Url));
	}

	// --- forEachCachedKey: the streamed listing dump-keys and get-cached-keys use ---

	private List<String> streamed() throws Exception {
		List<String> keys = new ArrayList<String>();
		long count = cacheProcessor.forEachCachedKey(keys::add);
		assertEquals("the count returned must match the keys handed over", keys.size(), count);
		return keys;
	}

	@Test
	public void forEachCachedKeyStreamsBothTiers() throws Exception {
		String l1Url = "http://test.example.com/l1/page.html";
		putL1(l1Url);
		putL2("http://test.example.com/l2/page.html");

		List<String> keys = streamed();
		assertEquals(2, keys.size());
		assertTrue(keys.contains(l1Url));
		assertTrue(keys.contains("/l2/page.html"));
	}

	/**
	 * An invalidated key is not listed. Through the processor the merge policy reclaims a small
	 * segment's deletes almost at once, so this does NOT prove the live-docs check -
	 * {@link LuceneKeyWalkTest} does, on an index that keeps its deleted terms.
	 */
	@Test
	public void forEachCachedKeySkipsInvalidatedL2Keys() throws Exception {
		putL2("http://test.example.com/l2/a.html");
		putL2("http://test.example.com/l2/b.html");
		putL2("http://test.example.com/l2/c.html");
		assertEquals(3, streamed().size());

		cacheProcessor.removeFromCache("http://test.example.com/l2/b.html");

		assertEquals(Arrays.asList("/l2/a.html", "/l2/c.html"), streamed());
	}

	@Test
	public void aReplacedL2EntryIsListedOnce() throws Exception {
		putL2("http://test.example.com/l2/page.html");
		assertEquals(1, streamed().size());
		putL2("http://test.example.com/l2/page.html"); // updateDocument: old doc deleted, term shared

		assertEquals(Arrays.asList("/l2/page.html"), streamed());
	}

	@Test
	public void l2KeysAreListedInKeyOrder() throws Exception {
		putL2("http://test.example.com/l2/c.html");
		putL2("http://test.example.com/l2/a.html");
		putL2("http://test.example.com/l2/b.html");

		assertEquals(Arrays.asList("/l2/a.html", "/l2/b.html", "/l2/c.html"), streamed());
	}

	/** A sink's failure is the caller's (a dump file's disk filled up), passed on as is - and stops the listing. */
	@Test
	public void aSinkFailureIsPassedOnAndStopsTheListing() throws Exception {
		putL2("http://test.example.com/l2/a.html");
		putL2("http://test.example.com/l2/b.html");

		List<String> seen = new ArrayList<String>();
		IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
				cacheProcessor.forEachCachedKey(key -> {
					seen.add(key);
					throw new IllegalStateException("disk full");
				}));
		assertEquals("disk full", thrown.getMessage());
		assertEquals("no key may be handed over after the sink failed", 1, seen.size());

		// the searcher was released: the index still lists
		assertEquals(2, streamed().size());
	}

	/**
	 * One order across both tiers, so a cursor works across them: L1 keys are slotted between the
	 * streamed L2 keys. An L1 key given as a bare path sorts among the L2 paths.
	 */
	@Test
	public void l1AndL2AreMergedIntoOneOrderAndPagedAcrossTheTiers() throws Exception {
		putL2("http://test.example.com/a.html");
		putL1("/b.html");
		putL2("http://test.example.com/c.html");
		putL1("/d.html");
		putL2("http://test.example.com/e.html");

		assertEquals(Arrays.asList("/a.html", "/b.html", "/c.html", "/d.html", "/e.html"), streamed());

		List<String> page = new ArrayList<String>();
		assertEquals(2, cacheProcessor.forEachCachedKey(null, "/a.html", 2, page::add));
		assertEquals("a page may end on an L1 key", Arrays.asList("/b.html", "/c.html"), page);

		page.clear();
		assertEquals(2, cacheProcessor.forEachCachedKey(null, "/c.html", 2, page::add));
		assertEquals(Arrays.asList("/d.html", "/e.html"), page);

		page.clear();
		assertEquals(1, cacheProcessor.forEachCachedKey("/d", null, 10, page::add));
		assertEquals("the prefix applies to L1 too", Arrays.asList("/d.html"), page);
	}
}
