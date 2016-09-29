package org.frontcache.cache.impl.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class IndexManager {

	private static final Logger logger = LoggerFactory.getLogger(IndexManager.class);

	private static String INDEX_PATH;

	private IndexWriter indexWriter = null;
	private final StandardAnalyzer analyzer = new StandardAnalyzer();

	final Gson gson = new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy() {

		@Override
		public boolean shouldSkipField(FieldAttributes att) {
			if (att.getName().equalsIgnoreCase("tags")) {
				return true;
			}
			return false;
		}

		@Override
		public boolean shouldSkipClass(Class<?> arg0) {
			return byte[].class.equals(arg0);
		}
	}).create();

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
				indexWriter = getIndexWriter(false);
			} catch (IOException e) {
				logger.error("Error during creating indexWriter", e);
			}
		}

	}

	static String getHash(String url) {
		return DigestUtils.md5Hex(url);
	}

	private IndexWriter getIndexWriter(boolean create) throws IOException {
		if (indexWriter == null) {
			Directory dir = FSDirectory.open(Paths.get(INDEX_PATH));
			Analyzer analyzer = new StandardAnalyzer();
			IndexWriterConfig iwc = new IndexWriterConfig(analyzer);

			if (create) {
				iwc.setOpenMode(OpenMode.CREATE);
			} else {
				iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
			}
			indexWriter = new IndexWriter(dir, iwc);
		}

		return indexWriter;
	}

	void indexDoc(WebResponse response) throws IOException {

		IndexWriter iWriter = getIndexWriter(false);

		Document doc = new Document();

		String hash = getHash(response.getUrl());

		Field hashField = new StringField("hash", hash, Field.Store.YES);
		doc.add(hashField);
		
		doc.add(new StoredField("bin", response.getContent()));

		Field pathField = new StringField("url", response.getUrl(), Field.Store.YES);
		doc.add(pathField);

		Field jsonF = new StringField("json", gson.toJson(response), Field.Store.YES);
		doc.add(jsonF);

		doc.add(new TextField("contents", new StringReader(
				Arrays.toString(Optional.ofNullable(response.getTags()).orElse(Collections.EMPTY_SET).toArray()))));

		try {
			if (iWriter.getConfig().getOpenMode() == OpenMode.CREATE) {
				iWriter.addDocument(doc);
			} else {
				iWriter.updateDocument(new Term("hash", hash), doc);
			}
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

	static void indexDocs(final IndexWriter writer, Path path) throws IOException {
		if (Files.isDirectory(path)) {
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					try {
						indexDoc(writer, file, attrs.lastModifiedTime().toMillis());
					} catch (IOException ignore) {
						// don't index files that can't be read.
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			indexDoc(writer, path, Files.getLastModifiedTime(path).toMillis());
		}
	}

	static void indexDoc(IndexWriter writer, Path file, long lastModified) throws IOException {
		try (InputStream stream = Files.newInputStream(file)) {
			// make a new, empty document
			Document doc = new Document();

			// Add the path of the file as a field named "path". Use a
			// field that is indexed (i.e. searchable), but don't tokenize
			// the field into separate words and don't index term frequency
			// or positional information:
			Field pathField = new StringField("path", file.toString(), Field.Store.YES);
			doc.add(pathField);

			// Add the last modified date of the file a field named "modified".
			// Use a LongPoint that is indexed (i.e. efficiently filterable with
			// PointRangeQuery). This indexes to milli-second resolution, which
			// is often too fine. You could instead create a number based on
			// year/month/day/hour/minutes/seconds, down the resolution you
			// require.
			// For example the long value 2011021714 would mean
			// February 17, 2011, 2-3 PM.
			doc.add(new LongPoint("modified", lastModified));

			// Add the contents of the file to a field named "contents". Specify
			// a Reader,
			// so that the text of the file is tokenized and indexed, but not
			// stored.
			// Note that FileReader expects the file to be in UTF-8 encoding.
			// If that's not the case searching for special characters will
			// fail.
			doc.add(new TextField("contents",
					new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))));

			if (writer.getConfig().getOpenMode() == OpenMode.CREATE) {
				// New index, so we just add the document (no old document can
				// be there):
				System.out.println("adding " + file);
				writer.addDocument(doc);
			} else {
				// Existing index (an old copy of this document may have been
				// indexed) so
				// we use updateDocument instead to replace the old one matching
				// the exact
				// path, if present:
				System.out.println("updating " + file);
				writer.updateDocument(new Term("path", file.toString()), doc);
			}
		}
	}

	// public void rebuidIndex(String docsPath, boolean create){
	// docsPath = Optional.ofNullable(docsPath).orElse(FILES_PATH);
	//
	// final Path docDir = Paths.get(docsPath);
	// if (!Files.isReadable(docDir)) {
	// System.out.println("Document directory '" +docDir.toAbsolutePath()+ "'
	// does not exist or is not readable, please check the path");
	// System.exit(1);
	// }
	//
	// Date start = new Date();
	//
	// try {
	// System.out.println("Indexing to directory '" + INDEX_PATH + "'...");
	//
	//
	//
	// IndexWriter writer = getIndexWriter(create);
	//
	// indexDocs(writer, docDir);
	//
	// // NOTE: if you want to maximize search performance,
	// // you can optionally call forceMerge here. This can be
	// // a terribly costly operation, so generally it's only
	// // worth it when your index is relatively static (ie
	// // you're done adding documents to it):
	// //
	// // writer.forceMerge(1);
	//
	// writer.close();
	//
	// Date end = new Date();
	// System.out.println(end.getTime() - start.getTime() + " total
	// milliseconds");
	//
	// } catch (IOException e) {
	// System.out.println(" caught a " + e.getClass() +
	// "\n with message: " + e.getMessage());
	// }
	// }

	// public static void searchDocs(String field, String params) throws
	// Exception {
	//
	// IndexReader reader =
	// DirectoryReader.open(MMapDirectory.open(Paths.get(INDEX_PATH)));
	//
	// IndexSearcher searcher = new IndexSearcher(reader);
	// StandardAnalyzer analyzer = new StandardAnalyzer();
	//
	// String index = INDEX_PATH;
	// String field = "contents";
	// boolean raw = false;
	//
	// int hitsPerPage = 10;
	//
	//
	//
	//// IndexReader reader =
	// DirectoryReader.open(FSDirectory.open(Paths.get(index)));
	//// IndexSearcher searcher = new IndexSearcher(reader);
	//// Analyzer analyzer = new StandardAnalyzer();
	//
	// BufferedReader in = null;
	//
	// QueryParser parser = new QueryParser(field, analyzer);
	//
	// Query query = parser.parse(params);
	// System.out.println("Searching for: " + query.toString(field));
	//
	//
	// doPagingSearch(in, searcher, query, hitsPerPage, raw, false);
	//
	// reader.close();
	// }
	
	public Document searchDocByHash(String hash) throws IOException, ParseException {
		IndexReader reader = DirectoryReader.open(indexWriter);

		IndexSearcher searcher = new IndexSearcher(reader);
		String field = "hash";

		QueryParser parser = new QueryParser(field, analyzer);

		Query query = parser.parse(hash);

		TopDocs results = searcher.search(query, 2);
		Document doc = null;
		if (results.scoreDocs != null && results.scoreDocs.length > 0) {
			doc = searcher.doc(results.scoreDocs[0].doc);
		}

		reader.close();
		return doc;
	}

	public void searchByTag(String tag, Consumer<String> hashConsumer) throws IOException, ParseException {

		IndexReader reader = DirectoryReader.open(indexWriter);
		int hitsPerPage = 10;
		IndexSearcher searcher = new IndexSearcher(reader);
		String field = "contents";

		QueryParser parser = new QueryParser(field, analyzer);

		Query query = parser.parse(tag);

		TopDocs results = searcher.search(query, 5 * hitsPerPage);
		ScoreDoc[] hits = results.scoreDocs;

		int numTotalHits = results.totalHits;
		System.out.println(numTotalHits + " total matching documents");

		int start = 0;
		int end = Math.min(numTotalHits, hitsPerPage);

		for (int i = start; i < end; i++) {

			Document doc = searcher.doc(hits[i].doc);
			String hash = doc.get("hash");
			if (hash != null) {
				System.out.println((i + 1) + ". " + hash);
				hashConsumer.accept(hash);
			} 

		}

	}

	public WebResponse getBaseResponse(String url) {
		String hash = getHash(url);
		try {
			Document doc = searchDocByHash(hash);
			WebResponse response = gson.fromJson(doc.get("json"), WebResponse.class);
			
			// get binary from lucene
			BytesRef bin1ref = doc.getBinaryValue("bin");
			response.setContent(bin1ref.bytes);
			
			return response;
		} catch (Exception e) {
			logger.error("Error during removing file", e);

		}
		return new WebResponse(url);
	}

}
