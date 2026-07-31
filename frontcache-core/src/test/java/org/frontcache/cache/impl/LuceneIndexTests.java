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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneIndexTests {

	protected Logger logger = LoggerFactory.getLogger(LuceneIndexTests.class);

	LuceneIndexManager luceneIndexManager = null;

	private final static String DOMAIN = "test-domain";

	private final static String COINSHOME_DOMAIN = "example.com";

	@Test
	public void dummy() throws Exception {

	}

	@Test
	public void fileSaveTest() throws Exception {
		String[] tags = { "apple", "limon", "banana", "chery", "peach" };
		int size = 5;
		for (int i = 0; i < size; i++) {
			String url = UUID.randomUUID().toString();
			String content = UUID.randomUUID().toString();
			luceneIndexManager.delete(DOMAIN, url);

			WebResponse response = new WebResponse(url, content.getBytes());
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> tagSet = new HashSet<>();
			tagSet.add(tags[i]);
			response.setDomain(DOMAIN);
			response.setTags(tagSet);
			response.setStatusCode(55);
			luceneIndexManager.indexDoc(response);

			WebResponse fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
			assertEquals(tagSet, fromFile.getTags()); // tags are restorable from index
		}

		assertEquals(size, luceneIndexManager.getIndexSize());
		luceneIndexManager.delete(DOMAIN, "apple");
		assertEquals(size - 1, luceneIndexManager.getIndexSize());

	}

	@Test
	public void deleteTest() throws Exception {
		String[] tags = { "apple", "limon", "banana", "chery", "peach" };
		int size = 5;
		for (int i = 0; i < size; i++) {
			String url = UUID.randomUUID().toString();
			String content = UUID.randomUUID().toString();
			;

			WebResponse response = new WebResponse(url, content.getBytes());
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> set = new HashSet<>();
			set.add(tags[i]);
			response.setTags(set);
			response.setStatusCode(55);
			response.setDomain(DOMAIN);
			luceneIndexManager.indexDoc(response);

			WebResponse fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
		}

		assertEquals(size, luceneIndexManager.getIndexSize());
		luceneIndexManager.delete(DOMAIN, "apple");
		assertEquals(size - 1, luceneIndexManager.getIndexSize());

	}


	@Test
	public void deleteByUrl() throws Exception {
		String[] tags = { "apple", "limon", "banana", "chery", "peach" };
		int size = 5;
		Map<String, String> urls = new HashMap<>();

		for (int i = 0; i < size; i++) {

			String url = UUID.randomUUID().toString();
			String content = UUID.randomUUID().toString();

			urls.put(url, content);

			WebResponse response = new WebResponse(url, content.getBytes());
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> set = new HashSet<>();
			set.add(tags[i]);
			response.setTags(set);
			response.setStatusCode(55);
			response.setDomain(DOMAIN);
			luceneIndexManager.indexDoc(response);

			WebResponse fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
		}

		for (String url : urls.keySet()) {
			WebResponse fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);
			assertEquals(urls.get(url), new String(fromFile.getContent()));
			luceneIndexManager.delete(DOMAIN, url);
			fromFile = luceneIndexManager.getResponse(url);
			assertNull(fromFile);
		}

	}

	@Test
	public void fileRemoveByTag() throws Exception {

		String url = "https://www.example.com/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm";

		luceneIndexManager.delete(DOMAIN, url);

		WebResponse response = new WebResponse(url, "data".getBytes());
		response.addTags(Arrays.asList(new String[]{"E9AKbzbiOBIAAAFG0vnZjkvL"}));
		response.setDomain(COINSHOME_DOMAIN);
		luceneIndexManager.indexDoc(response);

		WebResponse fromFile = luceneIndexManager.getResponse(url);
		assertEquals(url, fromFile.getUrl());
		assertEquals(new String("data".getBytes()), new String(fromFile.getContent()));

		luceneIndexManager.delete(DOMAIN, "E9AKbzbiOBIAAAFG0vnZjkvL");

		fromFile = luceneIndexManager.getResponse(url);
		assertNull(fromFile);

	}

	@Test
	public void fileEmptyByteTest() throws Exception {

			String url = UUID.randomUUID().toString();
			luceneIndexManager.delete(DOMAIN, url);

			WebResponse response = new WebResponse(url, null);
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> set = new HashSet<>();
			set.add("banana");
			response.setTags(set);
			response.setStatusCode(55);
			response.setDomain(DOMAIN);
			luceneIndexManager.indexDoc(response);

			WebResponse fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);

			response.setContent(new byte[0]);
			luceneIndexManager.indexDoc(response);

			fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);

			response.setContent(new byte[]{'a'});
			luceneIndexManager.indexDoc(response);

			fromFile = luceneIndexManager.getResponse(url);
			assertNotNull(fromFile);
			return;
	}


	@Test
	public void multyWritersTest() throws Exception {

		String baseDir = "/tmp/lucene-text-index-l2-" + System.currentTimeMillis();
		LuceneIndexManager luceneIndexManager1 = new LuceneIndexManager(baseDir); // INDEX_BASE_DIR
		LuceneIndexManager luceneIndexManager2 = new LuceneIndexManager(baseDir); // INDEX_BASE_DIR


//		System.out.println("hello");
		String url = UUID.randomUUID().toString();
		luceneIndexManager1.delete(DOMAIN, url);

		WebResponse response = new WebResponse(url, null);
		response.setDomain(DOMAIN);
		List<String> list = new ArrayList<>();
		list.add(UUID.randomUUID().toString());
		response.getHeaders().put("Accept", list);
		Set<String> set = new HashSet<>();
		set.add("banana");
		response.setTags(set);
		response.setStatusCode(55);
		try
		{
			luceneIndexManager2.indexDoc(response);
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		luceneIndexManager1.close();

		luceneIndexManager2.indexDoc(response);

		// now second writer should have IndexWriter
		WebResponse fromFile = luceneIndexManager2.getResponse(url);
		assertNotNull(fromFile);

		luceneIndexManager1 = luceneIndexManager2;
		luceneIndexManager1.close();
		return;
	}

	@Test
	public void readWriteToClosedWriter() throws Exception {

		String baseDir = "/tmp/lucene-text-index-l2-" + System.currentTimeMillis();
		LuceneIndexManager luceneIndexManager1 = new LuceneIndexManager(baseDir);


		String url = UUID.randomUUID().toString();
		luceneIndexManager1.delete(DOMAIN, url);

		WebResponse response = new WebResponse(url, null);
		response.setDomain(DOMAIN);
		List<String> list = new ArrayList<>();
		list.add(UUID.randomUUID().toString());
		response.getHeaders().put("Accept", list);
		Set<String> set = new HashSet<>();
		set.add("banana");
		response.setTags(set);
		response.setStatusCode(55);

		luceneIndexManager1.indexDoc(response);
		WebResponse fromFile = luceneIndexManager1.getResponse(url);
		assertNotNull(fromFile);
		assertEquals(url, fromFile.getUrl());

		luceneIndexManager1.close();


		fromFile = luceneIndexManager1.getResponse(url);

		assertNotNull(fromFile);
		assertEquals(url, fromFile.getUrl());

		return;
	}


	@Test
	public void indexStoresPathOnly() throws Exception {
		// full URL with scheme, host, and port → only the path is stored in URL_FIELD
		String fullUrl = "https://www.example-site.com:443/en/welcome.htm";
		String expectedPath = "/en/welcome.htm";

		luceneIndexManager.delete(DOMAIN, fullUrl);

		WebResponse response = new WebResponse(fullUrl, "data".getBytes());
		response.setDomain(DOMAIN);
		luceneIndexManager.indexDoc(response);

		// getKeys() returns the normalised path, not the full URL
		List<String> keys = luceneIndexManager.getKeys();
		assertTrue("expected path key " + expectedPath + " but got " + keys, keys.contains(expectedPath));
		assertFalse("full URL should not appear in keys", keys.contains(fullUrl));

		// getResponse works with the full URL (normalised internally)
		WebResponse fromFile = luceneIndexManager.getResponse(fullUrl);
		assertNotNull(fromFile);
		assertEquals(fullUrl, fromFile.getUrl()); // WebResponse preserves original URL from JSON

		// getResponse also works with a different port for the same host
		WebResponse fromOtherPort = luceneIndexManager.getResponse("https://www.example-site.com:9090/en/welcome.htm");
		assertNotNull(fromOtherPort);
		assertEquals(fullUrl, fromOtherPort.getUrl());

		// getResponse also works when called with the bare path
		WebResponse fromPath = luceneIndexManager.getResponse(expectedPath);
		assertNotNull(fromPath);
		assertEquals(fullUrl, fromPath.getUrl());

		// delete works with the full URL
		luceneIndexManager.delete(DOMAIN, fullUrl);
		assertNull(luceneIndexManager.getResponse(fullUrl));
	}

	@Test
	public void samePathDifferentHostResolvesToSameEntry() throws Exception {
		// same path on different hosts maps to the same cache key (host is stripped)
		String url1 = "http://fc1-test.org/common/page.html";
		String url2 = "http://fc2.test.org/common/page.html";

		luceneIndexManager.delete(DOMAIN, url1);

		WebResponse r1 = new WebResponse(url1, "from-fc1".getBytes());
		r1.setDomain(DOMAIN);
		luceneIndexManager.indexDoc(r1);

		// looking up via a different host returns the same entry
		WebResponse back = luceneIndexManager.getResponse(url2);
		assertNotNull(back);
		assertEquals("from-fc1", new String(back.getContent()));

		// only one entry in the index (same path = same key)
		assertEquals(1, luceneIndexManager.getIndexSize());

		luceneIndexManager.delete(DOMAIN, url1);
	}

	@Test
	public void indexStoresPathWithQueryString() throws Exception {
		String fullUrl = "http://example.com:8080/search?q=coin&page=2";
		String expectedPath = "/search?q=coin&page=2";

		luceneIndexManager.delete(DOMAIN, fullUrl);

		WebResponse response = new WebResponse(fullUrl, "results".getBytes());
		response.setDomain(DOMAIN);
		luceneIndexManager.indexDoc(response);

		List<String> keys = luceneIndexManager.getKeys();
		assertTrue(keys.contains(expectedPath));

		// same path+query with a different port/host resolves to the same entry
		WebResponse same = luceneIndexManager.getResponse("http://other.host:9999/search?q=coin&page=2");
		assertNotNull(same);

		luceneIndexManager.delete(DOMAIN, fullUrl);
	}

	@Test
	public void mergeTuningDefaultsTest() {
		// the single-arg constructor delegates to the tuned defaults
		assertEquals(LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB, luceneIndexManager.getMaxMergedSegmentMB(), 0.0001);
		assertEquals(LuceneIndexManager.DEFAULT_DELETES_PCT_ALLOWED, luceneIndexManager.getDeletesPctAllowed(), 0.0001);
	}

	@Test
	public void mergeTuningClampingTest() throws Exception {
		// non-positive segment cap -> default; deletesPct below Lucene's minimum -> clamped up to 20
		LuceneIndexManager m = new LuceneIndexManager("/tmp/lucene-tuning-" + System.currentTimeMillis(), -1, 5);
		try {
			assertEquals(LuceneIndexManager.DEFAULT_MAX_MERGED_SEGMENT_MB, m.getMaxMergedSegmentMB(), 0.0001);
			assertEquals(20.0, m.getDeletesPctAllowed(), 0.0001);
		} finally {
			m.close();
		}

		// valid custom cap is honoured; deletesPct above Lucene's max -> clamped down to 50;
		// and the tuned writer still indexes/reads a (parenthesised) url correctly
		LuceneIndexManager m2 = new LuceneIndexManager("/tmp/lucene-tuning2-" + System.currentTimeMillis(), 64, 99);
		try {
			assertEquals(64.0, m2.getMaxMergedSegmentMB(), 0.0001);
			assertEquals(50.0, m2.getDeletesPctAllowed(), 0.0001);

			String url = "https://www.example.com/en/country_coins-Ottoman_Empire_(1299_1923)-x.htm";
			WebResponse wr = new WebResponse(url, "hello".getBytes());
			wr.setDomain(COINSHOME_DOMAIN);
			m2.indexDoc(wr);

			WebResponse back = m2.getResponse(url);
			assertNotNull(back);
			assertEquals(url, back.getUrl());
		} finally {
			m2.close();
		}
	}

	@Test
	public void backupTest() throws Exception {
		// index a few docs
		int size = 3;
		for (int i = 0; i < size; i++) {
			String url = UUID.randomUUID().toString();
			WebResponse response = new WebResponse(url, UUID.randomUUID().toString().getBytes());
			response.setDomain(DOMAIN);
			luceneIndexManager.indexDoc(response);
		}
		assertEquals(size, luceneIndexManager.getIndexSize());

		java.nio.file.Path indexDir = java.nio.file.Paths.get(baseDir).toAbsolutePath();
		String indexDirName = indexDir.getFileName().toString();

		String archiveName = luceneIndexManager.backup();
		assertNotNull(archiveName);
		// only the archive name carries the .backup-{date}.zip suffix; the index dir name is kept
		assertTrue("backup archive name should follow the {indexDir}.backup-{date}.zip pattern, got " + archiveName,
				archiveName.startsWith(indexDirName + ".backup-") && archiveName.endsWith(".zip"));

		// the backup archive is a sibling of the index dir
		java.nio.file.Path archiveFile = indexDir.getParent().resolve(archiveName);
		assertTrue("backup archive should exist: " + archiveFile, java.nio.file.Files.isRegularFile(archiveFile));

		// unzip it into a fresh dir; entries are nested under the original index dir name
		java.nio.file.Path unzipRoot = indexDir.getParent().resolve("restored-" + System.currentTimeMillis());
		try (java.util.zip.ZipInputStream zis =
				new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(archiveFile))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				java.nio.file.Path out = unzipRoot.resolve(entry.getName());
				java.nio.file.Files.createDirectories(out.getParent());
				java.nio.file.Files.copy(zis, out);
			}
		}

		// the unzipped index (under the kept index dir name) is valid with the same doc count
		java.nio.file.Path restoredIndexDir = unzipRoot.resolve(indexDirName);
		assertTrue("unzipped index dir should exist: " + restoredIndexDir,
				java.nio.file.Files.isDirectory(restoredIndexDir));
		LuceneIndexManager restored = new LuceneIndexManager(restoredIndexDir.toString());
		try {
			assertEquals(size, restored.getIndexSize());
		} finally {
			restored.close();
		}
	}

	/**
	 * Reproduces the production wedge: a writer that dies on a tragic event (e.g. OOM during commit)
	 * or is killed leaves its write lock unreleased, so every reopen fails until a JVM restart. With
	 * the file-based SimpleFSLockFactory the lock is just the {@code write.lock} file, so this is a
	 * leftover lock file plus a dead writer. We simulate it by detaching the live writer's
	 * {@code writeLock} field before closing it (so close() cannot delete the lock file), then assert
	 * the manager reacquires a working writer on its own (self-heal) instead of staying dead.
	 */
	@Test
	public void selfHealsAfterStaleWriteLock() throws Exception {

		// baseline: the writer works
		String url1 = UUID.randomUUID().toString();
		WebResponse r1 = new WebResponse(url1, UUID.randomUUID().toString().getBytes());
		r1.setDomain(DOMAIN);
		luceneIndexManager.indexDoc(r1);
		assertNotNull(luceneIndexManager.getResponse(url1));

		// --- simulate a tragically-killed writer that left its lock file behind ---
		java.lang.reflect.Field iwField = LuceneIndexManager.class.getDeclaredField("indexWriter");
		iwField.setAccessible(true);
		org.apache.lucene.index.IndexWriter liveWriter = (org.apache.lucene.index.IndexWriter) iwField.get(luceneIndexManager);
		assertNotNull(liveWriter);

		// detach the write lock so close() cannot delete the lock file -> stale write.lock stays on disk
		java.lang.reflect.Field writeLockField = org.apache.lucene.index.IndexWriter.class.getDeclaredField("writeLock");
		writeLockField.setAccessible(true);
		writeLockField.set(liveWriter, null);
		liveWriter.close();
		assertFalse("writer must look dead to force the reopen path", liveWriter.isOpen());

		// the lock file is now stale exactly as after a tragic death / kill
		java.nio.file.Path lockFile = java.nio.file.Paths.get(baseDir)
				.resolve(org.apache.lucene.index.IndexWriter.WRITE_LOCK_NAME);
		assertTrue("precondition: a stale write.lock should be left behind", java.nio.file.Files.exists(lockFile));

		// --- next cache write must self-heal (delete the stale lock + reopen) rather than wedge ---
		String url2 = UUID.randomUUID().toString();
		WebResponse r2 = new WebResponse(url2, UUID.randomUUID().toString().getBytes());
		r2.setDomain(DOMAIN);
		luceneIndexManager.indexDoc(r2);

		WebResponse restored = luceneIndexManager.getResponse(url2);
		assertNotNull("L2 writer should have recovered automatically after the stale lock", restored);
		assertEquals(url2, restored.getUrl());
		// the pre-leak entry is still readable too
		assertNotNull(luceneIndexManager.getResponse(url1));
	}

	/**
	 * A fresh manager - as after a server restart or an upgrade from the old NativeFSLockFactory build
	 * whose write.lock file persists on disk - must clear a leftover lock left by a dead prior JVM and
	 * come up working, rather than wedging on startup.
	 */
	@Test
	public void selfHealsAtStartupWithLeftoverLockFromDeadJvm() throws Exception {

		String dir = "/tmp/lucene-leftover-lock-" + System.currentTimeMillis();

		// a first manager creates the index, then (simulating a hard crash) leaves write.lock behind
		LuceneIndexManager crashed = new LuceneIndexManager(dir);
		WebResponse r1 = new WebResponse(UUID.randomUUID().toString(), UUID.randomUUID().toString().getBytes());
		r1.setDomain(DOMAIN);
		crashed.indexDoc(r1);

		java.lang.reflect.Field iwField = LuceneIndexManager.class.getDeclaredField("indexWriter");
		iwField.setAccessible(true);
		org.apache.lucene.index.IndexWriter w = (org.apache.lucene.index.IndexWriter) iwField.get(crashed);
		java.lang.reflect.Field writeLockField = org.apache.lucene.index.IndexWriter.class.getDeclaredField("writeLock");
		writeLockField.setAccessible(true);
		writeLockField.set(w, null); // detach so close() cannot delete the lock file
		w.close();

		// simulate that the crashed JVM is gone: in a new JVM no live writer is registered for this dir
		clearLiveWriterRegistration(dir);
		java.nio.file.Path lockFile = java.nio.file.Paths.get(dir)
				.resolve(org.apache.lucene.index.IndexWriter.WRITE_LOCK_NAME);
		assertTrue("precondition: a stale write.lock should be left behind", java.nio.file.Files.exists(lockFile));

		// a fresh manager must clear the leftover lock during startup and be able to write
		LuceneIndexManager restarted = new LuceneIndexManager(dir);
		String url2 = UUID.randomUUID().toString();
		WebResponse r2 = new WebResponse(url2, UUID.randomUUID().toString().getBytes());
		r2.setDomain(DOMAIN);
		restarted.indexDoc(r2);
		assertNotNull("fresh manager should have recovered from the leftover lock", restarted.getResponse(url2));
		restarted.close();
	}

	@SuppressWarnings("unchecked")
	private static void clearLiveWriterRegistration(String path) throws Exception {
		java.lang.reflect.Field f = LuceneIndexManager.class.getDeclaredField("LIVE_WRITER_PATHS");
		f.setAccessible(true);
		((Set<String>) f.get(null)).remove(path);
	}

	private String baseDir;

	@Before
	public void setUp() {
		baseDir = "/tmp/lucene-text-index-l2-" + System.currentTimeMillis(); // INDEX_BASE_DIR
		luceneIndexManager = new LuceneIndexManager(baseDir);
		return;
	}


	@After
	public void cleanUp() {
		luceneIndexManager.close();
		return;
	}


}
