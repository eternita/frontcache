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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.frontcache.cache.CacheStats;
import org.frontcache.cache.FcCacheMetrics;
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
	 * The reason {@code getIndexSizeFast()} exists. {@code getIndexSize()} opens a fresh FSDirectory and
	 * DirectoryReader per call - acceptable for a console page, not for a scrape every few seconds
	 * forever. This pins that the cheap path is actually used and agrees with the expensive one.
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

	/** A gauge must never throw, whatever state the index is in - a scrape is not a request path. */
	@Test
	public void statsSurviveADestroyedProcessor()
	{
		cacheProcessor.destroy();

		CacheStats stats = cacheProcessor.getCacheStats();

		assertNotNull("a closed cache must still answer, with UNKNOWNs if need be", stats);
	}

}
