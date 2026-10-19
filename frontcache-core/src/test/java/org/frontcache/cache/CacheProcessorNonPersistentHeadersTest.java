/*
 * Copyright 2015 Eternita
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
package org.frontcache.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Properties;

import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.cache.impl.L1L2CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Test;

/**
 * Headers that must never survive a cache write - Set-Cookie above all. Caching a Set-Cookie would
 * replay the visitor's session cookie to every later visitor of the same URL, so the strip is a
 * security invariant, not a tidiness one.
 *
 * The strip lives in CacheProcessorBase.putToCache() so that no write path can skip it: the origin
 * miss, the soft-refresh, the admin API (FrontCacheIOServlet) and replication all go through there.
 * These tests therefore drive putToCache() directly rather than a request flow.
 */
public class CacheProcessorNonPersistentHeadersTest {

	private static final String URL = "http://test.example.com/cookie/page.html";

	private CacheProcessor cacheProcessor;

	@After
	public void tearDown() {
		if (null != cacheProcessor)
			cacheProcessor.destroy();
	}

	private CacheProcessor inMemory() {
		cacheProcessor = new InMemoryCacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M"); // without it nothing is cached
		cacheProcessor.init(props);
		return cacheProcessor;
	}

	private CacheProcessor l1l2() {
		cacheProcessor = new L1L2CacheProcessor();
		Properties props = new Properties();
		// throwaway L2 index dir so the test doesn't need FRONTCACHE_HOME
		props.setProperty("front-cache.cache-processor.impl.cache-dir",
				"/tmp/cookie-strip-test-" + System.currentTimeMillis() + "/");
		cacheProcessor.init(props);
		return cacheProcessor;
	}

	/** A response that passes the cacheability checks, carrying a cookie and a header that must persist. */
	private WebResponse responseWithCookie(String cookieHeaderName) {
		WebResponse response = new WebResponse(URL, "content".getBytes(), "30d", null);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		response.addHeader(cookieHeaderName, "JSESSIONID=1A2B3C; Path=/; HttpOnly");
		return response;
	}

	private static String cookieOf(WebResponse response) {
		for (String name : response.getHeaders().keySet())
			if ("set-cookie".equals(name.toLowerCase()))
				return response.getHeaders().get(name).toString();

		return null;
	}

	@Test
	public void setCookieIsStrippedByL1() {
		CacheProcessor cache = l1l2();
		WebResponse response = responseWithCookie("Set-Cookie");
		response.setCacheLevel(FCHeaders.CACHE_LEVEL_L1); // Ehcache tier

		cache.putToCache(URL, response);

		WebResponse cached = ((L1L2CacheProcessor) cache).getFromCacheImpl(URL);
		assertNotNull("entry should be cached - a cookie does not make a response uncacheable", cached);
		assertNull("Set-Cookie must not be stored in cache", cookieOf(cached));
		assertEquals("other headers must survive", "text/html", cached.getHeader(FCHeaders.CONTENT_TYPE));
	}

	@Test
	public void setCookieIsStrippedByL2() {
		CacheProcessor cache = l1l2();

		cache.putToCache(URL, responseWithCookie("Set-Cookie")); // default level -> Lucene

		WebResponse cached = ((L1L2CacheProcessor) cache).getFromCacheImpl(URL);
		assertNotNull("entry should be cached - a cookie does not make a response uncacheable", cached);
		assertNull("Set-Cookie must not be persisted to the L2 index", cookieOf(cached));
	}

	@Test
	public void setCookieIsStrippedByInMemoryCache() {
		CacheProcessor cache = inMemory();

		cache.putToCache(URL, responseWithCookie("Set-Cookie"));

		WebResponse cached = ((InMemoryCacheProcessor) cache).getFromCacheImpl(URL);
		assertNotNull(cached);
		assertNull("Set-Cookie must not be stored in cache", cookieOf(cached));
	}

	/**
	 * HTTP header names are case-insensitive and intermediaries re-case them, so a cookie arriving
	 * as "set-cookie" must be stripped just the same.
	 */
	@Test
	public void reCasedSetCookieIsStripped() {
		CacheProcessor cache = inMemory();

		cache.putToCache(URL, responseWithCookie("set-cookie"));

		WebResponse cached = ((InMemoryCacheProcessor) cache).getFromCacheImpl(URL);
		assertNotNull(cached);
		assertNull("the strip must be case-insensitive", cookieOf(cached));
	}
}
