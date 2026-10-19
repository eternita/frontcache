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
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.frontcache.warmer.WarmRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The include path's half of the warmer's pacing rule - {@code org.frontcache.cache.WarmRequestCacheProcessorTest}
 * is the page's half. Two tests because there are two copies of the soft-refresh block
 * ({@code CacheProcessorBase.processRequest} and {@code IncludeProcessorBase.getIncludeFromCache}), and
 * fixing one only is how this regresses.
 *
 * Both behaviours asserted here are invisible when broken: the page is still warmed, the fragments are
 * still refreshed - just in the background, where the warmer's delay does not reach, so a warm run
 * over a list of pages sharing a soft-expired header queues one origin fetch per page instead of
 * pacing them.
 */
public class IncludeWarmSoftRefreshTest {

	private static final String EDGE_BASE   = "http://www.example.com";
	private static final String ORIGIN_BASE = "http://origin.example.com:8080";

	private static final String INCLUDE_URI = "/fc/header.htm";
	private static final String PUBLIC_URL  = EDGE_BASE + INCLUDE_URI;

	private CacheProcessor previousCache;
	private RecordingCache cache;

	@Before
	public void installCache() throws Exception {
		cache = new RecordingCache();
		previousCache = swapCacheManagerInstance(cache);
	}

	@After
	public void restoreCache() throws Exception {
		swapCacheManagerInstance(previousCache);
	}

	@Test
	public void aSoftExpiredFragmentOnAWarmRequestIsRefreshedBeforeTheIncludeReturns() throws Exception {

		cache.stored.put(PUBLIC_URL, softExpired());
		Origin origin = new Origin();

		process("<fc:include url=\"" + INCLUDE_URI + "\"/>", origin, context(WarmRequest.MODE_FILL));

		assertEquals("the fragment must be fetched on the include's own thread", 1, origin.fetches.get());
		assertEquals("and never handed to the background refresh", 0, cache.softInvalidations.get());
		assertEquals("FRESH", new String(cache.stored.get(PUBLIC_URL).getContent(), StandardCharsets.UTF_8));
		assertEquals("the stale entry is overwritten, never removed first", 0, cache.removals.get());
	}

	/** The control: a visitor's page still gets the stale fragment and a background refresh. */
	@Test
	public void aVisitorsPageStillSoftRefreshesInTheBackground() throws Exception {

		cache.stored.put(PUBLIC_URL, softExpired());
		Origin origin = new Origin();

		WebResponse page = process("<fc:include url=\"" + INCLUDE_URI + "\"/>", origin, context(null));

		assertEquals(0, origin.fetches.get());
		assertEquals(1, cache.softInvalidations.get());
		assertEquals("STALE", new String(page.getContent(), StandardCharsets.UTF_8));
	}

	/**
	 * An async include is fire-and-forget for a visitor. On a warm request it is waited for - otherwise
	 * it escapes the delay - but its content still stays out of the page.
	 */
	@Test
	public void anAsyncIncludeOnAWarmRequestIsResolvedBeforeThePageReturns() throws Exception {

		SlowOrigin origin = new SlowOrigin();

		WebResponse page = process("[<fc:include url=\"" + INCLUDE_URI + "\" call=\"async\"/>]", origin,
				context(WarmRequest.MODE_FILL));

		assertEquals("processIncludes must not return before the async include's origin call finished",
				1, origin.finished.get());
		assertNotNull("and it was stored", cache.stored.get(PUBLIC_URL));
		assertEquals("its content is still not part of the page", "[]", new String(page.getContent(), StandardCharsets.UTF_8));
	}

	@Test
	public void anAsyncIncludeOnAVisitorsPageIsStillFireAndForget() throws Exception {

		SlowOrigin origin = new SlowOrigin();

		process("[<fc:include url=\"" + INCLUDE_URI + "\" call=\"async\"/>]", origin, context(null));

		assertEquals("a visitor's page does not wait for an async include", 0, origin.finished.get());

		// let the background include finish HERE: left running, it writes into whichever cache
		// CacheManager holds by then - the next test's
		origin.release.countDown();
		long deadline = System.currentTimeMillis() + 5000;
		while (0 == origin.finished.get() && System.currentTimeMillis() < deadline)
			Thread.sleep(5);
		Thread.sleep(50); // and its cache write after the fetch
	}

	// ---- fixture ---------------------------------------------------------------------------------

	private static WebResponse softExpired() {
		WebResponse response = new WebResponse(PUBLIC_URL, "STALE".getBytes(StandardCharsets.UTF_8), "30d",
				FCHeaders.COMPONENT_REFRESH_TYPE_SOFT);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, System.currentTimeMillis() - 1000L);
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, System.currentTimeMillis() - 1000L);
		response.setExpireTimeMap(expireTimeMap);
		return response;
	}

	private WebResponse process(String page, FcHttpClient origin, RequestContext context) throws Exception {

		Properties properties = new Properties();
		properties.setProperty(IncludeProcessorBase.INCLUDE_FETCH_TARGET_PROPERTY,
				IncludeProcessorBase.INCLUDE_FETCH_TARGET_ORIGIN);

		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(properties);
		try {
			WebResponse parent = new WebResponse(EDGE_BASE + "/p/1", page.getBytes(StandardCharsets.UTF_8));
			parent.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
			return processor.processIncludes(parent, EDGE_BASE, new HashMap<String, List<String>>(), origin, context, 1);
		} finally {
			processor.destroy();
		}
	}

	private static RequestContext context(String warmMode) throws Exception {
		RequestContext context = new RequestContext();
		context.setRequest(stubRequest());
		context.setOriginURL(new URL(ORIGIN_BASE));
		context.setDomain("www.example.com");
		context.setRequestId("test-request-id");
		context.setClientIP("127.0.0.1");
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_BOT);
		if (null != warmMode)
			context.setWarmRequest(warmMode);
		return context;
	}

	private static WebResponse fresh(String urlStr) {
		WebResponse fresh = new WebResponse(urlStr, "FRESH".getBytes(StandardCharsets.UTF_8), "30d", null);
		fresh.setStatusCode(200);
		fresh.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		return fresh;
	}

	private static class Origin implements FcHttpClient {
		final AtomicInteger fetches = new AtomicInteger();

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
			fetches.incrementAndGet();
			return fresh(urlStr);
		}

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
			return fetch(urlStr, requestHeaders, context);
		}

		@Override
		public WebResponse fetch(String urlStr) throws FrontCacheException {
			return fetch(urlStr, new HashMap<String, List<String>>(), null);
		}
	}

	/** Takes a while, so "did the caller wait for it" has an answer. */
	private static class SlowOrigin extends Origin {
		final AtomicInteger finished = new AtomicInteger();
		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
			try {
				release.await(300, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			WebResponse response = super.fetch(urlStr, requestHeaders, context);
			finished.incrementAndGet();
			return response;
		}
	}

	private static HttpServletRequest stubRequest() {
		return (HttpServletRequest) Proxy.newProxyInstance(
				IncludeWarmSoftRefreshTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				(proxy, method, args) -> {
					if ("getMethod".equals(method.getName()))
						return "GET";
					if (boolean.class == method.getReturnType())
						return Boolean.FALSE;
					return null;
				});
	}

	private static class RecordingCache implements CacheProcessor {

		final Map<String, WebResponse> stored = new LinkedHashMap<String, WebResponse>();
		final AtomicInteger softInvalidations = new AtomicInteger();
		final AtomicInteger removals = new AtomicInteger();

		@Override
		public synchronized void putToCache(String url, WebResponse component) {
			stored.put(url, component);
		}

		@Override
		public synchronized WebResponse getFromCache(String url, RequestContext context) {
			WebResponse hit = stored.get(url);
			return (null == hit) ? null : hit.copy();
		}

		@Override
		public synchronized void removeFromCache(String filter) {
			removals.incrementAndGet();
			stored.remove(filter);
		}

		@Override
		public synchronized void removeFromCacheAll() {
			stored.clear();
		}

		@Override
		public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) {
			softInvalidations.incrementAndGet();
		}

		@Override
		public void init(Properties properties) {
		}

		@Override
		public void destroy() {
		}

		@Override
		public Map<String, String> getCacheStatus() {
			return new HashMap<String, String>();
		}

		@Override
		public CacheStats getCacheStats() {
			return CacheStats.NOT_TRACKED;
		}

		@Override
		public synchronized List<String> getCachedKeys() {
			return new ArrayList<String>(stored.keySet());
		}

		@Override
		public void patch() {
		}

		@Override
		public void purge() {
		}
	}

	private static CacheProcessor swapCacheManagerInstance(CacheProcessor replacement) throws Exception {
		Field field = CacheManager.class.getDeclaredField("instance");
		field.setAccessible(true);
		CacheProcessor previous = (CacheProcessor) field.get(null);
		field.set(null, replacement);
		return previous;
	}
}
