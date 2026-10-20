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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.warmer.WarmRequest;
import org.junit.After;
import org.junit.Test;

/**
 * What {@code CacheProcessorBase.processRequest} does differently for the cache warmer's own requests
 * - see {@link WarmRequest}.
 *
 * <h2>Why the soft refresh has to be synchronous</h2>
 *
 * The warmer's delay applies to its calls to the front door. It paces the origin only if each call
 * does its origin work before it returns. A soft-expired entry is normally served stale while
 * {@code softInvalidationExecutor} refreshes it - so a fill run over 50,000 soft-expired entries would
 * read "fresh" 50,000 times at loopback speed and queue 50,000 background origin fetches, one per key
 * (single-flight dedupes a key, not a list). The only visible symptom would be the origin's own graphs,
 * hence a test that counts what is queued.
 *
 * {@code org.frontcache.include.IncludeWarmSoftRefreshTest} is the same assertion on the include
 * path's copy of the block.
 */
public class WarmRequestCacheProcessorTest {

	private static final String URL = "http://www.example.com/p/1";

	private CountingProcessor processor;

	@After
	public void tearDown() {
		if (null != processor)
			processor.destroy();
	}

	/** Counts removals, so "refresh never removes" is an assertion and not an inference. */
	private static class CountingProcessor extends InMemoryCacheProcessor {
		final AtomicInteger removals = new AtomicInteger();

		@Override
		public void removeFromCache(String filter) {
			removals.incrementAndGet();
			super.removeFromCache(filter);
		}
	}

	private CountingProcessor processor() {
		CountingProcessor p = new CountingProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M");
		p.init(props);
		processor = p;
		return p;
	}

	private static WebResponse cached(String content, String refreshType, boolean expired) {
		WebResponse response = new WebResponse(URL, content.getBytes(), "30d", refreshType);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		if (expired)
		{
			Map<String, Long> expireTimeMap = new HashMap<String, Long>();
			expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, System.currentTimeMillis() - 1000L);
			expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, System.currentTimeMillis() - 1000L);
			response.setExpireTimeMap(expireTimeMap);
		}
		return response;
	}

	private static RequestContext context(String warmMode) {
		RequestContext context = new RequestContext();
		context.setDomain("www.example.com");
		context.setIncludeLevel("0");
		context.setRequestId("test-request-id");
		context.setCurrentRequestURL(URL);
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_BOT);
		if (null != warmMode)
			context.setWarmRequest(warmMode);
		return context;
	}

	/** An origin that answers with a cacheable page - or fails, when told to. */
	private static class Origin implements FcHttpClient {
		final AtomicInteger fetches = new AtomicInteger();
		volatile boolean down = false;

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			fetches.incrementAndGet();
			if (down)
			{
				context.setFallbackUsed("failure");
				WebResponse fallback = new WebResponse(urlStr, "FALLBACK".getBytes(), null, null);
				fallback.setStatusCode(503);
				fallback.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
				return fallback;
			}
			WebResponse fresh = new WebResponse(urlStr, "FRESH".getBytes(), "30d", null);
			fresh.setStatusCode(200);
			fresh.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
			return fresh;
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

	private static WebResponse request(CacheProcessorBase p, Origin origin, RequestContext context) throws Exception {
		return p.processRequest(URL, new HashMap<String, List<String>>(), origin, context);
	}

	// ---- soft refresh -----------------------------------------------------------------------------

	@Test
	public void aSoftExpiredEntryOnAWarmRequestIsRefreshedBeforeTheCallReturns() throws Exception {

		CountingProcessor p = processor();
		p.putToCache(URL, cached("STALE", FCHeaders.COMPONENT_REFRESH_TYPE_SOFT, true));
		Origin origin = new Origin();
		RequestContext warm = context(WarmRequest.MODE_FILL);

		WebResponse served = request(p, origin, warm);

		assertEquals("the origin must have been called on this thread, before processRequest returned",
				1, origin.fetches.get());
		assertEquals("nothing may be handed to softInvalidationExecutor - that is the pacing hole",
				0, ((CacheProcessorBase) p).softRefreshInFlightCount());
		assertEquals("FRESH", new String(served.getContent()));
		assertEquals("FRESH", new String(p.getFromCache(URL, warm).getContent()));
		assertEquals(WarmRequest.RESULT_STORED, warm.getWarmResult());
		assertEquals("a soft refresh never removes the entry first", 0, p.removals.get());
	}

	/** The control: a visitor still gets the stale copy and a background refresh, exactly as before. */
	@Test
	public void aVisitorStillGetsTheStaleCopyAndABackgroundRefresh() throws Exception {

		CountingProcessor p = processor();
		p.putToCache(URL, cached("STALE", FCHeaders.COMPONENT_REFRESH_TYPE_SOFT, true));
		Origin origin = new Origin();

		WebResponse served = request(p, origin, context(null));

		assertEquals("a visitor is served the stale copy", "STALE", new String(served.getContent()));
		waitForQuiesce(p);
		assertEquals("and the refresh happened in the background", 1, origin.fetches.get());
	}

	// ---- refresh mode -----------------------------------------------------------------------------

	@Test
	public void refreshModeRefetchesAFreshEntryWithoutRemovingIt() throws Exception {

		CountingProcessor p = processor();
		p.putToCache(URL, cached("OLD", null, false));
		Origin origin = new Origin();
		RequestContext warm = context(WarmRequest.MODE_REFRESH);

		WebResponse served = request(p, origin, warm);

		assertEquals(1, origin.fetches.get());
		assertEquals("FRESH", new String(served.getContent()));
		assertEquals("FRESH", new String(p.getFromCache(URL, warm).getContent()));
		assertEquals(WarmRequest.RESULT_STORED, warm.getWarmResult());
		assertEquals("refresh overwrites - removing first would open a window in which every visitor misses",
				0, p.removals.get());
	}

	@Test
	public void aRefreshWhoseOriginCallFailsLeavesTheOldEntryServing() throws Exception {

		CountingProcessor p = processor();
		p.putToCache(URL, cached("OLD", null, false));
		Origin origin = new Origin();
		origin.down = true;
		RequestContext warm = context(WarmRequest.MODE_REFRESH);

		request(p, origin, warm);

		WebResponse survivor = p.getFromCache(URL, context(null));
		assertNotNull("a failed refresh must leave the old copy in place, not an empty key", survivor);
		assertEquals("OLD", new String(survivor.getContent()));
		assertEquals(WarmRequest.RESULT_FALLBACK, warm.getWarmResult());
		assertEquals(0, p.removals.get());
	}

	// ---- fill mode --------------------------------------------------------------------------------

	@Test
	public void fillModeLeavesAFreshEntryAloneAndSaysSo() throws Exception {

		CountingProcessor p = processor();
		p.putToCache(URL, cached("CACHED", null, false));
		Origin origin = new Origin();
		RequestContext warm = context(WarmRequest.MODE_FILL);

		WebResponse served = request(p, origin, warm);

		assertEquals("a fresh entry is a hit - the origin is not called", 0, origin.fetches.get());
		assertEquals("CACHED", new String(served.getContent()));
		assertEquals(WarmRequest.RESULT_FRESH, warm.getWarmResult());
	}

	@Test
	public void fillModeStoresAMissingEntry() throws Exception {

		CountingProcessor p = processor();
		Origin origin = new Origin();
		RequestContext warm = context(WarmRequest.MODE_FILL);

		request(p, origin, warm);

		assertEquals(1, origin.fetches.get());
		assertEquals(WarmRequest.RESULT_STORED, warm.getWarmResult());
		assertEquals("FRESH", new String(p.getFromCache(URL, warm).getContent()));
	}

	/** {@code maxage="guest:1d"} warmed as a bot: the origin answers, nothing is stored. */
	@Test
	public void aResponseNotCacheableForTheRunsClientTypeIsReportedAsSuch() throws Exception {

		CountingProcessor p = processor();
		FcHttpClient guestOnly = new Origin() {
			@Override
			public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
				WebResponse r = new WebResponse(urlStr, "GUESTS".getBytes(), "guest:1d", null);
				r.setStatusCode(200);
				r.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
				return r;
			}
		};
		RequestContext warm = context(WarmRequest.MODE_FILL);

		p.processRequest(URL, new HashMap<String, List<String>>(), guestOnly, warm);

		assertEquals(WarmRequest.RESULT_NOT_CACHEABLE, warm.getWarmResult());
		assertEquals(null, p.getFromCache(URL, warm));
	}

	/** A visitor's request records no warm result - the flag is the warmer's alone. */
	@Test
	public void aVisitorsRequestCarriesNoWarmResult() throws Exception {

		CountingProcessor p = processor();
		RequestContext visitor = context(null);

		request(p, new Origin(), visitor);

		assertEquals(null, visitor.getWarmResult());
	}

	private static void waitForQuiesce(CacheProcessorBase p) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (0 < p.softRefreshInFlightCount() && System.currentTimeMillis() < deadline)
			Thread.sleep(10);
	}
}
