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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneIndexTests {

	protected Logger logger = LoggerFactory.getLogger(LuceneIndexTests.class);
	LuceneCacheProcessor pr = null;
	private final static String DOMAIN = "test-domain";

	@Test
	public void fileSaveTest() throws Exception {
		String[] tags = { "apple", "limon", "banana", "chery", "peach" };
		int size = 5;
		for (int i = 0; i < size; i++) {
			String url = UUID.randomUUID().toString();
			String content = UUID.randomUUID().toString();
			pr.removeFromCache(DOMAIN, url);

			WebResponse response = new WebResponse(url, content.getBytes());
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> tagSet = new HashSet<>();
			tagSet.add(tags[i]);
			response.setTags(tagSet);
			response.setStatusCode(55);
			pr.putToCache(DOMAIN, url, response);

			WebResponse fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
			assertEquals(tagSet, fromFile.getTags()); // tags are restorable from index
		}

		assertEquals(size, pr.getIndexManager().getIndexSize());
		pr.getIndexManager().delete(DOMAIN, "apple");
		assertEquals(size - 1, pr.getIndexManager().getIndexSize());
		assertEquals(size - 1 + "", pr.getCacheStatus().get(CacheProcessor.CACHED_ENTRIES));

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
			pr.putToCache(DOMAIN, url, response);

			WebResponse fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
		}

		assertEquals(size, pr.getIndexManager().getIndexSize());
		pr.getIndexManager().delete(DOMAIN, "apple");
		assertEquals(size - 1, pr.getIndexManager().getIndexSize());
		assertEquals(size - 1 + "", pr.getCacheStatus().get(CacheProcessor.CACHED_ENTRIES));

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
			pr.putToCache(DOMAIN, url, response);

			WebResponse fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			assertEquals(url, fromFile.getUrl());
			assertEquals(content, new String(fromFile.getContent()));
			assertEquals(response.getHeaders(), fromFile.getHeaders());
			assertEquals(response.getContentLenth(), fromFile.getContentLenth());
			assertEquals(fromFile.getStatusCode(), response.getStatusCode());
		}

		for (String url : urls.keySet()) {
			WebResponse fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			assertEquals(urls.get(url), new String(fromFile.getContent()));
			pr.getIndexManager().delete(DOMAIN, url);
			fromFile = pr.getFromCacheImpl(url);
			assertNull(fromFile);
		}

	}
	
	@Test
	public void fileRemoveByTag() throws Exception {

		String url = "https://www.coinshome.net/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm";

		pr.removeFromCache(DOMAIN, url);
		
		WebResponse response = new WebResponse(url, "data".getBytes());
		response.addTags(Arrays.asList(new String[]{"E9AKbzbiOBIAAAFG0vnZjkvL"}));
		
		pr.putToCache(DOMAIN, url, response);
		
		WebResponse fromFile = pr.getFromCache(url);
		assertEquals(url, fromFile.getUrl());
		assertEquals(new String("data".getBytes()), new String(fromFile.getContent()));
		
		pr.getIndexManager().delete(DOMAIN, "E9AKbzbiOBIAAAFG0vnZjkvL");
		
		fromFile = pr.getFromCache(url);
		assertNull(fromFile);
		
	}
	
	@Test
	public void fileEmptyByteTest() throws Exception {

			String url = UUID.randomUUID().toString();
			pr.removeFromCache(DOMAIN, url);

			WebResponse response = new WebResponse(url, null);
			List<String> list = new ArrayList<>();
			list.add(UUID.randomUUID().toString());
			response.getHeaders().put("Accept", list);
			Set<String> set = new HashSet<>();
			set.add("banana");
			response.setTags(set);
			response.setStatusCode(55);
			pr.putToCache(DOMAIN, url, response);

			WebResponse fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			
			response.setContent(new byte[0]);
			pr.putToCache(DOMAIN, url, response);
			
			fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			
			response.setContent(new byte[]{'a'});
			pr.putToCache(DOMAIN, url, response);
			
			fromFile = pr.getFromCacheImpl(url);
			assertNotNull(fromFile);
			
	}

	@Test

	public void multyWritersTest() throws Exception {

		LuceneCacheProcessor pr1 = new LuceneCacheProcessor();
		Properties prop = new Properties();
		prop.put("front-cache.cache-processor.impl.cache-dir", "/tmp/cache/");
		pr1.init(prop);


		System.out.println("hello");
		String url = UUID.randomUUID().toString();
		pr.removeFromCache(DOMAIN, url);

		WebResponse response = new WebResponse(url, null);
		List<String> list = new ArrayList<>();
		list.add(UUID.randomUUID().toString());
		response.getHeaders().put("Accept", list);
		Set<String> set = new HashSet<>();
		set.add("banana");
		response.setTags(set);
		response.setStatusCode(55);
		pr1.putToCache(DOMAIN, url, response);

		pr.destroy();
		
		pr1.putToCache(DOMAIN, url, response);
		
		// now second writer should have IndexWriter
		WebResponse fromFile = pr1.getFromCacheImpl(url);
		assertNotNull(fromFile);

		pr1.destroy();

	}
	
	
	@Before
	public void setUp() {
		pr = new LuceneCacheProcessor();
		Properties prop = new Properties();
		prop.put("front-cache.cache-processor.impl.cache-dir", "/tmp/cache/");
		pr.init(prop);
		pr.removeFromCacheAll(DOMAIN);
	}

	@After
	public void cleanUp() {
		pr.removeFromCacheAll(DOMAIN);
		pr.destroy();

	}

}
