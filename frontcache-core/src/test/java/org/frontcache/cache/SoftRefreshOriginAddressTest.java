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
package org.frontcache.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;

import org.frontcache.cache.impl.InMemoryCacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Test;

/**
 * What {@code front-cache.origin-host} / {@code front-cache.origin-http-port} actually address in
 * <b>filter mode</b> - the question docs/archive/include-fetch-fix.md section 5, step 6 left open before
 * includes could be pointed at the origin there.
 *
 * The shipped properties say those ports are <i>"mandatory for FILTER MODE (used for soft
 * invalidation/refresh)"</i>, and the doubt was that they might be stale: soft refresh goes through
 * {@code FCUtils.dynamicCall} -> {@code FcHttpClient.fetch}, and {@code ApacheFcHttpClient.fetch}
 * takes the app's own filter chain - ignoring the URL entirely - whenever
 * {@link RequestContext#isFilterMode()} is true.
 *
 * It is not stale, and the reason is one line inside {@code doSoftInvalidation}:
 * {@code ctxCopy.setFilterChain(null)}. A refresh runs later, on a pool thread, when the servlet
 * objects the chain needs are already disposed - so it deliberately drops the chain and goes over
 * HTTP. The context the client is handed is therefore NOT in filter mode, and the URL it is handed
 * is the origin URL. That is what these pin.
 *
 * The consequence for the include change: pointing filter-mode includes at
 * {@code origin-host:origin-http-port} is not a new assumption about a working deployment. It is the
 * same address every soft-expired entry has always refreshed itself from.
 */
public class SoftRefreshOriginAddressTest {

	private static final String PUBLIC_URL = "https://www.example-site.com/fc/fragment.html";
	private static final String ORIGIN_URL = "http://localhost:8080/fc/fragment.html";

	private CacheProcessorBase cacheProcessor;

	@After
	public void tearDown() {
		if (null != cacheProcessor)
			cacheProcessor.destroy();
	}

	/**
	 * The whole answer, in one assertion pair: the refresh is handed the ORIGIN url, and a context
	 * that is no longer in filter mode - which is exactly the branch
	 * {@code ApacheFcHttpClient.fetch} tests to decide between the filter chain and an HTTP call.
	 */
	@Test
	public void aFilterModeSoftRefreshGoesOverHttpToTheOriginURL() throws Exception {

		CacheProcessorBase processor = inMemory();
		RecordingClient client = new RecordingClient();

		RequestContext context = filterModeContext();
		assertTrue("precondition: the request must start in filter mode", context.isFilterMode());

		processor.doSoftInvalidation(PUBLIC_URL, ORIGIN_URL, new HashMap<String, List<String>>(), client, context);

		assertTrue("the refresh never ran", client.entered.await(5, TimeUnit.SECONDS));

		assertEquals("the refresh fetched the wrong URL", ORIGIN_URL, client.fetchedURL);
		assertFalse("the refresh kept the filter chain - it would have re-entered the app's own chain"
				+ " and ignored the origin URL", client.fetchedInFilterMode);
		assertEquals("a soft refresh must go through fetch(), not fetchOverHttp()", "fetch", client.method);
	}

	/** And the entry it writes is keyed on the public URL, not on the address it fetched. */
	@Test
	public void theRefreshedEntryIsKeyedOnThePublicURL() throws Exception {

		CacheProcessorBase processor = inMemory();
		processor.putToCache(PUBLIC_URL, cacheable(PUBLIC_URL, "STALE"));

		RecordingClient client = new RecordingClient();
		processor.doSoftInvalidation(PUBLIC_URL, ORIGIN_URL, new HashMap<String, List<String>>(), client, filterModeContext());

		assertTrue(client.entered.await(5, TimeUnit.SECONDS));

		// the write happens just after the fetch returns - wait for it rather than racing it
		WebResponse refreshed = null;
		for (int i = 0; i < 100 && null == refreshed; i++)
		{
			refreshed = processor.getFromCacheImpl(PUBLIC_URL);
			if (null != refreshed && "FRESH".equals(new String(refreshed.getContent())))
				break;

			refreshed = null;
			Thread.sleep(20);
		}

		assertEquals("the refreshed entry is not under the public URL", PUBLIC_URL, refreshed.getUrl());
		assertEquals("nothing was stored under the origin URL", null, processor.getFromCacheImpl(ORIGIN_URL));
	}

	// ---- harness ---------------------------------------------------------------------------------

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

	/** A request being served by the servlet FILTER: it carries a chain. */
	private RequestContext filterModeContext() {
		RequestContext context = new RequestContext();
		context.setIncludeLevel("0");
		context.setRequestId("test-request-id");
		context.setFilterChain((FilterChain) Proxy.newProxyInstance(
				SoftRefreshOriginAddressTest.class.getClassLoader(),
				new Class[] { FilterChain.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						throw new IllegalStateException("the filter chain must not be used by a soft refresh");
					}
				}));
		return context;
	}

	/**
	 * Records what the real {@code ApacheFcHttpClient} would have branched on. It cannot be the real
	 * one - that opens a socket - so it captures the two inputs to that branch instead.
	 */
	private static class RecordingClient implements FcHttpClient {

		final CountDownLatch entered = new CountDownLatch(1);

		volatile String fetchedURL;
		volatile boolean fetchedInFilterMode = true;
		volatile String method;

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {

			method = "fetch";
			fetchedURL = urlStr;
			fetchedInFilterMode = context.isFilterMode();
			entered.countDown();

			return new WebResponse(urlStr, "FRESH".getBytes(), "30d", null);
		}

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {

			method = "fetchOverHttp";
			return fetch(urlStr, requestHeaders, context);
		}

		@Override
		public WebResponse fetch(String urlStr) {
			return fetch(urlStr, new HashMap<String, List<String>>(), new RequestContext());
		}
	}
}
