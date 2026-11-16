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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.bots.BotClassifier;
import org.frontcache.bots.BotRule;
import org.frontcache.bots.VisitorTypes;
import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code @type customer like guest no-store}: a request of the type never WRITES an entry, whatever the page's
 * {@code maxage} - and still reads one per its resolved TTL. The leak it closes is a customer's render stored in the
 * one copy every visitor is served.
 *
 * This is the page's save site, {@code CacheProcessorBase.processRequest}: the miss, and the soft-refresh branch - a
 * background refresh would fetch with the customer's request headers and store the answer, so it is a write too.
 * {@code org.frontcache.include.IncludeNoStoreVisitorTypeTest} pins the include path's copy of both - the
 * duplicated-block rule: fixing one and not the other is how this regresses.
 */
public class NoStoreVisitorTypeTest {

	private static final String URL = "http://test.example.com/fc/account.html";

	private CacheProcessorBase cacheProcessor;

	@Before
	public void declareTypes() {
		BotClassifier.install(BotClassifier.of(new ArrayList<BotRule>(),
				VisitorTypes.of("@type customer like guest no-store", "@type captcha-passed like guest")));
	}

	@After
	public void tearDown() {
		BotClassifier.install(null);
		if (null != cacheProcessor)
			cacheProcessor.destroy();
	}

	/** A customer's miss reaches the origin and writes nothing, so the guest after it misses too. */
	@Test
	public void aNoStoreMissGoesToTheOriginAndWritesNothing() throws Exception {
		CacheProcessorBase processor = inMemory();
		OriginClient origin = new OriginClient("1h", null);

		WebResponse toCustomer = processor.processRequest(URL, headers(), origin, context("customer"));
		assertEquals("RENDERED", new String(toCustomer.getContent()));
		assertNull("maxage=1h caches for every guest-based type - but a no-store type writes nothing",
				processor.getFromCache(URL, context("guest")));

		processor.processRequest(URL, headers(), origin, context("guest"));
		assertEquals("so the guest after it misses too, and its render is the one stored", 2, origin.calls.get());
		assertNotNull(processor.getFromCache(URL, context("guest")));
	}

	/** It may still read: an entry a guest stored serves the customer per its resolved TTL. */
	@Test
	public void aNoStoreTypeIsServedFromAFreshEntry() throws Exception {
		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, cached("1h", null));
		OriginClient origin = new OriginClient("1h", null);

		WebResponse toCustomer = processor.processRequest(URL, headers(), origin, context("customer"));

		assertEquals("CACHED", new String(toCustomer.getContent()));
		assertEquals(0, origin.calls.get());
	}

	/** The flag is the type's, not its base's: a type 'like guest' without it stores as before. */
	@Test
	public void aTypeWithoutTheFlagStores() throws Exception {
		CacheProcessorBase processor = inMemory();

		processor.processRequest(URL, headers(), new OriginClient("1h", null), context("captcha-passed"));

		assertNotNull(processor.getFromCache(URL, context("guest")));
	}

	/**
	 * A soft-expired entry is a miss for a no-store type: fetched on this thread and not stored, never a background
	 * refresh - that would carry the customer's cookie to the origin and store what it rendered, for everyone. The
	 * stale entry stays for the next visitor who may refresh it.
	 */
	@Test
	public void aSoftExpiredEntryIsAMissThatQueuesNoRefresh() throws Exception {
		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, expired(cached("1h", FCHeaders.COMPONENT_REFRESH_TYPE_SOFT)));
		OriginClient origin = new OriginClient("1h", FCHeaders.COMPONENT_REFRESH_TYPE_SOFT);

		WebResponse toCustomer = processor.processRequest(URL, headers(), origin, context("customer"));

		assertEquals("served what the origin rendered for it, not the stale copy on the promise of a refresh",
				"RENDERED", new String(toCustomer.getContent()));

		processor.softInvalidationExecutor.shutdown();
		assertTrue(processor.softInvalidationExecutor.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals("one origin call - this request's, no background refresh", 1, origin.calls.get());
		assertEquals("the stale entry is untouched", "CACHED",
				new String(processor.getFromCache(URL, context("guest")).getContent()));
	}

	/** And a guest on the same entry still gets the soft refresh - stale now, the refresh behind it. */
	@Test
	public void aGuestStillSoftRefreshes() throws Exception {
		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, expired(cached("1h", FCHeaders.COMPONENT_REFRESH_TYPE_SOFT)));
		OriginClient origin = new OriginClient("1h", FCHeaders.COMPONENT_REFRESH_TYPE_SOFT);

		WebResponse toGuest = processor.processRequest(URL, headers(), origin, context("guest"));
		assertEquals("CACHED", new String(toGuest.getContent()));

		processor.softInvalidationExecutor.shutdown();
		assertTrue(processor.softInvalidationExecutor.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals("RENDERED", new String(processor.getFromCache(URL, context("guest")).getContent()));
	}

	// ---- fixture ---------------------------------------------------------------------------------

	private CacheProcessorBase inMemory() {
		InMemoryCacheProcessor processor = new InMemoryCacheProcessor();
		Properties props = new Properties();
		props.setProperty("front-cache.cache-processor.impl.in-memory.maxsize", "10M");
		processor.init(props);
		cacheProcessor = processor;
		return processor;
	}

	private static WebResponse cached(String maxAge, String refreshType) {
		WebResponse response = new WebResponse(URL, "CACHED".getBytes(), maxAge, refreshType);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
		return response;
	}

	/** Every stamp in the past. */
	private static WebResponse expired(WebResponse response) {
		Map<String, Long> expireTimeMap = new HashMap<String, Long>();
		for (String type : response.getExpireTimeMap().keySet())
			expireTimeMap.put(type, System.currentTimeMillis() - 1000L);
		response.setExpireTimeMap(expireTimeMap);
		return response;
	}

	private static Map<String, List<String>> headers() {
		return new HashMap<String, List<String>>();
	}

	private static RequestContext context(String visitorType) {
		RequestContext context = new RequestContext();
		context.setDomain("test.example.com"); // the resilience command GROUP key
		context.setIncludeLevel("0");
		context.setRequestId("test-request-id");
		context.setCurrentRequestURL(URL);
		context.setVisitorType(visitorType, VisitorTypes.active().rootOf(visitorType));
		return context;
	}

	/** An origin that renders a cacheable page and counts the renders. */
	private static class OriginClient implements FcHttpClient {

		final AtomicInteger calls = new AtomicInteger();

		private final String maxAge;
		private final String refreshType;

		OriginClient(String maxAge, String refreshType) {
			this.maxAge = maxAge;
			this.refreshType = refreshType;
		}

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			calls.incrementAndGet();
			WebResponse response = new WebResponse(urlStr, "RENDERED".getBytes(), maxAge, refreshType);
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
}
