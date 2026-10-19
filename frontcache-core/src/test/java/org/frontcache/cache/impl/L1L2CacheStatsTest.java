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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.frontcache.cache.CacheStats;
import org.frontcache.cache.FcCacheMetrics;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The numbers behind the exported cache metrics, against a real L1 (Ehcache) and L2 (Lucene).
 *
 * {@code FcCacheMetricsExportTest} covers the export plumbing with no cache present; this covers the
 * other half - that the values are real, that the tier attribution is right, and that reading them is
 * cheap enough to do on every scrape.
 */
public class L1L2CacheStatsTest {

	private L1L2CacheProcessor cacheProcessor;

	@Before
	public void setUp() {
		FcCacheMetrics.reset();

		cacheProcessor = new L1L2CacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.cache-dir",
				"/tmp/l1l2-cachestats-test-" + System.currentTimeMillis() + "/");
		cacheProcessor.init(props);
	}

	@After
	public void tearDown() {
		cacheProcessor.destroy();
		FcCacheMetrics.reset();
	}

	private WebResponse cacheable(String url) {
		WebResponse response = new WebResponse(url, ("content of " + url).getBytes(), "30d", null);
		response.setStatusCode(200);
		return response;
	}

	/** A response tagged for the in-memory tier, with a body of a known size. */
	private WebResponse cacheableInL1(String url, int contentBytes) {
		WebResponse response = new WebResponse(url, new byte[contentBytes], "30d", null);
		response.setStatusCode(200);
		response.setCacheLevel(FCHeaders.CACHE_LEVEL_L1);
		return response;
	}

	/** Both tiers report real numbers, and the on-disk tier reports real bytes. */
	@Test
	public void sizesAreReadFromBothTiers()
	{
		CacheStats before = cacheProcessor.getCacheStats();
		assertTrue("L1 entries must be tracked", before.isTracked(before.getL1Entries()));
		assertTrue("L2 entries must be tracked", before.isTracked(before.getL2Entries()));
		assertTrue("on-disk bytes must be tracked", before.isTracked(before.getL2DiskBytes()));
		assertTrue("evictions must be tracked", before.isTracked(before.getL1Evictions()));

		cacheProcessor.putToCache("http://example.com/a.htm", cacheable("http://example.com/a.htm"));
		cacheProcessor.putToCache("http://example.com/b.htm", cacheable("http://example.com/b.htm"));

		CacheStats after = cacheProcessor.getCacheStats();

		assertEquals("both entries went to the default (L2) tier", 2, after.getL2Entries());
		assertTrue("an index holding two documents occupies bytes: " + after.getL2DiskBytes(),
				after.getL2DiskBytes() > 0);
	}

	/**
	 * The tier attribution, which is the whole point of splitting the counter: a read served from disk
	 * must not look like a memory hit, or "hit ratio is fine" hides an undersized L1.
	 */
	@Test
	public void hitsAreAttributedToTheTierThatServedThem()
	{
		String url = "http://example.com/a.htm";
		cacheProcessor.putToCache(url, cacheable(url));
		FcCacheMetrics.reset();

		assertNotNull("should be served from L2", cacheProcessor.getFromCacheImpl(url));

		assertEquals("served from the Lucene index", 1, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L2));
		assertEquals(0, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L1));
		assertEquals(0, FcCacheMetrics.count(FcCacheMetrics.Outcome.MISS));
	}

	@Test
	public void aMissIsCountedOnceAndAsAMiss()
	{
		assertEquals(null, cacheProcessor.getFromCacheImpl("http://example.com/never-cached.htm"));

		assertEquals("a miss checks L1 and then L2 - but it is ONE cache read, not two",
				1, FcCacheMetrics.count(FcCacheMetrics.Outcome.MISS));
		assertEquals(0, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L1));
		assertEquals(0, FcCacheMetrics.count(FcCacheMetrics.Outcome.HIT_L2));
	}

	/**
	 * The reason {@code getIndexSizeFast()} exists: it reads the writer's own doc stats, where
	 * {@code getIndexSize()} goes through the shared near-real-time searcher (and, before that, opened
	 * a fresh FSDirectory and DirectoryReader per call). This pins that the cheap path is actually used
	 * and agrees with the reader-based one.
	 */
	@Test
	public void theCheapDocCountAgreesWithTheExpensiveOne()
	{
		for (int i = 0; i < 5; i++)
			cacheProcessor.putToCache("http://example.com/" + i + ".htm", cacheable("http://example.com/" + i + ".htm"));

		LuceneIndexManager index = cacheProcessor.luceneIndexManager;

		assertEquals("the cheap doc count and the reader-opening one must agree",
				index.getIndexSize(), index.getIndexSizeFast());
		assertEquals(5, index.getIndexSizeFast());
	}

	/**
	 * The L1 byte counter, end to end: through the processor's own put path and out through
	 * {@link CacheStats}, which is how the metrics exporter and the console will read it.
	 * {@code L1ByteCounterTest} and {@code L1ByteCounterEvictionTest} cover the counter's arithmetic;
	 * this covers that it is wired to the cache the processor actually uses.
	 */
	@Test
	public void l1ContentBytesFollowsWhatTheMemoryTierHolds()
	{
		CacheStats empty = cacheProcessor.getCacheStats();
		assertTrue("L1 content bytes must be tracked", empty.isTracked(empty.getL1ContentBytes()));
		assertEquals("nothing in L1 yet", 0, empty.getL1ContentBytes());

		cacheProcessor.putToCache("http://example.com/l1-a.htm", cacheableInL1("http://example.com/l1-a.htm", 1024));
		cacheProcessor.putToCache("http://example.com/l1-b.htm", cacheableInL1("http://example.com/l1-b.htm", 2048));

		CacheStats after = cacheProcessor.getCacheStats();
		assertEquals("both went to the memory tier", 2, after.getL1Entries());
		assertEquals("and their bodies are what the byte figure counts", 3072, after.getL1ContentBytes());
	}

	/** L2 is the default tier, and it is on disk - it must not show up in L1's memory figure. */
	@Test
	public void l2EntriesDoNotCountTowardsL1ContentBytes()
	{
		cacheProcessor.putToCache("http://example.com/a.htm", cacheable("http://example.com/a.htm"));

		CacheStats stats = cacheProcessor.getCacheStats();

		assertEquals("the entry went to L2", 1, stats.getL2Entries());
		assertEquals("so L1 holds no bytes", 0, stats.getL1ContentBytes());
		assertTrue("while the on-disk tier does", stats.getL2DiskBytes() > 0);
	}

	/** A gauge must never throw, whatever state the index is in - a scrape is not a request path. */
	@Test
	public void statsSurviveADestroyedProcessor()
	{
		cacheProcessor.destroy();

		CacheStats stats = cacheProcessor.getCacheStats();

		assertNotNull("a closed cache must still answer, with UNKNOWNs if need be", stats);
	}

}
