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
package org.frontcache.include;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.bots.BotClassifier;
import org.frontcache.bots.BotRule;
import org.frontcache.bots.VisitorTypes;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The include path's copy of {@code @type customer like guest no-store} - {@code org.frontcache.cache.NoStoreVisitorTypeTest}
 * is the same assertion on the page's path. A fragment fetched for a no-store type is never stored, whatever its
 * {@code maxage}: on a miss ({@code isIncludeResponseCacheable}, which the combine path shares), and on a
 * soft-expired entry, which must not queue a background refresh carrying the customer's request headers.
 */
public class IncludeNoStoreVisitorTypeTest {

	private static final String EDGE_BASE   = "https://www.example-site.com";
	private static final String ORIGIN_BASE = "http://origin.example.com:8080";

	private static final String INCLUDE_URI = "/fc/recently-viewed.htm";
	private static final String PUBLIC_URL  = EDGE_BASE + INCLUDE_URI;

	private CacheProcessor previousCache;
	private RecordingCache cache;

	@Before
	public void setUp() throws Exception {
		BotClassifier.install(BotClassifier.of(new ArrayList<BotRule>(),
				VisitorTypes.of("@type customer like guest no-store", "@type captcha-passed like guest")));
		cache = new RecordingCache();
		previousCache = swapCacheManagerInstance(cache);
	}

	@After
	public void tearDown() throws Exception {
		swapCacheManagerInstance(previousCache);
		BotClassifier.install(null);
	}

	@Test
	public void aNoStoreMissIsFetchedAndNotStored() throws Exception {
		OriginClient origin = new OriginClient(null);

		String page = resolveOneInclude(context("customer"), origin);

		assertTrue(page, page.contains("RENDERED"));
		assertEquals(1, origin.calls.get());
		assertFalse("maxage=1h caches for every guest-based type - but a no-store type writes nothing",
				cache.stored.containsKey(PUBLIC_URL));
	}

	@Test
	public void aTypeWithoutTheFlagStores() throws Exception {
		resolveOneInclude(context("captcha-passed"), new OriginClient(null));

		assertNotNull(cache.stored.get(PUBLIC_URL));
	}

	/** The combine path stores through the same decision. */
	@Test
	public void theSharedCacheabilityCheckRefusesANoStoreType() throws Exception {
		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		WebResponse fragment = rendered(null);

		assertFalse(processor.isIncludeResponseCacheable(fragment, context("customer")));
		assertTrue(processor.isIncludeResponseCacheable(fragment, context("guest")));
		assertTrue(processor.isIncludeResponseCacheable(fragment, context("captcha-passed")));
	}

	@Test
	public void aSoftExpiredFragmentIsAMissThatQueuesNoRefresh() throws Exception {
		cache.stored.put(PUBLIC_URL, softExpired());
		OriginClient origin = new OriginClient(FCHeaders.COMPONENT_REFRESH_TYPE_SOFT);

		String page = resolveOneInclude(context("customer"), origin);

		assertTrue("fetched for it, not the stale copy on the promise of a refresh: " + page, page.contains("RENDERED"));
		assertEquals("no background refresh carrying the customer's headers", 0, cache.softRefreshes.get());
		assertEquals("the stale entry is untouched", "CACHED", new String(cache.stored.get(PUBLIC_URL).getContent()));
	}

	@Test
	public void aGuestStillSoftRefreshes() throws Exception {
		cache.stored.put(PUBLIC_URL, softExpired());
		OriginClient origin = new OriginClient(FCHeaders.COMPONENT_REFRESH_TYPE_SOFT);

		String page = resolveOneInclude(context("guest"), origin);

		assertTrue(page, page.contains("CACHED"));
		assertEquals(1, cache.softRefreshes.get());
		assertEquals(0, origin.calls.get());
	}

	// ---- fixture ---------------------------------------------------------------------------------

	private static WebResponse rendered(String refreshType) {
		WebResponse response = new WebResponse(PUBLIC_URL, "RENDERED".getBytes(StandardCharsets.UTF_8), "1h", refreshType);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		return response;
	}

	private static WebResponse softExpired() {
		WebResponse response = new WebResponse(PUBLIC_URL, "CACHED".getBytes(StandardCharsets.UTF_8), "1h",
				FCHeaders.COMPONENT_REFRESH_TYPE_SOFT);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");

		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		for (String type : response.getExpireTimeMap().keySet())
			expireTimeMap.put(type, System.currentTimeMillis() - 1000L);
		response.setExpireTimeMap(expireTimeMap);
		return response;
	}

	/** One page carrying one include, run through the real processIncludes; @return the page as served */
	private String resolveOneInclude(RequestContext context, FcHttpClient origin) throws Exception {

		Properties properties = new Properties();
		properties.setProperty(IncludeProcessorBase.INCLUDE_FETCH_TARGET_PROPERTY,
				IncludeProcessorBase.INCLUDE_FETCH_TARGET_ORIGIN);

		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(properties);

		try {
			String page = "<html><body><fc:include url=\"" + INCLUDE_URI + "\"/></body></html>";
			WebResponse parent = new WebResponse(EDGE_BASE + "/index.htm", page.getBytes(StandardCharsets.UTF_8));

			WebResponse served = processor.processIncludes(parent, EDGE_BASE, new HashMap<String, List<String>>(),
					origin, context, 1);
			return new String(served.getContent(), StandardCharsets.UTF_8);
		} finally {
			processor.destroy();
		}
	}

	private RequestContext context(String visitorType) throws Exception {

		RequestContext context = new RequestContext();
		context.setRequest(stubRequest());
		context.setOriginURL(new URL(ORIGIN_BASE));
		context.setDomain("www.example-site.com");
		context.setRequestId("test-request-id");
		context.setClientIP("203.0.113.7");
		context.setVisitorType(visitorType, VisitorTypes.active().rootOf(visitorType));
		return context;
	}

	/** An origin that renders a cacheable fragment and counts the renders. */
	private static class OriginClient implements FcHttpClient {

		final AtomicInteger calls = new AtomicInteger();

		private final String refreshType;

		OriginClient(String refreshType) {
			this.refreshType = refreshType;
		}

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			calls.incrementAndGet();
			WebResponse response = new WebResponse(urlStr, "RENDERED".getBytes(StandardCharsets.UTF_8), "1h", refreshType);
			response.setStatusCode(200);
			response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
			return response;
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

	/** Answers the handful of things RequestLogger and the include path ask of a request. */
	private static HttpServletRequest stubRequest() {
		return (HttpServletRequest) Proxy.newProxyInstance(
				IncludeNoStoreVisitorTypeTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("getMethod".equals(method.getName()))
							return "GET";

						if ("getHeader".equals(method.getName()))
							return null;

						if (boolean.class == method.getReturnType())
							return Boolean.FALSE;

						return null;
					}
				});
	}

	private static class RecordingCache implements CacheProcessor {

		final Map<String, WebResponse> stored = new LinkedHashMap<String, WebResponse>();

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
			stored.remove(filter);
		}

		@Override
		public synchronized void removeFromCacheAll() {
			stored.clear();
		}

		@Override
		public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) throws FrontCacheException {
			throw new UnsupportedOperationException();
		}

		final AtomicInteger softRefreshes = new AtomicInteger();

		@Override
		public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) {
			softRefreshes.incrementAndGet();
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

	/**
	 * {@code CacheManager} holds one JVM-global processor and offers no setter - creating it opens
	 * the Lucene index and takes its write lock. Swapping the field is the only way to give this test
	 * a cache it can read back, and it is restored after every case.
	 */
	private static CacheProcessor swapCacheManagerInstance(CacheProcessor replacement) throws Exception {

		Field field = CacheManager.class.getDeclaredField("instance");
		field.setAccessible(true);

		CacheProcessor previous = (CacheProcessor) field.get(null);
		field.set(null, replacement);

		return previous;
	}
}
