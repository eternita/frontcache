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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.frontcache.cache.CacheProcessor;
import org.junit.Test;

/**
 * {@link WebResponse#copy()} - which runs on every cache hit.
 *
 * The interesting property is not speed but what the copy must and must not share with its source.
 * It must share the content byte[] (or a cache hit would duplicate the whole page) and must NOT
 * share the header map (or {@code putToCache}'s in-place header strip would reach the cached
 * instance and, worse, a visitor's Set-Cookie would be cached).
 *
 * The case-insensitivity assertion below is a regression test for a real defect: the copy came back
 * as a plain HashMap, so a response whose headers arrived through {@code FCUtils.revertHeaders} -
 * a {@code TreeMap(CASE_INSENSITIVE_ORDER)}, because intermediaries re-case header names - silently
 * became case-SENSITIVE the moment it was copied.
 */
public class WebResponseCopyTest {

	private static WebResponse cachedResponse() {
		// exactly the shape FCUtils.revertHeaders produces
		Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
		headers.put("Content-Type", new ArrayList<String>(Arrays.asList("text/html; charset=UTF-8")));
		headers.put("X-Frontcache-Component-Max-Age", new ArrayList<String>(Arrays.asList("30d")));

		WebResponse response = new WebResponse("http://example.com/p.html", "body".getBytes(), "30d", null);
		response.setHeaders(headers);
		response.setStatusCode(200);
		response.setCacheLevel(FCHeaders.CACHE_LEVEL_L1);
		response.addTags(Arrays.asList("tag-a", "tag-b"));
		return response;
	}

	/**
	 * The defect. A re-cased header name must still resolve on the copy - Cloudflare and friends do
	 * re-case them, and {@code isText()} / {@code isCacheable()} both look up Content-Type by name.
	 */
	@Test
	public void theCopyStaysCaseInsensitive() {
		WebResponse copy = cachedResponse().copy();

		assertNotNull("exact case", copy.getHeader("Content-Type"));
		assertNotNull("lower case", copy.getHeader("content-type"));
		assertNotNull("upper case", copy.getHeader("CONTENT-TYPE"));
		assertNotNull("re-cased, as an intermediary would send it", copy.getHeader("Content-TYPE"));

		assertTrue("isText() resolves Content-Type by name, so it must survive a copy", copy.isText());
		assertTrue("so does isCacheable()", copy.isCacheable());
	}

	/** A source with an ordinary map must not be forced into a case-insensitive one either. */
	@Test
	public void aCaseSensitiveSourceStaysCaseSensitive() {
		WebResponse plain = new WebResponse("http://example.com/p.html", "body".getBytes());
		plain.addHeader(FCHeaders.CONTENT_TYPE, "text/html");

		WebResponse copy = plain.copy();

		assertNotNull(copy.getHeader(FCHeaders.CONTENT_TYPE));
		assertNull("the source map is a plain HashMap - the copy must not silently gain a comparator",
				copy.getHeader(FCHeaders.CONTENT_TYPE.toUpperCase()));
	}

	/**
	 * The whole reason copy() exists. putToCache strips headers IN PLACE, so the copy's map - and the
	 * value lists inside it - must be independent of the cached instance's.
	 */
	@Test
	public void headerMutationsDoNotLeakBetweenSourceAndCopy() {
		WebResponse source = cachedResponse();
		WebResponse copy = source.copy();

		copy.getHeaders().remove("Content-Type");
		copy.addHeader("Set-Cookie", "session=abc");

		assertNotNull("removing from the copy must not touch the source", source.getHeader("Content-Type"));
		assertNull("adding to the copy must not touch the source", source.getHeader("Set-Cookie"));

		// and the value LISTS must be separate too, not shared references
		copy.addHeader("Content-Type", "text/plain");
		assertEquals("the source's value list must be its own",
				1, source.getHeaders().get("Content-Type").size());
	}

	/** Content is shared on purpose: cloning it would copy the whole page on every cache hit. */
	@Test
	public void contentIsSharedNotCloned() {
		WebResponse source = cachedResponse();

		assertSame("the byte[] must be shared - this is what keeps a cache hit cheap",
				source.getContent(), source.copy().getContent());
	}

	/** Everything else must come across unchanged - the copy is served to the client. */
	@Test
	public void everyFieldSurvivesTheCopy() {
		WebResponse source = cachedResponse();
		WebResponse copy = source.copy();

		assertEquals(source.getUrl(), copy.getUrl());
		assertEquals(source.getStatusCode(), copy.getStatusCode());
		assertEquals(source.getRefreshType(), copy.getRefreshType());
		assertEquals(source.getCacheLevel(), copy.getCacheLevel());
		assertEquals(source.getTags(), copy.getTags());
		assertEquals(source.getExpireTimeMap(), copy.getExpireTimeMap());
		assertEquals(source.getContentLenth(), copy.getContentLenth());
		assertArrayEquals(source.getContent(), copy.getContent());
		assertEquals(source.getHeaders(), copy.getHeaders());
	}

	/**
	 * copy() no longer routes through a constructor that parses a maxAge only to have the result
	 * overwritten. The expiry must still be exactly the source's - including the sentinels, where an
	 * accidental re-parse would show up as NO_CACHE.
	 */
	@Test
	public void theExpiryIsCopiedNotRecomputed() {
		WebResponse forever = new WebResponse("http://example.com/f.html", "b".getBytes(), "forever", null);
		WebResponse copy = forever.copy();

		assertEquals("CACHE_FOREVER must survive", CacheProcessor.CACHE_FOREVER, copy.getExpireTimeMillis());
		assertEquals(forever.getExpireTimeMap(), copy.getExpireTimeMap());

		WebResponse timed = new WebResponse("http://example.com/t.html", "b".getBytes(), "30d", null);
		assertEquals(timed.getExpireTimeMap(), timed.copy().getExpireTimeMap());
		assertEquals(timed.getExpireTimeMillis(), timed.copy().getExpireTimeMillis());
	}

	/** A bodyless response (a redirect, say) must copy without exploding. */
	@Test
	public void aResponseWithNoBodyCopiesCleanly() {
		WebResponse redirect = new WebResponse("http://example.com/r");
		redirect.setStatusCode(302);
		redirect.addHeader("Location", "http://example.com/target");

		WebResponse copy = redirect.copy();

		assertNull(copy.getContent());
		assertEquals(302, copy.getStatusCode());
		assertEquals("http://example.com/target", copy.getHeader("Location"));
	}
}
