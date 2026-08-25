/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache.impl;

import static org.junit.Assert.assertFalse;
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

		first.putToCache(urlA, cacheableResponse(urlA));
		// before the fix this write hit "Lock held elsewhere" and was silently dropped
		second.putToCache(urlB, cacheableResponse(urlB));

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
		manager.indexDoc(r);
		assertNotNull("the shared manager must be able to write", manager.getResponse(url));

		manager.close();
	}

	/**
	 * Two managers racing to recover the SAME stale write.lock (the fc-eu production case: the previous
	 * JVM was killed, leaving a real stale lock, and both managers of the duplicated cache processor
	 * entered the heal path within 2ms) must not both "recover". Before the per-path monitor, both
	 * deleted the lock and both opened a writer; the one whose lock file its peer had replaced then
	 * threw AlreadyClosedException("Underlying file changed by an external force") on every operation
	 * for 25 hours.
	 */
	@Test
	public void concurrentHealersOnOneStaleLockNeverInvalidateEachOther() throws Exception {
		final String dir = "/tmp/lucene-concurrent-heal-" + System.currentTimeMillis();

		// an index with a genuinely stale write.lock, as left behind by a hard-killed JVM
		LuceneIndexManager seed = new LuceneIndexManager(dir);
		WebResponse seedResponse = new WebResponse(UUID.randomUUID().toString(), "seed".getBytes());
		seed.indexDoc(seedResponse);
		LuceneIndexTests.leaveStaleWriteLockBehind(seed, dir);

		final int racers = 2;
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(racers);
		final List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
		final List<String> written = java.util.Collections.synchronizedList(new ArrayList<String>());
		final LuceneIndexManager[] managers = new LuceneIndexManager[racers];

		for (int i = 0; i < racers; i++) {
			final int idx = i;
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						LuceneIndexManager m = new LuceneIndexManager(dir);
						managers[idx] = m;
						String url = "http://test.example.com/heal/" + idx + "-" + UUID.randomUUID() + ".html";
						WebResponse r = new WebResponse(url, ("payload " + idx).getBytes());
						m.indexDoc(r);
						if (m.getResponse(url) != null)
							written.add(url);
					} catch (Throwable t) {
						failures.add(t);
					} finally {
						done.countDown();
					}
				}
			}).start();
		}

		start.countDown();
		assertTrue("racers should finish", done.await(60, TimeUnit.SECONDS));

		for (Throwable t : failures)
			assertFalse("no manager may have its lock stolen by the other: " + t,
					causeChainHas(t, org.apache.lucene.store.AlreadyClosedException.class));

		assertFalse("at least one racer must hold a working writer and write", written.isEmpty());

		for (LuceneIndexManager m : managers)
			if (m != null)
				m.close();
	}

	/**
	 * A writer whose write.lock is deleted and recreated by an external force must be discarded and
	 * reopened on a later call instead of being handed back forever by getIndexWriter()'s fast path -
	 * {@code IndexWriter.isOpen()} stays true in that state, which is what made the wedge permanent.
	 */
	@Test
	public void writerWithExternallyReplacedLockRecoversOnNextCall() throws Exception {
		String dir = "/tmp/lucene-lock-stolen-" + System.currentTimeMillis();
		LuceneIndexManager manager = new LuceneIndexManager(dir);

		String before = "http://test.example.com/stolen/before-" + UUID.randomUUID() + ".html";
		WebResponse r1 = new WebResponse(before, "before".getBytes());
		manager.indexDoc(r1);
		assertNotNull("baseline write should work", manager.getResponse(before));

		// External force: same path, different file - exactly what a peer manager's stale-lock heal did.
		// SimpleFSLock detects the swap by comparing the file's creationTime, whose granularity is as
		// coarse as 1s on some filesystems; recreating within the same second is indistinguishable and
		// would make this test pass vacuously, so replace until the timestamp actually differs.
		java.nio.file.Path lockFile = java.nio.file.Paths.get(dir)
				.resolve(org.apache.lucene.index.IndexWriter.WRITE_LOCK_NAME);
		java.nio.file.attribute.FileTime original = java.nio.file.Files
				.readAttributes(lockFile, java.nio.file.attribute.BasicFileAttributes.class).creationTime();
		java.nio.file.attribute.FileTime replaced = original;
		long deadline = System.currentTimeMillis() + 10000;
		while (replaced.equals(original) && System.currentTimeMillis() < deadline) {
			java.nio.file.Files.deleteIfExists(lockFile);
			Thread.sleep(100);
			java.nio.file.Files.createFile(lockFile);
			replaced = java.nio.file.Files
					.readAttributes(lockFile, java.nio.file.attribute.BasicFileAttributes.class).creationTime();
		}
		assertFalse("precondition: the replacement write.lock must be distinguishable from the original",
				replaced.equals(original));

		// A DELETE, not an add: a delete against an already-committed segment writes liveDocs from
		// IndexWriter.writeReaderPool(), which Lucene does not treat as a tragic event - so the writer is
		// left reporting isOpen()==true with a dead lock. That asymmetry is exactly why production wedged
		// on the soft-invalidation path while page writes kept working, and why isOpen() cannot be the
		// liveness check.
		manager.delete(before);

		// Which operation DISCOVERS the invalid lock is Lucene's business, not ours: it surfaces the
		// next time the writer actually touches the lock, and a buffered delete may not do so until the
		// following flush. (It used to be the delete's own commit(), back when every write committed.)
		// So the contract this test pins is the one discardWriterIfLockLost() states - "the NEXT L2
		// operation will reopen it" - and the way to assert it is to run two writes and require the
		// index to end up durable. A writer that is never discarded, or never successfully reopened,
		// fails both and commits neither, which is the wedge.
		String after = "http://test.example.com/stolen/after-" + UUID.randomUUID() + ".html";
		manager.indexDoc(new WebResponse(after, "after".getBytes())); // may be the discovering operation
		manager.indexDoc(new WebResponse(after, "after".getBytes())); // must land on the reopened writer

		// Checked against a fresh on-disk reader, NOT manager.getResponse(): reads go through a
		// near-real-time searcher over the IndexWriter, so they surface in-memory segments that a dead
		// lock prevented from ever being committed - which would make a wedged writer look healthy.
		// The commit is explicit because writes no longer commit themselves (LuceneIndexManager.Tuning);
		// it is still the discriminator, since a writer with an invalidated lock cannot commit at all.
		manager.commit();
		assertTrue("L2 must self-heal after its lock was replaced externally and durably commit",
				committedOnDisk(dir, after));

		manager.close();
	}

	/**
	 * True if {@code url} is present in the index as committed on disk, read through an independent
	 * reader (no IndexWriter involved, so nothing uncommitted is visible).
	 */
	private static boolean committedOnDisk(String dir, String url) throws Exception {
		try (org.apache.lucene.store.Directory d =
					org.apache.lucene.store.FSDirectory.open(java.nio.file.Paths.get(dir));
				org.apache.lucene.index.IndexReader reader =
						org.apache.lucene.index.DirectoryReader.open(d)) {
			org.apache.lucene.search.IndexSearcher searcher = new org.apache.lucene.search.IndexSearcher(reader);
			org.apache.lucene.index.Term term = new org.apache.lucene.index.Term(
					LuceneIndexManager.URL_FIELD, LuceneIndexManager.toPathKey(url));
			return searcher.count(new org.apache.lucene.search.TermQuery(term)) > 0;
		}
	}

	private static boolean causeChainHas(Throwable t, Class<? extends Throwable> type) {
		for (Throwable c = t; c != null; c = c.getCause())
			if (type.isInstance(c))
				return true;
		return false;
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
