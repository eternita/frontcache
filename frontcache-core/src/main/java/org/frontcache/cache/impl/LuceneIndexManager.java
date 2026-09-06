/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache.impl;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.apache.lucene.util.BytesRef;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Provides access to lucene index.
 *
 */
public class LuceneIndexManager {

	private static final Logger logger = LoggerFactory.getLogger(LuceneIndexManager.class);

	// The index directory this manager owns. Instance state, NOT static: as a static field every new
	// manager clobbered the path of all existing ones, so a second manager (on any dir) corrupted the
	// LIVE_WRITER_PATHS bookkeeping and the lock/error messages of the first.
	private final String indexPath;

	// One manager per index directory, process-wide. Two IndexWriters cannot share a directory, so a
	// second manager on the same dir is always a bug: one wins write.lock and the other is left
	// permanently writer-less ("Lock held elsewhere" on every request, L2 dead until restart - the
	// production wedge this registry prevents). Production goes through getInstance(); the
	// constructor stays package-private so tests can still simulate a crashed-and-restarted JVM.
	private static final java.util.concurrent.ConcurrentMap<String, LuceneIndexManager> INSTANCES =
			new java.util.concurrent.ConcurrentHashMap<>();

	// One monitor per index directory, guarding the whole "open writer / detect stale lock / delete it /
	// retry" sequence in getIndexWriter(). Keyed by path rather than per manager because the thing that
	// must not interleave is two managers healing the same directory.
	private static final java.util.concurrent.ConcurrentMap<String, Object> WRITER_MONITORS =
			new java.util.concurrent.ConcurrentHashMap<>();

	private static Object writerMonitorFor(String path) {
		return WRITER_MONITORS.computeIfAbsent(path, p -> new Object());
	}

	public static final String JSON_FIELD = "json";
	public static final String BIN_FIELD = "bin";

	// searchable fields
	public static final String TAGS_FIELD = "tags"; // for invalidation
	public static final String URL_FIELD = "url";
	public static final String EXPIRE_DATE_FIELD = "expire_date";

	private IndexWriter indexWriter = null;

	/**
	 * The one near-real-time view every read goes through, shared by all reader threads.
	 * <p>
	 * Before this, {@link #getDocByURL} did {@code DirectoryReader.open(indexWriter)} per cache read.
	 * That is {@code IndexWriter.getReader()}, so a READ took the writer's full-flush lock and forced a
	 * WRITE, then built {@code SegmentReader}s for the whole segment set, used them for one one-hit
	 * {@code TermQuery} and threw them away - and two concurrent L2 reads serialized on that lock.
	 * {@link SearcherManager#acquire()} is a volatile read plus a refcount bump; the reopen happens once
	 * per refresh for everyone, and is incremental (existing SegmentReaders are reused).
	 * <p>
	 * Bound to one {@link IndexWriter}, so it is torn down and rebuilt wherever the writer is - see
	 * {@link #closeSearcherManagerQuietly()}. Rebuilt lazily by {@link #acquireSearcher()} rather than
	 * eagerly on writer open, so a failure to build one costs L2 reads until the next attempt instead
	 * of permanently.
	 */
	private volatile SearcherManager searcherManager = null;

	/** Periodic commit and periodic refresh. One daemon thread per manager; stopped by {@link #close()}. */
	private volatile ScheduledExecutorService maintenance = null;

	// See Tuning.
	private final long commitIntervalMs;
	private final boolean refreshOnWrite;
	private final long refreshIntervalMs;

	// The index directory is opened once and reused for the lifetime of this manager.
	// Reopening a fresh FSDirectory on every writer reopen (the previous behaviour) leaked
	// the NativeFSLock held by a broken-but-not-closed writer: the stale lock stayed in the
	// JVM's static lock set, so every subsequent "new IndexWriter(freshDir)" failed forever
	// with "LockObtainFailedException: Lock held by this virtual machine". Reusing one
	// Directory + always closing the stale writer before reopening keeps the lock lifecycle clean.
	private Directory directory = null;

	// Index paths that currently have a live IndexWriter in THIS JVM. A stale write.lock is safe to
	// auto-clear on reopen only when its path is absent here - i.e. no live writer owns it (see
	// getIndexWriter). Static because the guard is against two managers in one process on one dir.
	private static final Set<String> LIVE_WRITER_PATHS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	// Snapshot deletion policy of the current writer. Lets backup() pin the files of a commit so
	// a concurrent merge can't delete them mid-copy - the Lucene-canonical hot-backup mechanism.
	// Rebuilt together with the writer config on every (re)open (see buildIndexWriterConfig).
	private SnapshotDeletionPolicy snapshotDeletionPolicy = null;

	// throttle the "writer wedged" ERROR so a permanently-broken writer can't flood the log
	// (previously this produced a full stack trace on every request - GBs/day).
	private volatile long lastReopenFailureLogMillis = 0;
	private static final long REOPEN_FAILURE_LOG_INTERVAL_MILLIS = 60_000;

	// Merge-policy tuning for a cache-style index (see buildIndexWriterConfig).
	// Cap on the largest merged segment. Lucene's default is 5 GB; once a segment reaches
	// that size it stops being merged, so its deleted docs are never reclaimed (prod grew a
	// single 5.3 GB segment). A smaller cap keeps segments merge-eligible so churn is reclaimed.
	static final double DEFAULT_MAX_MERGED_SEGMENT_MB = 512.0;
	// % of the index allowed to be deleted docs before merges reclaim them. Lucene default 33;
	// 20 (the minimum) reclaims sooner, which suits the heavy updateDocument()/delete churn here.
	static final double DEFAULT_DELETES_PCT_ALLOWED = 20.0;

	private final double maxMergedSegmentMB;
	private final double deletesPctAllowed;

	/**
	 * Everything about this index that an operator can tune, in one object so adding a knob does not
	 * add a positional constructor argument.
	 *
	 * <h3>Why commits are no longer per write</h3>
	 *
	 * {@code indexDoc()} used to end in {@code IndexWriter.commit()} - a flush plus an <b>fsync</b> of
	 * every file in the commit - so every cache miss paid one fsync, and a regular (non-soft) expiry
	 * paid two (the {@code removeFromCache} delete commits, then the refreshed entry commits). Commit
	 * is a checkpoint operation; it belongs on a timer.
	 *
	 * <b>The durability trade is deliberate.</b> An ungraceful kill now loses up to
	 * {@code commitIntervalMs} of cache <i>writes</i> instead of at most the one in flight. For a cache
	 * that is not a correctness event - the lost entries are simply re-fetched from origin - and
	 * {@link LuceneIndexManager#close()} commits on ordinary shutdown. Set the interval to 0 to get the
	 * old commit-per-write behaviour back.
	 *
	 * <h3>Why refresh-on-write defaults to true</h3>
	 *
	 * A cache entry must be visible to the very next request for that URL, or a popular page stampedes
	 * the origin for the whole refresh window. So a write refreshes the searcher itself, and the
	 * background refresh is only the safety net for the race where two writers overlap (the second
	 * {@code maybeRefresh()} returns immediately while the first is still refreshing, so the second
	 * write can miss that reopen). Turning it off batches writes into fewer, larger segments - better
	 * for the merge policy - at the cost of up to {@code refreshIntervalMs} of visibility lag.
	 *
	 * <p>Note that refresh is much cheaper than the commit it replaced: an NRT reopen flushes, but does
	 * not fsync and does not write a new segments file.
	 */
	public static final class Tuning {

		/** Commit cadence. {@code <= 0} restores the pre-2.9.0 commit-after-every-write behaviour. */
		static final long DEFAULT_COMMIT_INTERVAL_MS = 10_000;

		/** Safety-net refresh cadence. {@code <= 0} disables the background refresher. */
		static final long DEFAULT_REFRESH_INTERVAL_MS = 1_000;

		final double maxMergedSegmentMB;
		final double deletesPctAllowed;
		final long commitIntervalMs;
		final boolean refreshOnWrite;
		final long refreshIntervalMs;

		public Tuning(double maxMergedSegmentMB, double deletesPctAllowed,
				long commitIntervalMs, boolean refreshOnWrite, long refreshIntervalMs) {
			this.maxMergedSegmentMB = maxMergedSegmentMB;
			this.deletesPctAllowed = deletesPctAllowed;
			this.commitIntervalMs = commitIntervalMs;
			this.refreshOnWrite = refreshOnWrite;
			this.refreshIntervalMs = refreshIntervalMs;
		}

		public static Tuning defaults() {
			return new Tuning(DEFAULT_MAX_MERGED_SEGMENT_MB, DEFAULT_DELETES_PCT_ALLOWED,
					DEFAULT_COMMIT_INTERVAL_MS, true, DEFAULT_REFRESH_INTERVAL_MS);
		}

		/** Merge tuning only - everything else default. Keeps the pre-existing 3-arg entry points working. */
		static Tuning ofMergeTuning(double maxMergedSegmentMB, double deletesPctAllowed) {
			return new Tuning(maxMergedSegmentMB, deletesPctAllowed,
					DEFAULT_COMMIT_INTERVAL_MS, true, DEFAULT_REFRESH_INTERVAL_MS);
		}

		@Override
		public String toString() {
			return "maxMergedSegmentMB=" + maxMergedSegmentMB
					+ ", deletesPctAllowed=" + deletesPctAllowed
					+ ", commitIntervalMs=" + commitIntervalMs
					+ ", refreshOnWrite=" + refreshOnWrite
					+ ", refreshIntervalMs=" + refreshIntervalMs;
		}
	}

	public final static FieldType JSON_TYPE;
	static {
	    JSON_TYPE = new FieldType();
	    JSON_TYPE.setStored(true);
	    JSON_TYPE.setIndexOptions(IndexOptions.NONE);
	    JSON_TYPE.setTokenized(false);
	    JSON_TYPE.freeze();
	}

	/**
	 * Strips scheme, host, and port from a full URL, returning only the path (and query string, if any).
	 * Used to normalise cache keys so that the same resource reached via different hostnames or ports
	 * maps to a single index entry. Non-URL strings (UUIDs, plain paths, tags) are returned unchanged.
	 *
	 * <pre>
	 *   https://www.example.com:443/en/welcome.htm  →  /en/welcome.htm
	 *   http://host/a/b?q=1                          →  /a/b?q=1
	 *   /en/welcome.htm                              →  /en/welcome.htm
	 *   some-uuid-string                             →  some-uuid-string
	 * </pre>
	 */
	static String toPathKey(String url) {
		if (url == null)
			return null;
		int schemeIdx = url.indexOf("://");
		if (schemeIdx < 0)
			return url; // not a full URL - return as-is
		int pathStart = url.indexOf('/', schemeIdx + 3);
		if (pathStart < 0)
			return "/";
		return url.substring(pathStart);
	}

	/**
	 *
	 */
	final Gson gson = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {

		@Override
		public boolean shouldSkipField(FieldAttributes att) {
			return false;
		}

		@Override
		public boolean shouldSkipClass(Class<?> arg0) {
			return byte[].class.equals(arg0);
		}
	}).create();


	/**
	 * Returns the one manager for {@code indexPath}, creating it on first use.
	 * <p>
	 * This is how production code must obtain a manager. Only one {@link IndexWriter} can hold an
	 * index directory, so sharing a single manager per directory is what keeps a second, permanently
	 * broken writer from ever existing - see {@link #INSTANCES}.
	 *
	 * @param indexPath index directory (any form; normalised internally)
	 * @param maxMergedSegmentMB cap on largest merged segment (MB); &lt;=0 -&gt; default
	 * @param deletesPctAllowed % deleted docs tolerated before reclaim; clamped to Lucene's [20,50]
	 */
	public static LuceneIndexManager getInstance(String indexPath, double maxMergedSegmentMB, double deletesPctAllowed) {
		return getInstance(indexPath, Tuning.ofMergeTuning(maxMergedSegmentMB, deletesPctAllowed));
	}

	/**
	 * Returns the one manager for {@code indexPath}, creating it with {@code tuning} on first use.
	 *
	 * @param indexPath index directory (any form; normalised internally)
	 * @param tuning merge, commit and refresh settings - see {@link Tuning}
	 */
	public static LuceneIndexManager getInstance(String indexPath, Tuning tuning) {
		String key = normalizePath(indexPath);
		LuceneIndexManager existing = INSTANCES.get(key);
		if (null != existing) {
			if (existing.maxMergedSegmentMB != validateMaxMergedSegmentMB(tuning.maxMergedSegmentMB)
					|| existing.deletesPctAllowed != validateDeletesPctAllowed(tuning.deletesPctAllowed)
					|| existing.commitIntervalMs != tuning.commitIntervalMs
					|| existing.refreshOnWrite != tuning.refreshOnWrite
					|| existing.refreshIntervalMs != tuning.refreshIntervalMs)
				logger.warn("L2 index {} is already open with different tuning ({}) - reusing the existing "
						+ "manager; restart to apply new tuning.", key, existing.describeTuning());
			return existing;
		}

		// computeIfAbsent so two threads racing the first request can't each build a manager
		return INSTANCES.computeIfAbsent(key, p -> new LuceneIndexManager(p, tuning));
	}

	private String describeTuning() {
		return "maxMergedSegmentMB=" + maxMergedSegmentMB
				+ ", deletesPctAllowed=" + deletesPctAllowed
				+ ", commitIntervalMs=" + commitIntervalMs
				+ ", refreshOnWrite=" + refreshOnWrite
				+ ", refreshIntervalMs=" + refreshIntervalMs;
	}

	/**
	 * Normalises an index path so the same directory maps to one registry key / one lock-bookkeeping
	 * entry regardless of how it was spelled (relative, trailing slash, {@code ..} segments).
	 */
	static String normalizePath(String indexPath) {
		return Paths.get(indexPath).toAbsolutePath().normalize().toString();
	}

	/**
	 * Constructor - uses default merge tuning.
	 * @param indexPath
	 */
	LuceneIndexManager(String indexPath) {
		this(indexPath, Tuning.defaults());
	}

	/**
	 * Constructor - merge tuning only, everything else default.
	 * @param indexPath index directory
	 * @param maxMergedSegmentMB cap on largest merged segment (MB); &lt;=0 -&gt; default
	 * @param deletesPctAllowed % deleted docs tolerated before reclaim; clamped to Lucene's [20,50]
	 */
	LuceneIndexManager(String indexPath, double maxMergedSegmentMB, double deletesPctAllowed) {
		this(indexPath, Tuning.ofMergeTuning(maxMergedSegmentMB, deletesPctAllowed));
	}

	/**
	 * Constructor
	 * @param indexPath index directory
	 * @param tuning merge, commit and refresh settings - see {@link Tuning}
	 */
	LuceneIndexManager(String indexPath, Tuning tuning) {
		this.maxMergedSegmentMB = validateMaxMergedSegmentMB(tuning.maxMergedSegmentMB);
		this.deletesPctAllowed = validateDeletesPctAllowed(tuning.deletesPctAllowed);
		this.commitIntervalMs = tuning.commitIntervalMs;
		this.refreshOnWrite = tuning.refreshOnWrite;
		this.refreshIntervalMs = tuning.refreshIntervalMs;

		this.indexPath = normalizePath(indexPath);
		Path path = Paths.get(this.indexPath);
		if (!Files.exists(path)) {
			try {
				if (path.getParent().toFile().isDirectory() && !path.getParent().toFile().exists())
					Files.createDirectories(path.getParent());

				// create dummy index
				{
					String url = UUID.randomUUID().toString();
					WebResponse dummy =  new WebResponse(url);
					indexDoc(dummy);
					delete(url);
					// Commit explicitly: writes no longer commit themselves, and this path exists to
					// leave a valid, readable index on disk for whoever opens the directory next.
					commitQuietly(indexWriter, "create empty index");
				}
			} catch (IOException e) {
				logger.error("Error during creating cache-file", e);
			}
		} else {
			try {
				indexWriter = getIndexWriter();
			} catch (Exception e) {
				// if other instance holds lock on index - lets try to get lock during runtime
				logger.error("Error during creating indexWriter " + e.getMessage());
			}
		}

		startMaintenance();
	}

	/**
	 * Starts the periodic commit and the safety-net refresh. One daemon thread does both: they are
	 * seconds apart and never contend, so a second thread would only add a stack.
	 */
	private void startMaintenance() {

		if (0 >= commitIntervalMs && 0 >= refreshIntervalMs)
			return; // both disabled - commit-per-write and refresh-on-write carry the whole job

		ScheduledExecutorService local = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "LuceneIndexManager.maintenance");
				t.setDaemon(true); // must never hold up JVM shutdown
				return t;
			}
		});

		if (0 < commitIntervalMs)
			local.scheduleWithFixedDelay(this::commitPending, commitIntervalMs, commitIntervalMs, TimeUnit.MILLISECONDS);

		if (0 < refreshIntervalMs)
			local.scheduleWithFixedDelay(this::refreshSearcher, refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);

		maintenance = local;

		logger.info("L2 index {} - {}", indexPath, describeTuning());
	}

	/**
	 * The scheduled commit. Never throws: it runs on a scheduled task, and an escaping exception would
	 * cancel that task for the life of the JVM - leaving the index uncommitted forever.
	 */
	private void commitPending() {
		try {
			IndexWriter iWriter = indexWriter; // no getIndexWriter(): a timer must not open an index nobody is using
			if (null == iWriter || !iWriter.isOpen() || !iWriter.hasUncommittedChanges())
				return;

			commitQuietly(iWriter, "scheduled commit");

		} catch (Throwable t) {
			logger.warn("Scheduled L2 commit failed: {}", t.toString());
		}
	}

	/**
	 * Commits pending changes now, instead of waiting for the scheduled commit.
	 * <p>
	 * Writes on the request path deliberately do not commit (see {@link Tuning}), which is the right
	 * trade for a cache: a lost write is re-fetched from origin. It is the wrong trade for an
	 * operator-initiated invalidation, where an entry re-appearing after a crash is a surprise rather
	 * than a warm-up - so the management-API paths that empty or purge the cache call this.
	 * <p>
	 * Never throws: a wedged writer is discarded here exactly as it is anywhere else, and the caller's
	 * own operation has already succeeded in memory.
	 */
	public void commit() {
		IndexWriter iWriter = indexWriter;
		if (null == iWriter || !iWriter.isOpen())
			return;
		commitQuietly(iWriter, "explicit commit");
	}

	/**
	 * Test seam: runs one non-blocking refresh, exactly as the scheduled refresher does, so a test can
	 * contend for the refresh lock the way that thread does.
	 */
	void forceRefreshForTest() throws IOException {
		SearcherManager mgr = searcherManager;
		if (null != mgr)
			mgr.maybeRefresh();
	}

	/**
	 * The safety-net refresh - see {@link Tuning}. Never throws, for the same reason as
	 * {@link #commitPending()}.
	 */
	private void refreshSearcher() {
		try {
			SearcherManager mgr = searcherManager; // no lazy create: nothing has read this index yet
			if (null != mgr)
				mgr.maybeRefresh();

		} catch (AlreadyClosedException ace) {
			// the writer went away under us; the next read reopens both writer and searcher
			logger.debug("Scheduled L2 refresh skipped - searcher closed: {}", ace.toString());
		} catch (Throwable t) {
			logger.warn("Scheduled L2 refresh failed: {}", t.toString());
		}
	}

	private static double validateMaxMergedSegmentMB(double maxMergedSegmentMB) {
		return (maxMergedSegmentMB > 0) ? maxMergedSegmentMB : DEFAULT_MAX_MERGED_SEGMENT_MB;
	}

	private static double validateDeletesPctAllowed(double deletesPctAllowed) {
		// TieredMergePolicy.setDeletesPctAllowed requires 20 <= value <= 50
		return Math.min(50.0, Math.max(20.0, deletesPctAllowed));
	}

	// package-private accessors for the effective (validated/clamped) merge-tuning values - for tests
	double getMaxMergedSegmentMB() {
		return maxMergedSegmentMB;
	}

	double getDeletesPctAllowed() {
		return deletesPctAllowed;
	}

	/**
	 * Returns instance of IndexManager
	 * @return
	 * @throws IOException
	 */
	private IndexWriter getIndexWriter() throws IOException {
		// fast path - reuse the live writer without locking
		if (indexWriter != null && indexWriter.isOpen())
			return indexWriter;

		// Acquisition is serialized per index directory, not just per manager: the stale-lock recovery
		// below deletes write.lock, and two managers doing that at the same moment both "recover" and
		// end up with a live writer on one index (prod, fc-eu 2026-08-07 14:16:16 - the loser's lock
		// file was deleted and recreated by its peer, so every later op threw AlreadyClosedException
		// "Underlying file changed by an external force" for 25h). One monitor per path makes the
		// acquire-test-heal-retry sequence atomic against any other manager on the same directory.
		synchronized (writerMonitorFor(indexPath)) {
			if (indexWriter != null && indexWriter.isOpen())
				return indexWriter;

			// Close the broken writer first so its lock is released; otherwise reopening fails. Its
			// path leaves LIVE_WRITER_PATHS so the stale write.lock it may have left behind becomes
			// eligible for auto-clearing below.
			if (indexWriter != null) {
				// Before the writer: the SearcherManager holds an NRT reader ON this writer, and
				// closing the writer with that reader still open leaks it.
				closeSearcherManagerQuietly();
				try {
					indexWriter.close();
				} catch (Throwable t) {
					logger.warn("Error closing stale IndexWriter before reopen (lock may be released on close): {}", t.getMessage());
				}
				indexWriter = null;
				LIVE_WRITER_PATHS.remove(indexPath);
			}

			// Also covers the writer==null case: close() and the tests' reflection-based
			// "simulate a crashed writer" both leave a manager bound to a writer that is gone.
			closeSearcherManagerQuietly();

			logger.debug("(re)opening Lucene IndexWriter...");

			try {
				indexWriter = openNewIndexWriter();
				logger.info("IndexWriter initialized");
			} catch (LockObtainFailedException lofe) {
				// The write lock could not be acquired. Recover ONLY when no live writer in this JVM
				// owns the index (LIVE_WRITER_PATHS), which means the lock is stale - left by a writer
				// that died on a tragic event (e.g. an OutOfMemoryError during commit/merge under load,
				// the ~4h prod wedge at 16:23-20:35 that previously needed a restart), by a hard crash /
				// kill of a prior JVM, or by an older build's NativeFSLockFactory (whose write.lock file
				// persists on disk). Because the index uses SimpleFSLockFactory (see openNewIndexWriter)
				// the lock is nothing but the write.lock file's existence, so deleting the stale file and
				// retrying once lets L2 self-heal in-process.
				//
				// If a live writer IN THIS JVM does own it, we must NOT delete: that would put two
				// writers on one index (corruption). That only arises from two managers on one dir in
				// one process - unsupported in prod (single writer per FRONTCACHE_HOME).
				if (!LIVE_WRITER_PATHS.contains(indexPath)) {
					logger.warn("L2 Lucene write lock is stale ({}) - clearing it and retrying", lofe.getMessage());
					deleteStaleWriteLock();
					try {
						indexWriter = openNewIndexWriter();
						logger.warn("L2 Lucene IndexWriter recovered automatically after clearing a stale write lock");
					} catch (IOException retryEx) {
						logReopenFailureThrottled(retryEx);
						throw retryEx;
					}
				} else {
					logReopenFailureThrottled(lofe);
					throw lofe;
				}
			} catch (IOException e) {
				logReopenFailureThrottled(e);
				throw e;
			}
		}

		return indexWriter;
	}

	/**
	 * Opens a fresh IndexWriter on the (lazily opened, reused) Directory with cache-tuned config.
	 * A new IndexWriterConfig is built on every call because one config instance may back only a
	 * single IndexWriter.
	 */
	private IndexWriter openNewIndexWriter() throws IOException {
		// Open the Directory once and reuse it - a fresh Directory per reopen leaks stale locks.
		//
		// Use SimpleFSLockFactory instead of Lucene's default NativeFSLockFactory: here the write
		// lock is just the write.lock FILE's existence - there is no OS file-channel lock and no
		// process-global lock table. A writer that dies tragically (OOM during commit) or is killed
		// (kill -9, crash) leaks the default's OS lock + static LOCK_HELD entry, both unreachable, so
		// every reopen throws "Lock held by this virtual machine" until a JVM restart. The file-based
		// lock instead leaves only a stale file, which we can delete and retry (see getIndexWriter),
		// letting L2 self-heal. Safe because Frontcache is single-writer per index dir (each instance
		// owns its FRONTCACHE_HOME), so the cross-process protection we give up is protection we never
		// relied on.
		if (directory == null)
			directory = FSDirectory.open(Paths.get(indexPath), SimpleFSLockFactory.INSTANCE);

		// Claim the path BEFORE constructing the writer, not after. new IndexWriter() creates write.lock
		// early but can take hundreds of ms to return on a large index; registering only on success left
		// a window in which a concurrent manager saw "no live writer owns this lock", judged the lock
		// stale and deleted it out from under us. Cleared again below if construction fails, so a real
		// stale lock is still recoverable.
		boolean claimed = LIVE_WRITER_PATHS.add(indexPath);
		IndexWriter w;
		try {
			w = openWriterOnDirectory();
		} catch (Throwable t) {
			if (claimed)
				LIVE_WRITER_PATHS.remove(indexPath);
			throw t;
		}
		return w;
	}

	/**
	 * Constructs the {@link IndexWriter}, rebuilding the directory if the on-disk index is in a format
	 * Lucene can no longer read.
	 */
	private IndexWriter openWriterOnDirectory() throws IOException {
		IndexWriter w;
		try {
			w = new IndexWriter(directory, buildIndexWriterConfig());
		} catch (IndexFormatTooOldException tooOld) {
			// Lucene reads only one major version back, so a Lucene 8 L2 index cannot be opened by
			// Lucene 10 - IndexWriter construction throws IndexFormatTooOldException. L2 is a
			// regenerable page-fragment cache, so we discard the incompatible index and rebuild it
			// empty rather than letting the writer stay null and silently drop the whole app to
			// dynamic/uncached (the wedge failure mode). The cache repopulates from origin on demand.
			logger.warn("L2 Lucene index at {} is in an unreadable older format ({}) - discarding it and "
					+ "rebuilding an empty index; it will repopulate from origin.", indexPath, tooOld.getMessage());
			rebuildEmptyIndexDirectory();
			w = new IndexWriter(directory, buildIndexWriterConfig());
		}
		return w;
	}

	/**
	 * A checked-out searcher together with the manager it came from.
	 * <p>
	 * The manager travels with the searcher because the two can come apart: a writer reopen between
	 * acquire and release swaps {@link LuceneIndexManager#searcherManager}, and releasing against
	 * whatever happens to be in the field at that moment would silently skip the decRef. The reader
	 * would then stay checked out forever, pinning its segment files against deletion by merges - a
	 * disk leak that only shows up as an index that never shrinks.
	 */
	private static final class Acquired {
		final SearcherManager manager;
		final IndexSearcher searcher;

		Acquired(SearcherManager manager, IndexSearcher searcher) {
			this.manager = manager;
			this.searcher = searcher;
		}
	}

	/**
	 * The near-real-time searcher for this index, or {@code null} when L2 is unreadable (no writer).
	 * <p>
	 * Every caller must {@link #releaseSearcher(Acquired)} in a {@code finally} - see {@link Acquired}.
	 * <p>
	 * The manager is built lazily rather than alongside the writer, so a failure here degrades to "L2
	 * reads unavailable until the next attempt" rather than permanently: the writer's fast path would
	 * never revisit an eager construction that failed.
	 */
	private Acquired acquireSearcher() throws IOException {

		IndexWriter iWriter = getIndexWriter(); // reopens a broken writer, and with it the searcher
		if (null == iWriter)
			return null;

		SearcherManager mgr = searcherManager;
		if (null == mgr) {
			synchronized (writerMonitorFor(indexPath)) {
				if (null == searcherManager && null != indexWriter && indexWriter.isOpen())
					// applyAllDeletes=true: a deleted (invalidated / expired) entry must not be
					// served. writeAllDeletes=false keeps the deletes in memory rather than pushing
					// them to disk on every reopen.
					searcherManager = new SearcherManager(indexWriter, true, false, null);
				mgr = searcherManager;
			}
		}

		if (null == mgr)
			return null;

		return new Acquired(mgr, mgr.acquire());
	}

	/** Counterpart of {@link #acquireSearcher()}. Null-safe, so callers can release from a plain finally. */
	private void releaseSearcher(Acquired acquired) {
		if (null == acquired)
			return;
		try {
			// Released against the manager that handed it out, not the current one - see Acquired.
			// ReferenceManager.release() is a decRef and stays valid after that manager was closed.
			acquired.manager.release(acquired.searcher);
		} catch (Exception e) {
			logger.debug("Error releasing L2 searcher: {}", e.toString());
		}
	}

	/**
	 * Closes and forgets the current searcher. Must be called before the writer it is bound to is
	 * closed. Callers hold {@code writerMonitorFor(indexPath)}, except {@link #close()} which is not
	 * concurrent with a reopen by contract.
	 */
	private void closeSearcherManagerQuietly() {
		SearcherManager mgr = searcherManager;
		searcherManager = null;
		if (null == mgr)
			return;
		try {
			mgr.close();
		} catch (Throwable t) {
			logger.debug("Error closing L2 searcher manager: {}", t.toString());
		}
	}

	/**
	 * Runs after a write (index or delete) has been handed to the writer.
	 * <p>
	 * This is what replaced the unconditional {@code commit()} that used to end every write - see
	 * {@link Tuning} for why. A refresh here is what keeps a just-cached entry visible to the very next
	 * request for that URL; the commit is left to the timer.
	 */
	private void afterWrite(IndexWriter iWriter, String operation) {

		if (0 >= commitIntervalMs) {
			// commit-per-write restored by config; the commit publishes the change, so no refresh needed
			commitQuietly(iWriter, operation + "/commit");
			return;
		}

		if (!refreshOnWrite)
			return; // the scheduled refresh publishes it, within refreshIntervalMs

		SearcherManager mgr = searcherManager;
		if (null == mgr)
			return; // nothing has read this index yet - the first read builds a current searcher

		try {
			// BLOCKING, deliberately. maybeRefresh() returns false on the spot when another thread is
			// already refreshing - and its reopen may have STARTED BEFORE this write, so this entry is
			// not in it and nothing else will refresh until the scheduled one, up to refreshIntervalMs
			// away. That is not a rare theoretical window: the scheduled refresher is itself one of
			// those threads, so it collides with ordinary writes by construction. It cost an e2e
			// failure - cache the entry, read it 7ms later, miss - which is the origin stampede this
			// refresh exists to prevent.
			//
			// maybeRefreshBlocking() waits for the in-flight reopen, then does its own, which is
			// guaranteed to include this write. The cost is that concurrent writers serialize here
			// instead of sharing one reopen. That is affordable where sharing was not correct: writes
			// are bounded by the MISS rate, each has just paid a full origin round trip, and a reopen
			// is still far cheaper than the commit() this replaced. Reads pay nothing either way.
			mgr.maybeRefreshBlocking();

		} catch (AlreadyClosedException ace) {
			discardWriterIfLockLost(ace, operation + "/refresh");
		} catch (Exception e) {
			logger.warn("Could not refresh the L2 searcher after {} - the entry stays invisible until the "
					+ "scheduled refresh: {}", operation, e.toString());
		}
	}

	/**
	 * Deletes every file in the index directory and reopens a fresh Directory, discarding an index
	 * whose on-disk format Lucene can no longer read (e.g. a Lucene 8 index after the upgrade to
	 * Lucene 10). Safe because L2 is a regenerable cache - the dropped entries are re-fetched from
	 * origin on demand. Any stale {@code write.lock} file is cleared along with the segment files.
	 */
	private void rebuildEmptyIndexDirectory() throws IOException {
		if (directory != null) {
			try {
				directory.close();
			} catch (IOException e) {
				logger.warn("Error closing directory before rebuild: {}", e.getMessage());
			}
			directory = null;
		}
		Path dir = Paths.get(indexPath);
		if (Files.isDirectory(dir)) {
			try (java.util.stream.Stream<Path> files = Files.list(dir)) {
				files.forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException e) {
						logger.warn("Could not delete stale index file {}: {}", p, e.getMessage());
					}
				});
			}
		}
		directory = FSDirectory.open(dir, SimpleFSLockFactory.INSTANCE);
	}

	/**
	 * Logs the "L2 writer unavailable" ERROR at most once per interval instead of a per-request
	 * stack-trace flood (a permanently-broken writer previously produced GBs/day), with an
	 * actionable restart hint for the case recovery genuinely can't fix (e.g. disk full).
	 */
	private void logReopenFailureThrottled(IOException e) {
		long now = System.currentTimeMillis();
		if (now - lastReopenFailureLogMillis > REOPEN_FAILURE_LOG_INTERVAL_MILLIS) {
			lastReopenFailureLogMillis = now;
			logger.error("L2 Lucene IndexWriter is unavailable - cache reads/writes to L2 are failing. "
					+ "If this persists, restart the Frontcache server (index dir: {}). Cause: {}",
					indexPath, e.toString());
		}
	}

	/**
	 * Commits pending changes, logging rather than propagating failures, and discarding the writer if
	 * its lock turned out to be invalidated (so the next operation reopens a working one).
	 * <p>
	 * Used from the {@code finally} blocks of the mutating operations. An {@link AlreadyClosedException}
	 * raised here is a RuntimeException, so before this it escaped the (IOException-only) handlers and
	 * surfaced far away - e.g. as "Soft invalidation/refresh failed" on every background refresh.
	 */
	private void commitQuietly(IndexWriter iWriter, String operation) {
		if (indexWriter == null)
			return; // writer already discarded by this operation's error handling
		try {
			iWriter.commit();
		} catch (IOException e) {
			if (!discardWriterIfLockLost(e, operation))
				logger.error(e.getMessage(), e);
		} catch (AlreadyClosedException ace) {
			discardWriterIfLockLost(ace, operation);
		}
	}

	/**
	 * True when {@code t} (or anything in its cause chain) is an {@link AlreadyClosedException} - i.e.
	 * this writer's lock is no longer valid, typically because its {@code write.lock} file was deleted
	 * and recreated by something else ("Underlying file changed by an external force").
	 */
	private static boolean isWriterLockLost(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof AlreadyClosedException)
				return true;
			if (c == c.getCause())
				break; // defensive: self-referencing cause
		}
		return false;
	}

	/**
	 * Discards a writer whose lock has been invalidated so the next call reopens a working one.
	 * <p>
	 * {@link IndexWriter#isOpen()} is NOT sufficient to detect this: an invalid lock is not one of
	 * Lucene's "tragic" events, so the writer reports itself open while every operation on it throws
	 * {@link AlreadyClosedException}. Without this, getIndexWriter()'s fast path kept handing back the
	 * same dead writer indefinitely - in production that flooded ~9k errors/day for 25 hours and left
	 * soft invalidation permanently broken until a restart.
	 *
	 * @return true if a lost-lock writer was discarded (caller's operation still failed, the next one
	 *         will reopen)
	 */
	private boolean discardWriterIfLockLost(Throwable t, String operation) {
		if (!isWriterLockLost(t))
			return false;

		synchronized (writerMonitorFor(indexPath)) {
			closeSearcherManagerQuietly(); // bound to this writer - see the field javadoc
			if (indexWriter != null) {
				try {
					indexWriter.close();
				} catch (Throwable closeFailure) {
					// expected: SimpleFSLock refuses to remove a lock file it no longer owns
					// ("Lock file cannot be safely removed") - nothing to do but drop the reference
					logger.debug("Closing a writer with a lost lock failed: {}", closeFailure.toString());
				}
				indexWriter = null;
				LIVE_WRITER_PATHS.remove(indexPath);
			}
		}
		logger.error("L2 Lucene write lock was invalidated externally during {} - discarded the writer, "
				+ "the next L2 operation will reopen it (index dir: {}). Cause: {}", operation, indexPath, t.toString());
		return true;
	}

	/**
	 * Deletes a stale {@code write.lock} left behind by a writer that died without releasing it,
	 * so a fresh writer can reacquire the (file-based, SimpleFSLockFactory) lock.
	 * <p>
	 * Only called from the reopen path after a lock acquisition already failed, at which point our
	 * own writer is closed/null - so no live writer's lock is being pulled out from under it.
	 */
	private void deleteStaleWriteLock() {
		Path lockFile = Paths.get(indexPath).resolve(IndexWriter.WRITE_LOCK_NAME);
		try {
			if (Files.deleteIfExists(lockFile))
				logger.warn("Deleted stale L2 write lock {}", lockFile);
			else
				logger.warn("No L2 write lock file to delete at {} (already gone?)", lockFile);
		} catch (IOException e) {
			logger.warn("Could not delete stale L2 write lock {} - a restart may still be required: {}", lockFile, e.toString());
		}
	}

	/**
	 * Builds the IndexWriterConfig with merge tuning appropriate for a cache-style index.
	 *
	 * This index is churn-heavy: every re-cache is an updateDocument() (delete + add) and
	 * invalidation/expiry deletes docs. With Lucene's default TieredMergePolicy the largest
	 * merged segment is capped at 5 GB; once a segment hits that size it is no longer merged,
	 * so the deleted docs inside it are never reclaimed and the index grows without bound
	 * (production reached a single 5.3 GB segment). Capping the max merged segment keeps
	 * segments merge-eligible so deletes are reclaimed, and lowering the tolerated deletes
	 * percentage makes the policy reclaim them sooner.
	 */
	private IndexWriterConfig buildIndexWriterConfig() {
		IndexWriterConfig iwc = new IndexWriterConfig(new StandardAnalyzer());
		iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
		iwc.setRAMBufferSizeMB(250.0);

		TieredMergePolicy mergePolicy = new TieredMergePolicy();
		mergePolicy.setMaxMergedSegmentMB(maxMergedSegmentMB);
		mergePolicy.setDeletesPctAllowed(deletesPctAllowed);
		iwc.setMergePolicy(mergePolicy);

		// wrap the default deletion policy so backup() can take a consistent, merge-safe snapshot
		snapshotDeletionPolicy = new SnapshotDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
		iwc.setIndexDeletionPolicy(snapshotDeletionPolicy);

		return iwc;
	}

	/**
	 * Takes a consistent, hot backup of the index into a {@code .zip} archive sibling to the index
	 * dir, named {@code l2-lucene-index.backup-{yyyyMMdd_HHmmss}.zip}.
	 * <p>
	 * The current writer is committed and a {@link SnapshotDeletionPolicy} snapshot pins the
	 * commit's files so they survive concurrent merges while they are streamed into the archive.
	 * Each file is zipped on the fly (no intermediate copy on disk); entries are nested under a
	 * top-level folder that keeps the original index directory name (e.g. {@code l2-lucene-index}),
	 * so unzipping yields a directory identical to the live index - only the archive file name
	 * carries the {@code .backup-{yyyyMMdd_HHmmss}} suffix.
	 * The snapshot is always released afterwards so the pinned files can be reclaimed.
	 *
	 * @return the name of the created backup archive
	 * @throws IOException if the index writer is unavailable or the archive can't be written
	 */
	public String backup() throws IOException {

		IndexWriter iWriter = getIndexWriter();
		if (iWriter == null)
			throw new IOException("L2 Lucene index writer is unavailable - cannot back up");

		SnapshotDeletionPolicy snapshotPolicy = this.snapshotDeletionPolicy;
		if (snapshotPolicy == null)
			throw new IOException("Snapshot deletion policy is not configured - cannot back up");

		// flush pending changes so the snapshot reflects the current cache state
		iWriter.commit();

		Path indexDir = Paths.get(indexPath).toAbsolutePath();
		// keep the original index directory name inside the archive (e.g. l2-lucene-index)
		String indexDirName = indexDir.getFileName().toString();
		String archiveName = indexDirName + ".backup-" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".zip";
		Path archiveFile = indexDir.getParent().resolve(archiveName);

		IndexCommit commit = snapshotPolicy.snapshot();
		try (OutputStream fos = Files.newOutputStream(archiveFile);
				ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

			for (String fileName : commit.getFileNames()) {
				zos.putNextEntry(new ZipEntry(indexDirName + "/" + fileName));
				Files.copy(indexDir.resolve(fileName), zos); // does not close zos
				zos.closeEntry();
			}

			logger.info("L2 index backup created: {} ({} files)", archiveFile, commit.getFileNames().size());
		} finally {
			snapshotPolicy.release(commit);
		}

		return archiveName;
	}

	/**
	 * Writes webResponse to index
	 * @param response
	 * @throws IOException
	 */
	void indexDoc(WebResponse response) throws IOException {

		IndexWriter iWriter = getIndexWriter();

		Document doc = new Document();

		String url = response.getUrl();

		if (null == url)
		{
			logger.error("URL can't be null during index time for " + response);
			return;
		}

		String urlKey = toPathKey(url);
		doc.add(new StringField(URL_FIELD, urlKey, Field.Store.YES));

		if (null != response.getContent())
			doc.add(new StoredField(BIN_FIELD, response.getContent()));

		// index the effective expiration timestamp as a searchable point so expired
		// entries can be purged with a range query (see deleteExpired()).
		// "cache forever" (-1) is stored as Long.MAX_VALUE so it never falls into the expired range.
		long expireTimeMillis = response.getExpireTimeMillis();
		long expireIndexValue = (CacheProcessor.CACHE_FOREVER == expireTimeMillis) ? Long.MAX_VALUE : expireTimeMillis;
		doc.add(new LongPoint(EXPIRE_DATE_FIELD, expireIndexValue));

		doc.add(new StoredField(JSON_FIELD, gson.toJson(response), JSON_TYPE));

		for (String tag : response.getTags())
			doc.add(new StringField(TAGS_FIELD, tag, Field.Store.NO)); // tag is StringField to exact match

		try {
			iWriter.updateDocument(new Term(URL_FIELD, urlKey), doc);
		} catch (IOException e) {
			if (!discardWriterIfLockLost(e, "indexDoc"))
				logger.error("Error while in Lucene index operation: {}", e.getMessage(), e);
		} catch (AlreadyClosedException ace) {
			// lock invalidated externally - drop the writer so the next call reopens instead of wedging
			discardWriterIfLockLost(ace, "indexDoc");
			return; // nothing to publish on a dead writer
		} finally {
			if (indexWriter != null)
				afterWrite(iWriter, "indexDoc");
		}

	}

	public void close() {

		// Stop the timers first: a scheduled commit or refresh firing while we tear the writer down
		// would race with it. shutdownNow() rather than shutdown() - a task that is mid-commit finishes
		// on its own (the interrupt only matters to a task that is waiting), and the explicit commit
		// below covers whatever the last scheduled one did not.
		ScheduledExecutorService local = maintenance;
		maintenance = null;
		if (null != local)
			local.shutdownNow();

		// Before the writer: the searcher holds an NRT reader on it.
		closeSearcherManagerQuietly();

		if (indexWriter != null && indexWriter.isOpen()) {
			// Writes no longer commit themselves, so an ordinary shutdown is where the last
			// commitIntervalMs of cache entries are made durable. IndexWriter.close() commits too;
			// this is explicit so a commit failure is logged as one rather than as a close failure.
			commitQuietly(indexWriter, "close");
			try {
				indexWriter.close();
			} catch (Exception e) {
				logger.error("Error:", e);
			}
		}
		indexWriter = null;
		// SimpleFSLockFactory deletes write.lock on a clean close; drop our live-writer registration
		// so a later reopen treats any leftover lock as stale.
		LIVE_WRITER_PATHS.remove(indexPath);

		if (directory != null) {
			try {
				directory.close();
			} catch (Exception e) {
				logger.error("Error closing Lucene directory:", e);
			}
			directory = null;
		}

		// Drop the registry entry so a later init (destroy/reload cycle) builds a fresh manager rather
		// than handing out this closed one. Removed by identity: if a new manager for this path has
		// already been registered we must not evict it.
		INSTANCES.remove(indexPath, this);
	}

	/**
	 * Returns document based on url.
	 * <p>
	 * This is the L2 read path - it runs on the request thread for every cache lookup that misses L1,
	 * so it goes through the shared {@link #searcherManager} rather than opening a reader of its own.
	 * See that field for what the old {@code DirectoryReader.open(indexWriter)} per read actually cost.
	 */
	private Document getDocByURL(String url) throws IOException {

		Acquired acquired = null;
		try {
			acquired = acquireSearcher();
		} catch (Exception e1) {
			logger.debug("Error during acquiring L2 searcher. " + e1.getMessage());
			return null;
		}

		if (null == acquired)
			return null;

		try {
			IndexSearcher searcher = acquired.searcher;

			Term term = new Term(URL_FIELD, toPathKey(url));
			Query query = new TermQuery(term);

			TopDocs results = searcher.search(query, 1);

			if (results.scoreDocs != null) {
				if (results.scoreDocs.length == 1) {
					return searcher.storedFields().document(results.scoreDocs[0].doc);
				}
			}
		} finally {
			releaseSearcher(acquired);
		}

		return null;
	}


	/**
	 * Removes documents by url or tag.
	 *
	 * @param urlOrTag a full URL (normalised to its path), a bare path, or an invalidation tag
	 */
	public void delete(String urlOrTag) {

		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return ;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return;
		}

		try {
			Query urlQuery = new TermQuery(new Term(URL_FIELD, toPathKey(urlOrTag)));
			Query tagsQuery = new TermQuery(new Term(TAGS_FIELD, urlOrTag));

			BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

			booleanQuery.add(urlQuery, Occur.SHOULD);
			booleanQuery.add(tagsQuery, Occur.SHOULD);

			long count = iWriter.deleteDocuments(booleanQuery.build());
			logger.debug("Removed  {} documents for {}.", count, urlOrTag);
		} catch (IOException e) {
			if (!discardWriterIfLockLost(e, "delete"))
				logger.error(e.getMessage(), e);
		} catch (AlreadyClosedException ace) {
			discardWriterIfLockLost(ace, "delete");
		} finally {
			afterWrite(iWriter, "delete");
		}
	}

	/**
	 * Removes every document from the index.
	 *
	 * There used to be a domain-scoped flavour that matched a DOMAIN_FIELD term. Entries pushed in by
	 * index replication carry the SOURCE edge's domain, so it silently walked past them and they
	 * stayed served forever. With one site per node there is one flavour, and no DOMAIN_FIELD.
	 */
	public void deleteAll() {

		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return ;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return;
		}

		try {
			long count = iWriter.deleteAll();
			logger.info("Removed all {} documents.", count);
		} catch (IOException e) {
			if (!discardWriterIfLockLost(e, "deleteAll"))
				logger.error(e.getMessage(), e);
		} catch (AlreadyClosedException ace) {
			discardWriterIfLockLost(ace, "deleteAll");
		} finally {
			afterWrite(iWriter, "deleteAll");
		}
	}

	/**
	 * Removes all documents whose effective expiration timestamp is in the past.
	 *
	 * "Cache forever" entries are indexed with Long.MAX_VALUE (see indexDoc()) so
	 * they are never selected by the expired range. Documents indexed before the
	 * EXPIRE_DATE_FIELD point existed carry no point value and are left untouched
	 * until they are re-cached.
	 *
	 * @return number of expired documents removed from the index
	 */
	public long deleteExpired() {

		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return 0;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return 0;
		}

		long count = 0;
		Acquired acquired = null;
		try {
			// expired = expire_date in [MIN_VALUE, now] ; forever entries are Long.MAX_VALUE -> excluded
			long now = System.currentTimeMillis();
			Query expiredQuery = LongPoint.newRangeQuery(EXPIRE_DATE_FIELD, Long.MIN_VALUE, now);

			// Count matches first so we can report how many were purged. The count comes from the
			// shared searcher, so it can lag the writer by up to one refresh - it is a log line, and
			// the delete below runs against the writer's own live view either way.
			acquired = acquireSearcher();
			if (null != acquired)
				count = acquired.searcher.count(expiredQuery);

			iWriter.deleteDocuments(expiredQuery);
			logger.info("deleteExpired() removed {} expired documents", count);
		} catch (IOException e) {
			if (!discardWriterIfLockLost(e, "deleteExpired"))
				logger.error(e.getMessage(), e);
		} catch (AlreadyClosedException ace) {
			discardWriterIfLockLost(ace, "deleteExpired");
		} finally {
			releaseSearcher(acquired);
			afterWrite(iWriter, "deleteExpired");
		}

		return count;
	}

	/**
	 * Returns all URL keys stored in the index.
	 * <p>
	 * Uses a paginated {@link MatchAllDocsQuery} with {@link IndexSearcher#searchAfter}
	 * and loads only the {@link #URL_FIELD} stored field per document, avoiding the
	 * cost of materialising large binary / JSON fields for every hit.
	 *
	 * @return list of normalised URL keys (never {@code null})
	 */
	public List<String> getKeys()
	{
		Acquired acquired = null;
		try {
			acquired = acquireSearcher();
		} catch (Exception e1) {
			logger.debug("Error during acquiring L2 searcher. " + e1.getMessage());
			return Collections.emptyList();
		}

		if (null == acquired)
			return Collections.emptyList();

		List<String> keys = new ArrayList<String>();

		try {
			IndexSearcher searcher = acquired.searcher;
			StoredFields storedFields = searcher.getIndexReader().storedFields();

			// only load the URL stored field — skip JSON, BIN, etc.
			Set<String> urlFieldOnly = Collections.singleton(URL_FIELD);

			int pageSize = 1024;
			Query query = new MatchAllDocsQuery();
			ScoreDoc lastHit = null;

			while (true) {
				TopDocs topDocs = (lastHit == null)
						? searcher.search(query, pageSize)
						: searcher.searchAfter(lastHit, query, pageSize);

				if (topDocs.scoreDocs.length == 0)
					break;

				for (ScoreDoc hit : topDocs.scoreDocs) {
					Document doc = storedFields.document(hit.doc, urlFieldOnly);
					String url = doc.get(URL_FIELD);
					if (url != null)
						keys.add(url);
					else
						logger.error("URL is null for doc id {} (probably corrupted during index time)", hit.doc);
				}

				if (topDocs.scoreDocs.length < pageSize)
					break; // last page

				lastHit = topDocs.scoreDocs[topDocs.scoreDocs.length - 1];
			}
		} catch (Exception e) {
			if (!discardWriterIfLockLost(e, "getKeys"))
				logger.error("Error during loading urls/keys from index", e);
		} finally {
			releaseSearcher(acquired);
		}

		return keys;
	}

	/**
	 * Gets response from index
	 * @param url request url
	 * @return WebResponse from index
	 */
	public WebResponse getResponse(String url) {
		WebResponse response = null;
		try {
			Document doc = getDocByURL(url);
			if (doc != null) {
				response = gson.fromJson(doc.get(JSON_FIELD), WebResponse.class);
				BytesRef bin1ref = doc.getBinaryValue(BIN_FIELD);
				if (null != bin1ref)
					// BytesRef is (bytes, offset, length) and its backing array may be longer than -
					// and shared beyond - the value it carries. Handing `bytes` straight to the
					// WebResponse published trailing bytes as page content and retained an array
					// larger than the entry for as long as that entry was alive.
					response.setContent(Arrays.copyOfRange(bin1ref.bytes,
							bin1ref.offset, bin1ref.offset + bin1ref.length));
			}

			return response;
		} catch (Exception e) {
			if (!discardWriterIfLockLost(e, "getResponse"))
				logger.error("Error during loading data from index", e);
		}
		return null;
	}

	/**
	 * Returns index size
	 * @return
	 */
	/**
	 * Document count, read from the live writer rather than through a searcher.
	 *
	 * {@code IndexWriter.getDocStats()} is an in-memory read of state the writer already maintains -
	 * cheaper still than {@link #getIndexSize()}'s searcher acquire, and, being the writer's own view,
	 * it does not lag a refresh. That is what a Prometheus scrape arriving every few seconds forever
	 * wants.
	 *
	 * The two can differ by whatever has been written since the last searcher refresh; for a cache-size
	 * gauge that is noise.
	 *
	 * @return the document count, or -1 when the writer is unavailable - never an exception, because
	 *         the caller is a metrics gauge and a wedged index must not break a scrape
	 */
	public int getIndexSizeFast() {
		try {
			IndexWriter writer = getIndexWriter();
			return (null == writer) ? -1 : writer.getDocStats().numDocs;

		} catch (Exception e) {
			logger.debug("Can't read doc count from the index writer", e);
			return -1;
		}
	}

	/**
	 * On-disk size of the index, summed over the files Lucene currently lists.
	 *
	 * This is the number that answers "will the cache volume fill up", which document count does not:
	 * entry sizes vary by orders of magnitude on a page cache. Deleted-but-not-merged documents are
	 * included, deliberately - they occupy the disk this is measuring.
	 *
	 * @return total bytes, or -1 when the directory cannot be read
	 */
	public long getIndexDiskBytes() {
		try {
			Directory dir = directory;
			if (null == dir)
				return -1;

			long total = 0;
			for (String name : dir.listAll())
			{
				try {
					total += dir.fileLength(name);
				} catch (IOException merged) {
					// a segment file merged away between listAll() and fileLength() - skip it rather
					// than fail the whole reading; the next scrape sees the new file set
				}
			}

			return total;

		} catch (Exception e) {
			logger.debug("Can't measure index size on disk", e);
			return -1;
		}
	}

	/**
	 * Document count as a reader sees it - the number the management API and the console report.
	 * <p>
	 * Reads through the shared near-real-time searcher. It used to open its own {@code FSDirectory} and
	 * {@code DirectoryReader}, which read the last COMMIT - and since commits became periodic
	 * (see {@link Tuning}) that would report a count up to one commit interval stale, and zero on a
	 * node that has not committed yet. The NRT view is both correct and far cheaper.
	 *
	 * @return the document count, or -1 when L2 is unreadable
	 */
	public int getIndexSize(){
		Acquired acquired = null;
		try {
			acquired = acquireSearcher();
			return (null == acquired) ? -1 : acquired.searcher.getIndexReader().numDocs();

		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return -1;
		} finally {
			releaseSearcher(acquired);
		}
	}


}
