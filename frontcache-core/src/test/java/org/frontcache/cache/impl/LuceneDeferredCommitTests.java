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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.UUID;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.frontcache.core.WebResponse;
import org.junit.Test;

/**
 * The L2 read/commit contract as it stands after the reads were taken off the writer.
 *
 * Two things changed together and have to be pinned together, because each one alone looks like a
 * regression:
 *
 *  - reads go through a shared near-real-time searcher instead of opening a DirectoryReader on the
 *    IndexWriter per read (which forced a writer flush on every cache lookup), and
 *  - writes no longer commit, so the fsync moved off the request path onto a timer.
 *
 * The visible consequence is that "just cached" and "durably on disk" are no longer the same moment.
 * Everything here is about where that line now sits. See LuceneIndexManager.Tuning.
 */
public class LuceneDeferredCommitTests {

	private static String freshDir(String name) {
		return "/tmp/lucene-" + name + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
	}

	private static WebResponse entry(String url, String body) {
		return new WebResponse(url, body.getBytes());
	}

	/** True if {@code url} is in the index AS COMMITTED ON DISK - no IndexWriter involved. */
	private static boolean committedOnDisk(String dir, String url) throws Exception {
		try (Directory d = FSDirectory.open(Paths.get(dir));
				IndexReader reader = DirectoryReader.open(d)) {
			Term term = new Term(LuceneIndexManager.URL_FIELD, LuceneIndexManager.toPathKey(url));
			return new IndexSearcher(reader).count(new TermQuery(term)) > 0;
		}
	}

	/**
	 * The whole point of refresh-on-write: a cache entry must be readable by the very next request for
	 * that URL. Without it a popular page keeps missing - and stampeding the origin - for the whole
	 * refresh window, which would be a far worse regression than the fsync this change removed.
	 */
	@Test
	public void anEntryIsReadableImmediatelyAfterItIsWritten() throws Exception {
		String dir = freshDir("visibility");
		LuceneIndexManager manager = new LuceneIndexManager(dir);
		try {
			String url = "http://example.com/hot-page.html";
			manager.indexDoc(entry(url, "hot"));

			WebResponse back = manager.getResponse(url);
			assertNotNull("a just-written entry must be visible to the next read, with no commit and no "
					+ "waiting for the scheduled refresh", back);
			assertEquals("hot", new String(back.getContent()));

			// ... and so must its removal: serving an invalidated entry is worse than missing one
			manager.delete(url);
			assertEquals("a just-deleted entry must be gone from the next read", null, manager.getResponse(url));

		} finally {
			manager.close();
		}
	}

	/**
	 * The same guarantee, while other threads are refreshing - which is the case that actually broke.
	 *
	 * {@code SearcherManager.maybeRefresh()} returns false immediately when another thread holds the
	 * refresh lock, and that other thread's reopen may have started BEFORE this write. The entry is
	 * then absent from it and stays invisible until the scheduled refresh, up to a second away, so the
	 * next request for that URL misses and goes to origin. The background refresher collides with
	 * writes by construction, so this is a routine occurrence, not a corner - it cost an e2e failure
	 * (cache an entry, read it 7ms later, miss).
	 *
	 * A refresher thread here stands in for the scheduled one and runs flat out, so any write whose
	 * refresh is merely best-effort loses the race almost every time.
	 */
	@Test
	public void anEntryIsReadableImmediatelyEvenWhileOtherThreadsRefresh() throws Exception {
		String dir = freshDir("visibility-contended");
		final LuceneIndexManager manager = new LuceneIndexManager(dir);
		final java.util.concurrent.atomic.AtomicBoolean stop =
				new java.util.concurrent.atomic.AtomicBoolean(false);

		// stand-in for the scheduled refresher, hammering the refresh lock
		Thread refresher = new Thread(() -> {
			while (!stop.get()) {
				manager.getResponse("http://example.com/does-not-exist.html"); // acquires a searcher
				try {
					manager.forceRefreshForTest();
				} catch (Exception ignored) {
					// the manager may be closing; the assertions below are what matter
				}
			}
		}, "refresh-contender");
		refresher.setDaemon(true);
		refresher.start();

		try {
			int misses = 0;
			for (int i = 0; i < 300; i++) {
				String url = "http://example.com/contended-" + i + ".html";
				manager.indexDoc(entry(url, "body-" + i));
				if (null == manager.getResponse(url))
					misses++;
			}
			assertEquals("every write must be visible to the very next read, even when a concurrent "
					+ "thread is refreshing - a best-effort refresh loses this race and the miss goes "
					+ "to origin", 0, misses);
		} finally {
			stop.set(true);
			refresher.join(10_000);
			manager.close();
		}
	}

	/**
	 * Writes do not fsync any more - that is the change. Stated as a test because it is the one thing
	 * here an operator can be surprised by, and because a future "just add a commit, it's cheap" would
	 * otherwise silently put the per-request fsync back.
	 */
	@Test
	public void aWriteIsVisibleButNotYetOnDisk() throws Exception {
		String dir = freshDir("deferred");
		LuceneIndexManager manager = new LuceneIndexManager(dir);
		try {
			String url = "http://example.com/deferred.html";
			manager.indexDoc(entry(url, "deferred"));

			assertNotNull("visible through the searcher", manager.getResponse(url));
			assertFalse("but NOT committed - the commit is the timer's job now",
					committedOnDisk(dir, url));

			manager.commit();

			assertTrue("an explicit commit is what makes it durable - this is what the management API's "
					+ "invalidate/purge paths call", committedOnDisk(dir, url));

		} finally {
			manager.close();
		}
	}

	/**
	 * An ordinary shutdown is the other half of the durability story: whatever the last scheduled
	 * commit did not cover has to be committed by close(), or a restart would drop up to a commit
	 * interval of cache for no reason.
	 */
	@Test
	public void closeCommitsWhatTheTimerHasNotYet() throws Exception {
		String dir = freshDir("close-commits");
		LuceneIndexManager manager = new LuceneIndexManager(dir);
		String url = "http://example.com/survives-restart.html";
		manager.indexDoc(entry(url, "survives"));
		assertFalse("precondition: nothing committed yet", committedOnDisk(dir, url));

		manager.close();

		assertTrue("close() must commit", committedOnDisk(dir, url));

		// and a fresh manager - a restarted node - reads it back
		LuceneIndexManager restarted = new LuceneIndexManager(dir);
		try {
			WebResponse back = restarted.getResponse(url);
			assertNotNull("a restarted node must find the entry", back);
			assertEquals("survives", new String(back.getContent()));
		} finally {
			restarted.close();
		}
	}

	/**
	 * commitIntervalMs &lt;= 0 restores commit-per-write. It is the escape hatch offered to anyone who
	 * cannot accept the durability trade, so it has to actually work.
	 */
	@Test
	public void commitPerWriteCanBeRestoredByConfig() throws Exception {
		String dir = freshDir("commit-per-write");
		LuceneIndexManager.Tuning legacy = new LuceneIndexManager.Tuning(
				LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB,
				LuceneIndexManager.DEFAULT_DELETES_PCT_ALLOWED,
				0,      // commitIntervalMs <= 0 -> commit on every write
				true,
				LuceneIndexManager.Tuning.DEFAULT_REFRESH_INTERVAL_MS);

		LuceneIndexManager manager = new LuceneIndexManager(dir, legacy);
		try {
			String url = "http://example.com/legacy.html";
			manager.indexDoc(entry(url, "legacy"));

			assertTrue("with commitIntervalMs<=0 the write must commit itself",
					committedOnDisk(dir, url));
			assertNotNull("and still be readable", manager.getResponse(url));

		} finally {
			manager.close();
		}
	}

	/**
	 * Content came back as {@code BytesRef.bytes} - the whole backing array, which Lucene is free to
	 * make longer than the value it carries. A body whose length happened to match hid this; one that
	 * does not is what catches it.
	 */
	@Test
	public void contentIsExactlyWhatWasStored() throws Exception {
		String dir = freshDir("bytesref");
		LuceneIndexManager manager = new LuceneIndexManager(dir);
		try {
			// several sizes, since whether the backing array is oversized depends on Lucene's buffering
			for (int size : new int[] { 1, 7, 100, 1023, 4096, 5000 }) {
				String url = "http://example.com/sized-" + size + ".html";
				byte[] body = new byte[size];
				for (int i = 0; i < size; i++)
					body[i] = (byte) (i % 251);

				manager.indexDoc(new WebResponse(url, body));

				WebResponse back = manager.getResponse(url);
				assertNotNull("entry of size " + size, back);
				assertEquals("content length for size " + size, size, back.getContent().length);
				assertArrayEquals("content bytes for size " + size, body, back.getContent());
			}
		} finally {
			manager.close();
		}
	}
}
