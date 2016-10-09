package org.frontcache.cache.impl.file;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
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
public class IndexManager {

	private static final Logger logger = LoggerFactory.getLogger(IndexManager.class);

	private static String INDEX_PATH;
	
	public static final String JSON_FIELD = "json";
	public static final String BIN_FIELD = "bin";

	// searchable fields
	public static final String TAGS_FIELD = "tags"; 
	public static final String HASH_FIELD = "hash"; 
	
	private IndexWriter indexWriter = null;
	
	private final StandardAnalyzer analyzer = new StandardAnalyzer();

	public final static FieldType TYPE;
	static {
	    TYPE = new FieldType();
	    TYPE.setStored(true);
	    TYPE.setIndexOptions(IndexOptions.NONE);
	    TYPE.setTokenized(false);
	    TYPE.freeze();
	}
	
	/**
	 *  
	 */
	final Gson gson = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {

		@Override
		public boolean shouldSkipField(FieldAttributes att) {
			if (att.getName().equalsIgnoreCase(TAGS_FIELD)) {
				return true;
			}
			return false;
		}

		@Override
		public boolean shouldSkipClass(Class<?> arg0) {
			return byte[].class.equals(arg0);
		}
	}).create();

	
	/**
	 * Constructor
	 * @param indexPath
	 */
	public IndexManager(String indexPath) {
		INDEX_PATH = indexPath;
		Path path = Paths.get(INDEX_PATH);
		if (!Files.exists(path)) {
			try {
				Files.createDirectories(path.getParent());
				// create dummy index
				indexDoc(new WebResponse(UUID.randomUUID().toString()));
			} catch (IOException e) {
				logger.error("Error during creating cache-file", e);
			}
		} else {
			try {
				indexWriter = getIndexWriter();
			} catch (IOException e) {
				logger.error("Error during creating indexWriter", e);
			}
		}
	}

	static String getHash(String url) {
		return DigestUtils.md5Hex(url);
	}

	/**
	 * Returns instance of IndexManager
	 * @param create
	 * @return
	 * @throws IOException
	 */
	private IndexWriter getIndexWriter() throws IOException {
		if (indexWriter == null) {
			synchronized (IndexWriter.class) {

				Directory dir = FSDirectory.open(Paths.get(INDEX_PATH));
				Analyzer analyzer = new StandardAnalyzer();
				IndexWriterConfig iwc = new IndexWriterConfig(analyzer);

				iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
				iwc.setRAMBufferSizeMB(250.0);
				indexWriter = new IndexWriter(dir, iwc);
			}
		}

		return indexWriter;
	}

	/**
	 * Writes webResponse to index
	 * @param response
	 * @throws IOException
	 */
	void indexDoc(WebResponse response) throws IOException {

		IndexWriter iWriter = getIndexWriter();

		Document doc = new Document();

		String hash = getHash(response.getUrl());

		doc.add(new StringField(HASH_FIELD, hash, Field.Store.YES));
		if (null != response.getContent())
			doc.add(new StoredField(BIN_FIELD, response.getContent()));
		doc.add(new StoredField(JSON_FIELD, gson.toJson(response), TYPE));
		doc.add(new TextField(TAGS_FIELD, new StringReader(Arrays.toString(response.getTags().toArray()))));

		try {
			iWriter.updateDocument(new Term(HASH_FIELD, hash), doc);
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
			} catch (IOException e) {
				logger.error("Error:", e);
			}
		}
	}

	public void truncate() {
		try {
			indexWriter.deleteAll();
			logger.warn("lucene index truncated");
		} catch (IOException ioEx) {
			logger.error("Error truncating lucene index: {}", ioEx.getMessage(), ioEx);
		} finally {
			try {
				indexWriter.commit();
			} catch (IOException ioEx) {
				logger.error("Error truncating lucene index: {}", ioEx.getMessage(), ioEx);
			}
		}
	}

	/**
	 * Returns document based on url's hash
	 * @param hash md5 hash
	 * @return Lucene document
	 * @throws IOException
	 * @throws ParseException
	 */
	public Document getDocByHash(String hash) throws IOException, ParseException {

		Document doc = null;

		QueryParser parser = new QueryParser(HASH_FIELD, analyzer);
		IndexReader reader = null;
		try {
			reader = DirectoryReader.open(indexWriter);

			IndexSearcher searcher = new IndexSearcher(reader);

			Query query = parser.parse(hash);

			TopDocs results = searcher.search(query, 2);

			if (results.scoreDocs != null) {
				if (results.scoreDocs.length == 2) {
					deleteByHash(hash);
					return null;
				} else if (results.scoreDocs.length == 1) {
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

	/**
	 * Removes document by url
	 * @param url
	 */
	public void deleteByUrl(String url) {
		deleteBy(HASH_FIELD, getHash(url));
	}

	/**
	 * Removes documents with given hash
	 * @param hash
	 */
	private void deleteByHash(String hash) {
		deleteBy(HASH_FIELD, hash);
	}

	/**
	 * Removes documents by tag
	 * @param tag
	 */
	public void deleteByTag(String tag) {
		deleteBy(TAGS_FIELD, tag);
	}

	/**
	 * Removes documents by field
	 * @param field
	 * @param tag
	 */
	private void deleteBy(String field, String tag) {

		QueryParser parser = new QueryParser(field, analyzer);

		try {
			Query query = parser.parse(tag);
			long count = indexWriter.deleteDocuments(query);
			logger.debug("Removed  {} documents for {}.", count, tag);
		} catch (IOException | ParseException e) {
			logger.error(e.getMessage(), e);
		} finally {
			try {
				indexWriter.commit();
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		}
	}

	/**
	 * Gets response from index
	 * @param url request url
	 * @return WebResponse from index
	 */
	public WebResponse getResponse(String url) {
		String hash = getHash(url);
		WebResponse response = null;
		try {
			Document doc = getDocByHash(hash);
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
		try {
			IndexReader reader = DirectoryReader.open(indexWriter);
			n = reader.numDocs();
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}
		return n;
	}
	

}
