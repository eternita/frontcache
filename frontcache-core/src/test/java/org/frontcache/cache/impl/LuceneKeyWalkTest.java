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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link LuceneIndexManager#forEachLiveKey}: keys come from the url terms dictionary, and a deleted
 * document keeps its term until its segment is merged away. {@code NoMergePolicy} keeps it there, so
 * this is where the live-docs check is actually exercised - remove it and the deleted keys come back.
 */
public class LuceneKeyWalkTest {

	private Directory directory;
	private IndexWriter writer;

	@Before
	public void setUp() throws Exception {
		directory = new ByteBuffersDirectory();
		IndexWriterConfig iwc = new IndexWriterConfig(new StandardAnalyzer());
		iwc.setMergePolicy(NoMergePolicy.INSTANCE);
		writer = new IndexWriter(directory, iwc);
	}

	@After
	public void tearDown() throws Exception {
		writer.close();
		directory.close();
	}

	/** A document shaped like {@code indexDoc}'s: the key indexed and stored, and a body beside it. */
	private void index(String key) throws Exception {
		Document doc = new Document();
		doc.add(new StringField(LuceneIndexManager.URL_FIELD, key, Field.Store.YES));
		doc.add(new StoredField(LuceneIndexManager.BIN_FIELD, ("body of " + key).getBytes()));
		writer.updateDocument(new Term(LuceneIndexManager.URL_FIELD, key), doc);
	}

	private List<String> walk(int limit) throws Exception {
		return walk(null, null, limit);
	}

	private List<String> walk(String prefix, String after, int limit) throws Exception {
		List<String> keys = new ArrayList<String>();
		try (DirectoryReader reader = DirectoryReader.open(writer)) {
			long count = LuceneIndexManager.forEachLiveKey(reader, prefix, after, limit, keys::add);
			assertEquals(keys.size(), count);
		}
		return keys;
	}

	@Test
	public void deletedAndReplacedDocumentsAreNotListed() throws Exception {
		index("/a.html");
		index("/b.html");
		index("/c.html");
		writer.commit(); // one segment holding all three

		writer.deleteDocuments(new Term(LuceneIndexManager.URL_FIELD, "/b.html"));
		index("/c.html"); // replaced: its first document is deleted, its term shared with the new one

		try (DirectoryReader reader = DirectoryReader.open(writer)) {
			assertTrue("premise: the deleted documents must still be in their segment",
					reader.maxDoc() > reader.numDocs());
		}

		assertEquals(Arrays.asList("/a.html", "/c.html"), walk(Integer.MAX_VALUE));
	}

	@Test
	public void keysAreListedInOrderAndStopAtTheLimit() throws Exception {
		index("/c.html");
		index("/a.html");
		writer.commit();
		index("/b.html"); // a second segment: the order holds across segments

		assertEquals(Arrays.asList("/a.html", "/b.html", "/c.html"), walk(Integer.MAX_VALUE));
		assertEquals(Arrays.asList("/a.html", "/b.html"), walk(2));
	}

	@Test
	public void anEmptyIndexListsNothing() throws Exception {
		assertEquals(Arrays.<String>asList(), walk(Integer.MAX_VALUE));
	}

	private void indexAll(String... keys) throws Exception {
		for (String key : keys)
			index(key);
		writer.commit();
	}

	@Test
	public void aPrefixListsOnlyItsKeys() throws Exception {
		indexAll("/a/1", "/p/1", "/p/2", "/pq/1", "/q/1");

		assertEquals(Arrays.asList("/p/1", "/p/2"), walk("/p/", null, Integer.MAX_VALUE));
		assertEquals("a prefix is bytes, not a path segment", Arrays.asList("/p/1", "/p/2", "/pq/1"),
				walk("/p", null, Integer.MAX_VALUE));
		assertEquals(Arrays.<String>asList(), walk("/z", null, Integer.MAX_VALUE));
	}

	@Test
	public void theCursorResumesAfterItselfWhetherOrNotItIsStillAKey() throws Exception {
		indexAll("/a", "/b", "/c", "/d");

		assertEquals(Arrays.asList("/c", "/d"), walk(null, "/b", Integer.MAX_VALUE));

		writer.deleteDocuments(new Term(LuceneIndexManager.URL_FIELD, "/b")); // invalidated between pages
		assertEquals(Arrays.asList("/c", "/d"), walk(null, "/b", Integer.MAX_VALUE));
		assertEquals("a cursor between keys", Arrays.asList("/c", "/d"), walk(null, "/bb", Integer.MAX_VALUE));
		assertEquals(Arrays.<String>asList(), walk(null, "/d", Integer.MAX_VALUE));
	}

	@Test
	public void aCursorAndAPrefixTogether() throws Exception {
		indexAll("/a/1", "/p/1", "/p/2", "/p/3", "/q/1");

		assertEquals("cursor before the prefix: start at the prefix", Arrays.asList("/p/1", "/p/2", "/p/3"),
				walk("/p/", "/a/1", Integer.MAX_VALUE));
		assertEquals(Arrays.asList("/p/3"), walk("/p/", "/p/2", Integer.MAX_VALUE));
		assertEquals("cursor past the prefix", Arrays.<String>asList(), walk("/p/", "/q/0", Integer.MAX_VALUE));
	}

	/** Paging a listing by its own last key visits every key once. */
	@Test
	public void pagingByTheLastKeyVisitsEveryKeyOnce() throws Exception {
		List<String> all = new ArrayList<String>();
		for (int i = 0; i < 25; i++)
			all.add(String.format("/k/%03d", i));
		indexAll(all.toArray(new String[0]));

		List<String> seen = new ArrayList<String>();
		String after = null;
		List<String> page;
		do {
			page = walk("/k/", after, 7);
			seen.addAll(page);
			after = page.isEmpty() ? after : page.get(page.size() - 1);
		} while (page.size() == 7);

		assertEquals(all, seen);
	}

	@Test
	public void aSinkAnsweringFalseStopsTheWalk() throws Exception {
		indexAll("/a", "/b", "/c");

		List<String> keys = new ArrayList<String>();
		try (DirectoryReader reader = DirectoryReader.open(writer)) {
			long offered = LuceneIndexManager.forEachLiveKey(reader, null, null, Integer.MAX_VALUE,
					key -> keys.add(key) && keys.size() < 2);
			assertEquals(2, offered);
		}
		assertEquals(Arrays.asList("/a", "/b"), keys);
	}
}
