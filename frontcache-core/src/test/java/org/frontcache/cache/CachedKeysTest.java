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
package org.frontcache.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.lucene.util.BytesRef;
import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.WebResponse;
import org.junit.Test;

public class CachedKeysTest {

	/**
	 * U+1F600 (a surrogate pair in UTF-16) against U+FF01: String.compareTo puts the emoji first
	 * (0xD83D < 0xFF01), code-point order - and the L2 index's UTF-8 byte order - puts it last.
	 */
	@Test
	public void theOrderIsCodePointOrderWhichIsTheIndexsByteOrder() {
		String emoji = "/😀";
		String fullwidth = "/！";

		assertTrue("premise: String.compareTo disagrees", emoji.compareTo(fullwidth) < 0);
		assertTrue(CachedKeys.ORDER.compare(emoji, fullwidth) > 0);
		assertTrue("the index's order", new BytesRef(emoji.getBytes(StandardCharsets.UTF_8))
				.compareTo(new BytesRef(fullwidth.getBytes(StandardCharsets.UTF_8))) > 0);

		assertTrue(CachedKeys.ORDER.compare("/a", "/ab") < 0);
		assertEquals(0, CachedKeys.ORDER.compare("/a", "/a"));
	}

	@Test
	public void theRangeExcludesTheCursorItselfAndKeysOutsideThePrefix() {
		assertTrue(CachedKeys.inRange("/p/b", "/p/", "/p/a"));
		assertFalse("the cursor is the last key of the page before", CachedKeys.inRange("/p/a", "/p/", "/p/a"));
		assertFalse(CachedKeys.inRange("/q/b", "/p/", null));
		assertTrue("null and empty mean no restriction", CachedKeys.inRange("/q/b", null, ""));
	}

	/** A processor with no override of its own pages through the default: filtered, sorted, cut. */
	@Test
	public void theDefaultListingPagesAProcessorThatOnlyListsKeys() throws Exception {
		CacheProcessor processor = new InMemoryCacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M"); // the default caches nothing
		processor.init(props);
		try {
			for (String url : Arrays.asList("http://h/p/c", "http://h/p/a", "http://h/q/a", "http://h/p/b")) {
				WebResponse response = new WebResponse(url, "x".getBytes(), "30d", null);
				response.setStatusCode(200);
				processor.putToCache(url, response);
			}
			List<String> keys = new ArrayList<String>();
			long count = processor.forEachCachedKey("http://h/p/", "http://h/p/a", 1, keys::add);

			assertEquals(1, count);
			assertEquals(Arrays.asList("http://h/p/b"), keys);
		} finally {
			processor.destroy();
		}
	}
}
