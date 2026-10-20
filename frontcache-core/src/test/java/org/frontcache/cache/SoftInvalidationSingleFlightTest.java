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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Test;

/**
 * Soft refresh must be a per-key singleton that never empties the cache.
 *
 * Both properties are invisible when broken - the cache still returns the right bytes, just far
 * more often from the origin - and both were broken in production. A hot fragment on a 60 s TTL
 * produced 170 origin fetches per second on its own, and 33 such fragments produced 608/s, because:
 *
 * - the refresh task called removeFromCache() before fetching, so for one origin round trip every
 *   arriving request found nothing and went to the origin itself, and
 * - nothing deduplicated the refreshes, so each of those requests also queued another refresh,
 *   which removed the entry again.
 *
 * The two defects fed each other, which is why the herd sustained itself instead of decaying.
 * These tests hold a refresh open and assert what the rest of the world sees while it runs.
 */
public class SoftInvalidationSingleFlightTest {

	private static final String URL = "http://test.example.com/fc/fragment.html";
	private static final String OTHER_URL = "http://test.example.com/fc/other.html";

	private CacheProcessorBase cacheProcessor;

	@After
	public void tearDown() {
		if (null != cacheProcessor)
			cacheProcessor.destroy();
	}

	private CacheProcessorBase inMemory() {
		InMemoryCacheProcessor processor = new InMemoryCacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M");
		processor.init(props);
		cacheProcessor = processor;
		return processor;
	}

	private WebResponse cacheable(String url, String content) {
		WebResponse response = new WebResponse(url, content.getBytes(), "30d", null);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		return response;
	}

	/** An origin that blocks until released, so a refresh can be observed while it is still running. */
	private static class BlockingClient implements FcHttpClient {

		final AtomicInteger fetches = new AtomicInteger();
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		volatile boolean fail = false;

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			fetches.incrementAndGet();
			entered.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (fail)
				throw new FrontCacheException("origin is down");

			return new WebResponse(urlStr, "FRESH".getBytes(), "30d", null);
		}

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			return fetch(urlStr, requestHeaders, context);
		}

		@Override
		public WebResponse fetch(String urlStr) throws FrontCacheException {
			return fetch(urlStr, new HashMap<String, List<String>>(), null);
		}
	}

	private RequestContext context() {
		RequestContext context = new RequestContext();
		context.setIncludeLevel("0");
		context.setRequestId("test-request-id");
		return context;
	}

	private void softInvalidate(CacheProcessorBase processor, String url, FcHttpClient client) {
		processor.doSoftInvalidation(url, url, new HashMap<String, List<String>>(), client, context());
	}

	/**
	 * The herd. One expiry, many concurrent requests, one origin call.
	 */
	@Test
	public void concurrentSoftRefreshesOfOneKeyMakeOneOriginCall() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, cacheable(URL, "STALE"));

		BlockingClient client = new BlockingClient();

		softInvalidate(processor, URL, client);
		assertTrue("refresh never started", client.entered.await(5, TimeUnit.SECONDS));

		// every other request that arrives during the refresh window
		for (int i = 0; i < 200; i++)
			softInvalidate(processor, URL, client);

		client.release.countDown();
		waitForQuiesce(processor);

		assertEquals("one refresh per expiry, not one per request", 1, client.fetches.get());
	}

	/**
	 * The window. While the refresh runs, the stale entry is still served - that is what "soft"
	 * means, and removing it first was what sent every concurrent request to the origin.
	 */
	@Test
	public void theStaleEntryIsServedWhileTheRefreshIsInFlight() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, cacheable(URL, "STALE"));

		BlockingClient client = new BlockingClient();
		softInvalidate(processor, URL, client);
		assertTrue("refresh never started", client.entered.await(5, TimeUnit.SECONDS));

		// getFromCacheImpl, not getFromCache: the latter wraps the read in an FcCommand, which needs a
		// group key and the resilience config. What is under test is what the cache holds.
		WebResponse duringRefresh = processor.getFromCacheImpl(URL);
		assertNotNull("cache was emptied while the refresh was in flight", duringRefresh);
		assertEquals("STALE", new String(duringRefresh.getContent()));

		client.release.countDown();
		waitForQuiesce(processor);

		WebResponse afterRefresh = processor.getFromCacheImpl(URL);
		assertNotNull(afterRefresh);
		assertEquals("refresh did not replace the entry", "FRESH", new String(afterRefresh.getContent()));
	}

	/**
	 * A failed refresh must leave the stale entry in place. Before the fix the entry had already
	 * been deleted by the time the origin call failed, so an origin outage emptied the cache
	 * key by key as each entry expired - exactly when the stale copy was most useful.
	 */
	@Test
	public void aFailedRefreshLeavesTheStaleEntry() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, cacheable(URL, "STALE"));

		BlockingClient client = new BlockingClient();
		client.fail = true;

		softInvalidate(processor, URL, client);
		assertTrue(client.entered.await(5, TimeUnit.SECONDS));
		client.release.countDown();
		waitForQuiesce(processor);

		WebResponse afterFailure = processor.getFromCacheImpl(URL);
		assertNotNull("a failed refresh emptied the key", afterFailure);
		assertEquals("STALE", new String(afterFailure.getContent()));
	}

	/** The claim is per key, not global - a slow fragment must not block refreshes of every other one. */
	@Test
	public void otherKeysAreNotBlockedByAnInFlightRefresh() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, cacheable(URL, "STALE"));
		processor.putToCache(OTHER_URL, cacheable(OTHER_URL, "STALE"));

		BlockingClient first = new BlockingClient();
		BlockingClient second = new BlockingClient();

		softInvalidate(processor, URL, first);
		assertTrue(first.entered.await(5, TimeUnit.SECONDS));

		softInvalidate(processor, OTHER_URL, second);
		assertTrue("a different key was blocked by the in-flight refresh",
				second.entered.await(5, TimeUnit.SECONDS));

		first.release.countDown();
		second.release.countDown();
		waitForQuiesce(processor);
	}

	/**
	 * The claim is released when the task ends, so the next expiry refreshes again. Releasing it in
	 * the submitting method instead would let the very next request re-submit while the task was
	 * still fetching, which is the herd again; never releasing it would freeze the key forever.
	 */
	@Test
	public void theKeyCanBeRefreshedAgainAfterTheFirstRefreshCompletes() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, cacheable(URL, "STALE"));

		BlockingClient first = new BlockingClient();
		softInvalidate(processor, URL, first);
		assertTrue(first.entered.await(5, TimeUnit.SECONDS));
		first.release.countDown();
		waitForQuiesce(processor);

		BlockingClient second = new BlockingClient();
		softInvalidate(processor, URL, second);
		assertTrue("the key stayed claimed after its refresh finished",
				second.entered.await(5, TimeUnit.SECONDS));
		second.release.countDown();
		waitForQuiesce(processor);

		assertEquals(1, second.fetches.get());
	}

	/** Waits for the soft-refresh executor to drain, via the in-flight claim count. */
	private void waitForQuiesce(CacheProcessorBase processor) throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			if (0 == processor.softRefreshInFlightCount())
				return;
			Thread.sleep(10);
		}
		throw new AssertionError("soft refresh did not finish: "
				+ processor.softRefreshInFlightCount() + " still claimed");
	}
}
