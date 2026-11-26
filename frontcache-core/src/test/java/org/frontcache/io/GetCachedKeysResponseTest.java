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
package org.frontcache.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code get-cached-keys}' two answers: the whole listing streamed when no {@code limit} is named
 * (every existing caller), one bounded page with its count and an exact {@code more} when it is.
 */
public class GetCachedKeysResponseTest {

	private CacheProcessor processor;

	/** What the servlet answered. */
	private final ByteArrayOutputStream body = new ByteArrayOutputStream();
	private final Map<String, String> headers = new HashMap<String, String>();
	private final int[] error = {0};
	private HttpServletResponse resp;

	@Before
	public void setUp() throws Exception {
		processor = new InMemoryCacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M"); // the default caches nothing
		processor.init(props);
		for (String key : Arrays.asList("/p/c", "/p/a", "/q/a", "/p/b", "/p/d")) {
			WebResponse response = new WebResponse(key, "x".getBytes(), "30d", null);
			response.setStatusCode(200);
			processor.putToCache(key, response);
		}

		resp = mock(HttpServletResponse.class);
		ServletOutputStream out = new ServletOutputStream() {
			@Override public void write(int b) { body.write(b); }
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener listener) { }
		};
		when(resp.getOutputStream()).thenReturn(out);
		doAnswer(inv -> headers.put(inv.getArgument(0), inv.getArgument(1))).when(resp).setHeader(anyString(), anyString());
		doAnswer(inv -> { error[0] = inv.getArgument(0); return null; }).when(resp).sendError(anyInt(), anyString());
	}

	@After
	public void tearDown() {
		processor.destroy();
	}

	private List<String> lines() {
		List<String> lines = new ArrayList<String>();
		for (String line : new String(body.toByteArray(), StandardCharsets.UTF_8).split("\n"))
			if (!line.isEmpty())
				lines.add(line);
		return lines;
	}

	@Test
	public void withoutALimitEveryKeyIsStreamedWithNoPagingHeaders() {
		FrontCacheIOServlet.writeKeysResponse(processor, null, null, null, resp);

		assertEquals(Arrays.asList("/p/a", "/p/b", "/p/c", "/p/d", "/q/a"), lines());
		assertTrue("no count header: an old client sees the answer it always saw", headers.isEmpty());
	}

	@Test
	public void aPrefixAppliesToTheStreamedListingToo() {
		FrontCacheIOServlet.writeKeysResponse(processor, "/q/", "", null, resp);

		assertEquals(Arrays.asList("/q/a"), lines());
	}

	@Test
	public void aPageCarriesItsCountAndAnExactMore() {
		FrontCacheIOServlet.writeKeysResponse(processor, "/p/", null, "2", resp);
		assertEquals(Arrays.asList("/p/a", "/p/b"), lines());
		assertEquals("2", headers.get(FCHeaders.X_FRONTCACHE_KEYS_COUNT));
		assertEquals("true", headers.get(FCHeaders.X_FRONTCACHE_KEYS_MORE));

		body.reset();
		FrontCacheIOServlet.writeKeysResponse(processor, "/p/", "/p/b", "2", resp);
		assertEquals(Arrays.asList("/p/c", "/p/d"), lines());
		assertEquals("a full last page is not 'more' - the walk looked one key further",
				"false", headers.get(FCHeaders.X_FRONTCACHE_KEYS_MORE));
	}

	@Test
	public void anEmptyPageStillSaysItIsAPage() {
		FrontCacheIOServlet.writeKeysResponse(processor, "/z/", null, "10", resp);

		assertEquals(Arrays.<String>asList(), lines());
		assertEquals("0", headers.get(FCHeaders.X_FRONTCACHE_KEYS_COUNT));
		assertEquals("false", headers.get(FCHeaders.X_FRONTCACHE_KEYS_MORE));
	}

	@Test
	public void aBadLimitIsABadRequest() {
		FrontCacheIOServlet.writeKeysResponse(processor, null, null, "lots", resp);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, error[0]);

		FrontCacheIOServlet.writeKeysResponse(processor, null, null, "0", resp);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, error[0]);
	}

	/** A failed page is a 500 - never a short page whose missing 'more' reads as the end of the listing. */
	@Test
	public void aListingFailureIsAServerErrorNotAShortPage() throws Exception {
		CacheProcessor failing = mock(CacheProcessor.class);
		doAnswer(inv -> {
			java.util.function.Consumer<String> sink = inv.getArgument(3);
			sink.accept("/a");
			throw new IOException("L2 index unavailable");
		}).when(failing).forEachCachedKey(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				anyInt(), org.mockito.ArgumentMatchers.any());

		FrontCacheIOServlet.writeKeysResponse(failing, null, null, "10", resp);

		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error[0]);
		assertEquals(0, body.size());
		assertTrue(headers.isEmpty());
	}

	@Test
	public void aLimitOverTheCapIsServedAsTheCap() throws Exception {
		CacheProcessor counting = mock(CacheProcessor.class);
		int[] asked = {0};
		doAnswer(inv -> { asked[0] = inv.getArgument(2); return 0L; }).when(counting)
				.forEachCachedKey(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
						anyInt(), org.mockito.ArgumentMatchers.any());

		FrontCacheIOServlet.writeKeysResponse(counting, null, null, "10000000", resp);

		assertEquals("one more than the cap, to know whether more follow", FrontCacheIOServlet.MAX_KEYS_PAGE + 1, asked[0]);
	}
}
