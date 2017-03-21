/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
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
import static org.junit.Assert.assertNull;

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
	
	private final static String COINSHOME_DOMAIN = "coinshome.net";
	
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

		String url = "https://www.coinshome.net/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm";

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

	
	@Before
	public void setUp() {
		luceneIndexManager = new LuceneIndexManager("/tmp/lucene-text-index-l2-" + System.currentTimeMillis()); // INDEX_BASE_DIR
		return;
	}

	
	@After
	public void cleanUp() {
		luceneIndexManager.close();
		return;
	}

	
}
