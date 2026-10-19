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
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.frontcache.cache.CacheProcessor;
import org.junit.Test;

/**
 * A combined part's headers must mean exactly what a single include's response headers mean.
 *
 * This is the readable half of the invariant that a combined member is cached exactly as the same include
 * fetched alone - the other half, the cache KEY, is the e2e suite's job. If a part's maxAge, tags, cache
 * level or content type were read even slightly differently from a single include's, the two paths would
 * write entries that disagree about when they expire and what invalidates them, and nothing would throw.
 *
 * {@link FCUtils#buildWebComponent} exists so there is only one reading. These pin that it is the
 * same one.
 */
public class CombinePartMappingTest {

	private static final String URL = "http://www.example.com:8080/fc/search-item.htm?id=1";

	private static Map<String, List<String>> headers(String... nameThenValue) {

		// case-insensitive, like every map FCUtils.revertHeaders builds
		Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
		for (int i = 0; i < nameThenValue.length; i += 2)
			headers.put(nameThenValue[i], Arrays.asList(new String[]{nameThenValue[i + 1]}));

		return headers;
	}

	private static WebResponse build(Map<String, List<String>> headers) {
		return FCUtils.buildWebComponent(URL, "<div>cell</div>".getBytes(StandardCharsets.UTF_8), headers);
	}

	@Test
	public void maxAgeBecomesAnExpirationForBothClientTypes() {

		long before = System.currentTimeMillis();
		WebResponse webResponse = build(headers(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "10m"));
		long after = System.currentTimeMillis();

		Map<String, Long> expireTimeMap = webResponse.getExpireTimeMap();

		for (String clientType : new String[]{FCHeaders.REQUEST_CLIENT_TYPE_BOT, FCHeaders.REQUEST_CLIENT_TYPE_GUEST})
		{
			long expireTime = expireTimeMap.get(clientType).longValue();
			assertTrue(clientType + " expires too early: " + expireTime, expireTime >= before + 10 * 60 * 1000);
			assertTrue(clientType + " expires too late: " + expireTime, expireTime <= after + 10 * 60 * 1000);
		}
	}

	@Test
	public void maxAgeForeverIsTheCacheForeverSentinel() {

		WebResponse webResponse = build(headers(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "forever"));

		assertEquals(CacheProcessor.CACHE_FOREVER, webResponse.getExpireTimeMillis());
	}

	/**
	 * No maxage means not cached - the SAME default a single include with no
	 * x-frontcache-component-maxage gets. A combined part is not special, and does not become
	 * cacheable just because the origin took the trouble to batch it.
	 *
	 * Note WHICH check answers that, because it is a trap: {@code isCacheable()} says <em>true</em>
	 * here. It only tests that the expireTimeMap is non-empty, and a maxage-less response has a full
	 * map of NO_CACHE. The condition that actually excludes it - and the one
	 * {@code CacheProcessorBase.processRequest} pairs isCacheable() with - is
	 * {@code isWebComponentCacheableForClientType}. Storing on isCacheable() alone would write an
	 * entry that is expired the instant it lands.
	 */
	@Test
	public void aPartWithNoMaxAgeIsNotCached() {

		WebResponse webResponse = build(headers(FCHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"));

		assertEquals(CacheProcessor.NO_CACHE, webResponse.getExpireTimeMillis());

		assertTrue("isCacheable() alone does NOT exclude it", webResponse.isCacheable());
		assertFalse(FCUtils.isWebComponentCacheableForClientType(
				webResponse.getExpireTimeMap(), FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	@Test
	public void aPartWithMaxAgeAndATextContentTypeIsCached() {

		WebResponse webResponse = build(headers(
				FCHeaders.CONTENT_TYPE, "text/html;charset=UTF-8",
				FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "10d"));

		assertTrue(webResponse.isCacheable());
		assertTrue(FCUtils.isWebComponentCacheableForClientType(
				webResponse.getExpireTimeMap(), FCHeaders.REQUEST_CLIENT_TYPE_GUEST));
	}

	@Test
	public void tagsAreSplitOnTheSeparator() {

		WebResponse webResponse = build(headers(
				FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "10d",
				FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, "coin|coin-42|country-ua"));

		assertEquals(new HashSet<String>(Arrays.asList("coin", "coin-42", "country-ua")), webResponse.getTags());
	}

	@Test
	public void cacheLevelAndRefreshTypeAreCarried() {

		WebResponse webResponse = build(headers(
				FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "1h",
				FCHeaders.X_FRONTCACHE_COMPONENT_CACHE_LEVEL, FCHeaders.CACHE_LEVEL_L1,
				FCHeaders.X_FRONTCACHE_COMPONENT_REFRESH_TYPE, FCHeaders.COMPONENT_REFRESH_TYPE_SOFT));

		assertEquals(FCHeaders.CACHE_LEVEL_L1, webResponse.getCacheLevel());
		assertEquals(FCHeaders.COMPONENT_REFRESH_TYPE_SOFT, webResponse.getRefreshType());
	}

	/**
	 * Header names are case-insensitive (RFC 7230) and an origin hand-writing JSON will spell them
	 * however it likes. The edge hands buildWebComponent a case-insensitive map for exactly this;
	 * without it, {@code Content-Type} spelled in lower case would make the part uncacheable
	 * (isCacheable() reads it by name) with no error anywhere.
	 */
	@Test
	public void headerNamesAreReadCaseInsensitively() {

		WebResponse webResponse = build(headers(
				"content-type", "text/html;charset=UTF-8",
				"X-FrontCache-Component-MaxAge", "10d",
				"X-Frontcache-Component-Tags", "coin-42"));

		assertTrue("a lower-cased Content-Type must still be text", webResponse.isCacheable());
		assertEquals(new HashSet<String>(Arrays.asList("coin-42")), webResponse.getTags());
	}

	/** The url is the cache key the caller passes, and it must come back on the response unchanged. */
	@Test
	public void theUrlIsCarriedOntoTheResponse() {
		assertEquals(URL, build(headers(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "10d")).getUrl());
	}

	@Test
	public void theHeadersHandedInAreTheHeadersOnTheResponse() {

		Map<String, List<String>> headers = headers(
				FCHeaders.CONTENT_TYPE, "text/html;charset=UTF-8",
				FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "10d");

		assertEquals("text/html;charset=UTF-8", build(headers).getHeader(FCHeaders.CONTENT_TYPE));
	}

	/**
	 * A non-text part is not cacheable however it is marked - includes are text, and a cache entry
	 * whose body is not text is one the include processor could never splice.
	 */
	@Test
	public void aNonTextPartIsNotCacheable() {

		WebResponse webResponse = build(headers(
				FCHeaders.CONTENT_TYPE, "application/octet-stream",
				FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, "10d"));

		assertFalse(webResponse.isCacheable());
	}
}
