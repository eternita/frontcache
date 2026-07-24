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
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
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

	private static String INDEX_PATH;

	public static final String JSON_FIELD = "json";
	public static final String BIN_FIELD = "bin";

	// searchable fields
	public static final String TAGS_FIELD = "tags"; // for invalidation
	public static final String URL_FIELD = "url";
	public static final String DOMAIN_FIELD = "domain"; // for shared / multidomain mode
	public static final String EXPIRE_DATE_FIELD = "expire_date";

	private IndexWriter indexWriter = null;

	// The index directory is opened once and reused for the lifetime of this manager.
	// Reopening a fresh FSDirectory on every writer reopen (the previous behaviour) leaked
	// the NativeFSLock held by a broken-but-not-closed writer: the stale lock stayed in the
	// JVM's static lock set, so every subsequent "new IndexWriter(freshDir)" failed forever
	// with "LockObtainFailedException: Lock held by this virtual machine". Reusing one
	// Directory + always closing the stale writer before reopening keeps the lock lifecycle clean.
	private Directory directory = null;

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
	 * Constructor - uses default merge tuning.
	 * @param indexPath
	 */
	public LuceneIndexManager(String indexPath) {
		this(indexPath, DEFAULT_MAX_MERGED_SEGMENT_MB, DEFAULT_DELETES_PCT_ALLOWED);
	}

	/**
	 * Constructor
	 * @param indexPath index directory
	 * @param maxMergedSegmentMB cap on largest merged segment (MB); &lt;=0 -&gt; default
	 * @param deletesPctAllowed % deleted docs tolerated before reclaim; clamped to Lucene's [20,50]
	 */
	public LuceneIndexManager(String indexPath, double maxMergedSegmentMB, double deletesPctAllowed) {
		this.maxMergedSegmentMB = (maxMergedSegmentMB > 0) ? maxMergedSegmentMB : DEFAULT_MAX_MERGED_SEGMENT_MB;
		// TieredMergePolicy.setDeletesPctAllowed requires 20 <= value <= 50
		this.deletesPctAllowed = Math.min(50.0, Math.max(20.0, deletesPctAllowed));

		INDEX_PATH = indexPath;
		Path path = Paths.get(INDEX_PATH);
		if (!Files.exists(path)) {
			try {
				if (path.getParent().toFile().isDirectory() && !path.getParent().toFile().exists())
					Files.createDirectories(path.getParent());

				// create dummy index
				{
					String url = UUID.randomUUID().toString();
					String domain = UUID.randomUUID().toString();
					WebResponse dummy =  new WebResponse(url);
					dummy.setDomain(domain);
					indexDoc(dummy);
					delete(domain, url);
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

		synchronized (this) {
			if (indexWriter != null && indexWriter.isOpen())
				return indexWriter;

			// The current writer is broken (tragic event) or was never created. Close it first
			// so its NativeFSLock is released from the JVM's static lock set; otherwise reopening
			// throws "LockObtainFailedException: Lock held by this virtual machine" indefinitely.
			if (indexWriter != null) {
				try {
					indexWriter.close();
				} catch (Throwable t) {
					logger.warn("Error closing stale IndexWriter before reopen (lock may be released on close): {}", t.getMessage());
				}
				indexWriter = null;
			}

			logger.debug("(re)opening Lucene IndexWriter...");

			// open the Directory once and reuse it - a fresh Directory per reopen leaks stale locks
			if (directory == null)
				directory = FSDirectory.open(Paths.get(INDEX_PATH));

			IndexWriterConfig iwc = buildIndexWriterConfig();

			try {
				indexWriter = new IndexWriter(directory, iwc);
				logger.info("IndexWriter initialized");
			} catch (IOException e) {
				// reopen failed (e.g. a genuinely wedged lock we can't reclaim in-process).
				// Log at most once per interval instead of a per-request stack-trace flood,
				// and surface an actionable message. Recovery requires a JVM restart.
				long now = System.currentTimeMillis();
				if (now - lastReopenFailureLogMillis > REOPEN_FAILURE_LOG_INTERVAL_MILLIS) {
					lastReopenFailureLogMillis = now;
					logger.error("L2 Lucene IndexWriter is unavailable - cache reads/writes to L2 are failing. "
							+ "If this persists, restart the Frontcache server (index dir: {}). Cause: {}",
							INDEX_PATH, e.toString());
				}
				throw e;
			}
		}

		return indexWriter;
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
	 * top-level folder named after the backup so unzipping yields a ready-to-use index directory.
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

		Path indexDir = Paths.get(INDEX_PATH).toAbsolutePath();
		String backupName = "l2-lucene-index.backup-" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		String archiveName = backupName + ".zip";
		Path archiveFile = indexDir.getParent().resolve(archiveName);

		IndexCommit commit = snapshotPolicy.snapshot();
		try (OutputStream fos = Files.newOutputStream(archiveFile);
				ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

			for (String fileName : commit.getFileNames()) {
				zos.putNextEntry(new ZipEntry(backupName + "/" + fileName));
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

		doc.add(new StringField(DOMAIN_FIELD, response.getDomain(), Field.Store.YES));

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
			logger.error("Error while in Lucene index operation: {}", e.getMessage(), e);

		} finally {
			try {
				iWriter.commit();
			} catch (IOException ioEx) {
				logger.error("Error while commiting changes to Lucene index: {}", ioEx.getMessage(), ioEx);
			}
		}

	}

	public void close() {
		if (indexWriter != null && indexWriter.isOpen()) {
			try {
				indexWriter.close();
			} catch (Exception e) {
				logger.error("Error:", e);
			}
		}
		indexWriter = null;

		if (directory != null) {
			try {
				directory.close();
			} catch (Exception e) {
				logger.error("Error closing Lucene directory:", e);
			}
			directory = null;
		}
	}

	/**
	 * Returns document based on url
	 */
	private Document getDocByURL(String url) throws IOException, ParseException {

		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return null;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return null;
		}

		Document doc = null;
		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(iWriter);

			IndexSearcher searcher = new IndexSearcher(reader);

			Term term = new Term(URL_FIELD, toPathKey(url));
			Query query = new TermQuery(term);

			TopDocs results = searcher.search(query, 1);

			if (results.scoreDocs != null) {
				if (results.scoreDocs.length == 1) {
					doc = searcher.doc(results.scoreDocs[0].doc);
				}
			}
		} finally {
			if (reader != null) {
				reader.close();
			}
		}

		return doc;
	}


	public long getDocumentsCount(String domain) {

		long count = -1;

		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return count;
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return count;
		}

		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(iWriter);
			Term domainTerm = new Term(DOMAIN_FIELD, domain);
			IndexSearcher searcher = new IndexSearcher(reader);
			TermStatistics termStat = searcher.termStatistics(domainTerm, TermStates.build(searcher.getIndexReader().getContext(), domainTerm, true));
			count = termStat.docFreq();
		} catch (Exception e1) {
			logger.debug("Error during reader.totalTermFreq(domainTerm). " + e1.getMessage());
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		return count;
	}

	/**
	 * Removes documents by url or tags
	 * @param urlOrTag
	 */
	public void delete(String domain, String urlOrTag) {

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
//			Query domainQuery = new TermQuery(new Term(DOMAIN_FIELD, domain));
			Query urlQuery = new TermQuery(new Term(URL_FIELD, toPathKey(urlOrTag)));
			Query tagsQuery = new TermQuery(new Term(TAGS_FIELD, urlOrTag));

			BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

//			booleanQuery.add(domainQuery, Occur.MUST);
			booleanQuery.add(urlQuery, Occur.SHOULD);
			booleanQuery.add(tagsQuery, Occur.SHOULD);

			long count = iWriter.deleteDocuments(booleanQuery.build());
			logger.debug("Removed  {} documents for {}.", count, urlOrTag);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		} finally {
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}

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
			logger.error(e.getMessage(), e);
		} finally {
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}

	/**
	 * Removes documents by url or tags
	 * @param domain
	 */
	public void deleteAll(String domain) {

		if (null == domain)
		{
			logger.error("Cant delete all with null domain");
			return;
		}

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
			logger.debug("Removing all documents for {}.", domain);

			Query domainQuery = new TermQuery(new Term(DOMAIN_FIELD, domain));

			long count = iWriter.deleteDocuments(domainQuery);
			logger.debug("Removed  {} documents for {}.", count, domain);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		} finally {
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
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
		IndexReader reader = null;
		try {
			// expired = expire_date in [MIN_VALUE, now] ; forever entries are Long.MAX_VALUE -> excluded
			long now = System.currentTimeMillis();
			Query expiredQuery = LongPoint.newRangeQuery(EXPIRE_DATE_FIELD, Long.MIN_VALUE, now);

			// count matches first so we can report how many were purged
			reader = DirectoryReader.open(iWriter);
			IndexSearcher searcher = new IndexSearcher(reader);
			count = searcher.count(expiredQuery);

			iWriter.deleteDocuments(expiredQuery);
			logger.info("deleteExpired() removed {} expired documents", count);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			}
			try {
				iWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
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
		IndexWriter iWriter = null;
		try {
			iWriter = getIndexWriter();
			if (iWriter == null){
				return Collections.emptyList();
			}
		} catch (Exception e1) {
			logger.debug("Error during getting indexWriter. " + e1.getMessage());
			return Collections.emptyList();
		}

		List<String> keys = new ArrayList<String>();

		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(iWriter);
			IndexSearcher searcher = new IndexSearcher(reader);

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
					Document doc = reader.document(hit.doc, urlFieldOnly);
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
			logger.error("Error during loading urls/keys from index", e);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					logger.error("Error closing reader", e);
				}
			}
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
					response.setContent(bin1ref.bytes);
			}

			return response;
		} catch (Exception e) {
			logger.error("Error during loading data from index", e);
		}
		return null;
	}

	/**
	 * Returns index size
	 * @return
	 */
	public int getIndexSize(){
		int n = -1;
		IndexReader reader = null;
		try {

			Directory dir = FSDirectory.open(Paths.get(INDEX_PATH));
			reader = DirectoryReader.open(dir);
			n = reader.numDocs();

		} catch (IOException e) {

			logger.error(e.getMessage(), e);
		} finally {

			if (null != reader)
			{
				try {
					reader.close();
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			}
		}

		return n;
	}


}
