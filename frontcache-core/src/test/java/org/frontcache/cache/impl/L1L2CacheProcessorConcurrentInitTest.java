/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Test;

/**
 * Regression tests for the production wedge where L2 went permanently read/write-dead with
 * "LockObtainFailedException: Lock held elsewhere: .../write.lock" on every request.
 * <p>
 * Cause: {@code CacheManager.getInstance()} was an unsynchronized lazy singleton, so two Jetty
 * threads racing the first requests each built an {@link L1L2CacheProcessor}, and each of those
 * built its own {@link LuceneIndexManager} on the same L2 index directory. One won {@code
 * write.lock}; the other was left with a permanently null IndexWriter - and since a live writer in
 * this JVM owned the lock, the stale-lock self-heal correctly refused to clear it, so the loser
 * could never recover. Whichever engine happened to hold the loser served every request with L2
 * dead (reads included, because readers are opened from the writer).
 * <p>
 * These tests pin the invariant that makes that unrecoverable state impossible: one IndexWriter per
 * index directory, no matter how many cache processors or threads ask for one.
 */
public class L1L2CacheProcessorConcurrentInitTest {

	private static final String DOMAIN = "test-domain";

	private final List<CacheProcessor> processors = new ArrayList<CacheProcessor>();

	@After
	public void tearDown() {
		for (CacheProcessor p : processors) {
			try {
				p.destroy();
			} catch (Exception ignore) {
			}
		}
	}

	private CacheProcessor newProcessor(String cacheDir) {
		CacheProcessor p = new L1L2CacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.cache-dir", cacheDir);
		p.init(props);
		processors.add(p);
		return p;
	}

	private WebResponse cacheableResponse(String url) {
		WebResponse r = new WebResponse(url, ("content of " + url).getBytes());
		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, System.currentTimeMillis() + 600000);
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER, System.currentTimeMillis() + 600000);
		r.setExpireTimeMap(expireTimeMap);
		r.setStatusCode(200);
		r.setCacheLevel(null); // null -> L2 (Lucene)
		return r;
	}

	/**
	 * The prod scenario: two cache processors end up initialized against one FRONTCACHE_HOME. Neither
	 * may be left writer-less - both must be able to write to and read from L2.
	 */
	@Test
	public void twoProcessorsOnOneCacheDirBothUseL2() {
		String cacheDir = "/tmp/l1l2-dup-init-test-" + System.currentTimeMillis() + "/";

		CacheProcessor first = newProcessor(cacheDir);
		CacheProcessor second = newProcessor(cacheDir);

		String urlA = "http://test.example.com/dup/a-" + UUID.randomUUID() + ".html";
		String urlB = "http://test.example.com/dup/b-" + UUID.randomUUID() + ".html";

		first.putToCache(DOMAIN, urlA, cacheableResponse(urlA));
		// before the fix this write hit "Lock held elsewhere" and was silently dropped
		second.putToCache(DOMAIN, urlB, cacheableResponse(urlB));

		assertNotNull("first processor should read back its own L2 entry", first.getFromCache(urlA, null));
		assertNotNull("second processor must be able to write to L2 (was lock-starved)",
				second.getFromCache(urlB, null));
		// one shared index - so either processor sees the other's entry
		assertNotNull("second processor should see the first processor's L2 entry",
				second.getFromCache(urlA, null));
	}

	/**
	 * Concurrent first-use, which is how the race actually fired in production: many threads asking
	 * for a manager on one directory must all get the same instance, and it must be usable.
	 */
	@Test
	public void concurrentGetInstanceYieldsOneUsableManager() throws Exception {
		final String dir = "/tmp/lucene-concurrent-init-" + System.currentTimeMillis();
		final int threads = 8;
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);
		final LuceneIndexManager[] obtained = new LuceneIndexManager[threads];

		for (int i = 0; i < threads; i++) {
			final int idx = i;
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						obtained[idx] = LuceneIndexManager.getInstance(dir,
								LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB,
								LuceneIndexManager.DEFAULT_DELETES_PCT_ALLOWED);
					} catch (Exception e) {
						// leaves obtained[idx] null -> assertion below fails
					} finally {
						done.countDown();
					}
				}
			}).start();
		}

		start.countDown();
		assertTrue("threads should finish obtaining a manager", done.await(30, TimeUnit.SECONDS));

		LuceneIndexManager manager = obtained[0];
		assertNotNull("every thread should get a manager", manager);
		for (int i = 1; i < threads; i++)
			assertSame("all threads must share one manager per index dir", manager, obtained[i]);

		// and the single manager actually works - i.e. it holds the write lock
		String url = "http://test.example.com/concurrent/" + UUID.randomUUID() + ".html";
		WebResponse r = new WebResponse(url, "payload".getBytes());
		r.setDomain(DOMAIN);
		manager.indexDoc(r);
		assertNotNull("the shared manager must be able to write", manager.getResponse(url));

		manager.close();
	}

	/**
	 * The registry is keyed by normalised path, so the same directory spelled differently (trailing
	 * slash, redundant segments) resolves to one manager rather than two writers on one index.
	 */
	@Test
	public void differentlySpelledPathsResolveToOneManager() throws Exception {
		String dir = "/tmp/lucene-path-normalisation-" + System.currentTimeMillis();

		LuceneIndexManager plain = LuceneIndexManager.getInstance(dir,
				LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB, LuceneIndexManager.DEFAULT_DELETES_PCT_ALLOWED);
		LuceneIndexManager trailingSlash = LuceneIndexManager.getInstance(dir + "/",
				LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB, LuceneIndexManager.DEFAULT_DELETES_PCT_ALLOWED);
		LuceneIndexManager withDotSegment = LuceneIndexManager.getInstance(dir + "/./",
				LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB, LuceneIndexManager.DEFAULT_DELETES_PCT_ALLOWED);

		assertSame(plain, trailingSlash);
		assertSame(plain, withDotSegment);

		plain.close();
	}
}
