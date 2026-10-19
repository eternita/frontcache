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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Test;

/**
 * One client type's expiry must not evict the other client type's still-valid copy.
 *
 * {@code maxage="bot:30d, guest:1d"} is one cached entry carrying two expiration stamps - the cache
 * key has no client-type dimension. The expiry branch used to {@code removeFromCache()}
 * unconditionally, on the strength of an assumption that no longer holds: before two TTLs could be
 * live at once, whenever the stamps differed one of them was NO_CACHE, and a NO_CACHE client type
 * never reaches the expiry branch at all. So a guest arriving at T+25h deleted the copy that was
 * valid for crawlers for another 29 days.
 *
 * That is usually invisible - the next lines call the origin and put the entry straight back, with
 * both stamps reset. It stops being invisible exactly when it costs the most: the write back is
 * skipped when a fallback was served, so an origin outage plus one human visitor removes the entry,
 * and every crawler that follows goes to the dead origin. Before the split that visitor would have
 * been served dynamically and left the cache alone.
 *
 * Both the top-level path ({@link CacheProcessorBase}) and the include path
 * ({@code IncludeProcessorBase}) carry these three lines, and fixing one and not the other is the
 * classic way this regresses. This pins the top-level one;
 * {@code org.frontcache.include.IncludeTwoLiveTtlEvictionTest} pins the include one.
 *
 * Neither can be an e2e test: there the origin is up, so the refetch puts the entry straight back
 * and the eviction leaves no trace. A failing origin is the whole fixture.
 */
public class TwoLiveTtlEvictionTest {

	private static final String URL = "http://test.example.com/fc/search.html";

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

	/** An entry still valid for bots for 30 days, already expired for guests. */
	private WebResponse liveForBotsExpiredForGuests() {
		WebResponse response = new WebResponse(URL, "CACHED".getBytes(), "bot:30d, guest:1d", null);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");

		Map<String, Long> expireTimeMap = new HashMap<String, Long>(response.getExpireTimeMap());
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, System.currentTimeMillis() - 1000L);
		response.setExpireTimeMap(expireTimeMap);

		return response;
	}

	private RequestContext context(String clientType) {
		RequestContext context = new RequestContext();
		context.setDomain("test.example.com"); // the resilience command GROUP key
		context.setIncludeLevel("0");
		context.setRequestId("test-request-id");
		context.setCurrentRequestURL(URL);
		context.setClientType(clientType);
		return context;
	}

	/** An origin that is down, so the request is served a fallback and nothing is written back. */
	private static class FallbackOnlyClient implements FcHttpClient {

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			context.setFallbackUsed("open-circuit");

			WebResponse fallback = new WebResponse(urlStr, "FALLBACK".getBytes(), null, null);
			fallback.setStatusCode(503);
			fallback.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
			return fallback;
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

	/**
	 * The failure this fix exists for, in full: origin down, one guest arrives, and afterwards the
	 * crawlers must still be served from cache.
	 */
	@Test
	public void aGuestExpiryDuringAnOriginOutageMustNotEvictTheBotCopy() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(URL, liveForBotsExpiredForGuests());

		RequestContext guest = context(FCHeaders.REQUEST_CLIENT_TYPE_GUEST);
		WebResponse served = processor.processRequest(URL, new HashMap<String, List<String>>(),
				new FallbackOnlyClient(), guest);

		// the guest itself is served the fallback - that half was always right
		assertEquals("FALLBACK", new String(served.getContent()));

		WebResponse stillCached = processor.getFromCache(URL, guest);
		assertNotNull("the entry is still valid for bots for 29 more days - it must survive a guest's"
				+ " expiry, and nothing rewrote it because a fallback was served", stillCached);
		assertEquals("CACHED", new String(stillCached.getContent()));

		// and the crawler the entry exists for is still served from it
		RequestContext bot = context(FCHeaders.REQUEST_CLIENT_TYPE_BOT);
		WebResponse toBot = processor.processRequest(URL, new HashMap<String, List<String>>(),
				new FallbackOnlyClient(), bot);
		assertEquals("a bot must still be served the cached copy after the guest's expiry",
				"CACHED", new String(toBot.getContent()));
	}

	/**
	 * The other half of the predicate, so the fix cannot be "never delete": once no client type can
	 * be served from the entry any more, it still goes. Same outage, same fallback - the difference
	 * is only that both stamps are in the past.
	 */
	@Test
	public void anEntryExpiredForEveryClientTypeIsStillEvicted() throws Exception {

		CacheProcessorBase processor = inMemory();

		WebResponse response = liveForBotsExpiredForGuests();
		Map<String, Long> expireTimeMap = new HashMap<String, Long>(response.getExpireTimeMap());
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, System.currentTimeMillis() - 1000L);
		response.setExpireTimeMap(expireTimeMap);
		processor.putToCache(URL, response);

		RequestContext guest = context(FCHeaders.REQUEST_CLIENT_TYPE_GUEST);
		processor.processRequest(URL, new HashMap<String, List<String>>(), new FallbackOnlyClient(), guest);

		assertNull("nothing can be served from this entry any more - it must be removed",
				processor.getFromCache(URL, guest));
	}
}
