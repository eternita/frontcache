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
package org.frontcache.include;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.junit.Test;

/**
 * The includes of one page are resolved concurrently, and each of them ADDS trace headers to the map
 * it was handed: the include level ({@link IncludeProcessorBase#callInclude}), the request id and the
 * client IP ({@code FCUtils.includeDynamicCallHttpClient}). While they all shared the parent
 * request's map, that was N pool threads mutating one {@link HashMap} - headers written for one
 * include leaked into the next one's origin request, and the snapshot in
 * {@code CacheProcessorBase.doSoftInvalidation} iterated the map while another include thread wrote
 * to it, throwing {@link java.util.ConcurrentModificationException} out of an unrelated include.
 *
 * {@code parseIncludes} now copies the headers per include, on the request thread before anything is
 * submitted, which is what these pin.
 */
public class IncludeRequestHeadersIsolationTest {

	private static final String PAGE =
			"<html><body>" +
			"<fc:include url=\"/a.htm\"/>" +
			"<fc:include url=\"/b.htm\"/>" +
			"<fc:include url=\"/c.htm\" call=\"async\"/>" +
			"</body></html>";

	private static Map<String, List<String>> parentHeaders() {
		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		headers.put("Host", Arrays.asList(new String[]{"www.example-site.com"}));
		headers.put("Accept-Language", Arrays.asList(new String[]{"es"}));
		headers.put("User-Agent", Arrays.asList(new String[]{"junit"}));
		return headers;
	}

	/** {@code parseIncludes} is private - this is the only way to see the per-include maps. */
	@SuppressWarnings("unchecked")
	private static List<Object> parseIncludes(ConcurrentIncludeProcessor processor, String content,
			Map<String, List<String>> requestHeaders, RequestContext context) throws Exception {
		Method m = null;
		for (Method candidate : ConcurrentIncludeProcessor.class.getDeclaredMethods())
			if ("parseIncludes".equals(candidate.getName()))
				m = candidate;

		m.setAccessible(true);
		// hostURL and fetchBaseURL - the same here; the header isolation this pins is independent of
		// where the include is fetched from (see IncludeFetchTargetTest)
		return (List<Object>) m.invoke(processor, content, "http://localhost", "http://localhost", requestHeaders, null, context);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, List<String>> headersOf(Object includePlaceholder) throws Exception {
		Field f = includePlaceholder.getClass().getDeclaredField("requestHeaders");
		f.setAccessible(true);
		return (Map<String, List<String>>) f.get(includePlaceholder);
	}

	private static ConcurrentIncludeProcessor newProcessor() {
		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(new Properties());
		return processor;
	}

	@Test
	public void everyIncludeGetsItsOwnHeaderMap() throws Exception {
		ConcurrentIncludeProcessor processor = newProcessor();
		try {
			Map<String, List<String>> parent = parentHeaders();
			List<Object> includes = parseIncludes(processor, PAGE, parent, new RequestContext());

			assertEquals(3, includes.size());

			Map<Map<String, List<String>>, Boolean> seen = new IdentityHashMap<Map<String, List<String>>, Boolean>();
			for (Object include : includes) {
				Map<String, List<String>> headers = headersOf(include);
				assertNotSame("include shares the parent's header map", parent, headers);
				assertTrue("two includes share one header map", null == seen.put(headers, Boolean.TRUE));
			}
		} finally {
			processor.destroy();
		}
	}

	@Test
	public void everyIncludeCarriesTheParentHeaders() throws Exception {
		ConcurrentIncludeProcessor processor = newProcessor();
		try {
			Map<String, List<String>> parent = parentHeaders();
			List<Object> includes = parseIncludes(processor, PAGE, parent, new RequestContext());

			// sync includes: the parent's headers, nothing added yet
			assertEquals(parent, headersOf(includes.get(0)));
			assertEquals(parent, headersOf(includes.get(1)));

			// async include: the parent's headers plus the two it has always added
			Map<String, List<String>> async = headersOf(includes.get(2));
			for (Map.Entry<String, List<String>> entry : parent.entrySet())
				assertEquals(entry.getValue(), async.get(entry.getKey()));

			assertEquals(Arrays.asList(new String[]{"true"}), async.get(FCHeaders.X_FRONTCACHE_ASYNC_INCLUDE));
			assertTrue(async.containsKey(FCHeaders.X_FRONTCACHE_CLIENT_IP));
		} finally {
			processor.destroy();
		}
	}

	@Test
	public void writingToOneIncludeLeavesTheParentAndTheOtherIncludesAlone() throws Exception {
		ConcurrentIncludeProcessor processor = newProcessor();
		try {
			Map<String, List<String>> parent = parentHeaders();
			Map<String, List<String>> parentBefore = new HashMap<String, List<String>>(parent);
			List<Object> includes = parseIncludes(processor, PAGE, parent, new RequestContext());

			// what callInclude() does to the map it was handed
			headersOf(includes.get(0)).put(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL, Arrays.asList(new String[]{"1.0"}));

			assertEquals("include resolution mutated the parent request's headers", parentBefore, parent);
			assertFalse("one include's include-level leaked into another include",
					headersOf(includes.get(1)).containsKey(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL));
		} finally {
			processor.destroy();
		}
	}

	/**
	 * The reported failure, in miniature: one include thread adding its trace header (callInclude)
	 * while another copies the map it was handed (doSoftInvalidation's snapshot). Both threads used to
	 * touch the same HashMap, so the copy iterated a map being written to. With a map per include
	 * neither can see the other's.
	 */
	@Test
	public void concurrentIncludeResolutionDoesNotThrowConcurrentModification() throws Exception {
		ConcurrentIncludeProcessor processor = newProcessor();
		try {
			Map<String, List<String>> parent = parentHeaders();
			String page = "<html>" +
					repeat("<fc:include url=\"/fc/recent-updates-add.htm?locale=es\"/>", 16) +
					"</html>";

			final List<Object> includes = parseIncludes(processor, page, parent, new RequestContext());
			assertEquals(16, includes.size());

			final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch done = new CountDownLatch(includes.size());
			List<Thread> threads = new ArrayList<Thread>();

			for (int i = 0; i < includes.size(); i++) {
				final Object include = includes.get(i);
				final String includeLevel = "1." + i;
				Thread t = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							start.await();
							Map<String, List<String>> headers = headersOf(include);
							headers.put(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL,
									Arrays.asList(new String[]{includeLevel}));
							for (int n = 0; n < 500; n++) {
								// A key that is added and removed again, so every pass is a STRUCTURAL
								// modification: overwriting a key that is already there does not bump the
								// map's modCount and so cannot trip an iterator.
								String probeKey = "x-frontcache-probe-" + includeLevel + "-" + n;
								headers.put(probeKey, Arrays.asList(new String[]{"1"}));
								// doSoftInvalidation's snapshot - the line the stack trace pointed at
								new HashMap<String, List<String>>(headers);
								headers.remove(probeKey);
							}
						} catch (Throwable e) {
							failure.compareAndSet(null, e);
						} finally {
							done.countDown();
						}
					}
				});
				threads.add(t);
				t.start();
			}

			start.countDown();
			assertTrue("include threads did not finish", done.await(30, TimeUnit.SECONDS));
			for (Thread t : threads)
				t.join();

			if (null != failure.get())
				throw new AssertionError("concurrent include resolution failed: " + failure.get(), failure.get());
		} finally {
			processor.destroy();
		}
	}

	private static String repeat(String s, int times) {
		StringBuilder sb = new StringBuilder(s.length() * times);
		for (String part : Collections.nCopies(times, s))
			sb.append(part);
		return sb.toString();
	}
}
