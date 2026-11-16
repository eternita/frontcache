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
package org.frontcache.plugin.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.plugin.event.CacheInvalidated;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.RequestCompleted;
import org.junit.After;
import org.junit.Test;

/** RequestCompleted - sampled, per subscriber, top-level requests only - and CacheInvalidated. */
public class RequestEventsTest {

	@After
	public void tearDown() {
		FcEventBus.stopAll();
	}

	private static final class Sink {
		final List<FcEvent> received = Collections.synchronizedList(new ArrayList<>());
		final PluginCounters counters = new PluginCounters();
	}

	private static Sink subscribe(String id, double sample) {
		Sink sink = new Sink();
		FcEventBus.subscribe(id, FcEventBusTest.listener(Set.of(RequestCompleted.class), sink.received::addAll), 10_000,
				sample, sink.counters);
		return sink;
	}

	@Test
	public void eachSubscriberGetsItsOwnShareOfTopLevelRequests() throws InterruptedException {
		Sink all = subscribe("all", 1.0);
		Sink half = subscribe("half", 0.5);
		assertEquals(1.0, FcEventBus.requestSample(), 0.0);

		for (int i = 0; i < 400; i++)
			CoreEvents.requestCompleted(request(FCHeaders.COMPONENT_TOPLEVEL), true, "from-cache", 3, 1024);
		CoreEvents.requestCompleted(request("include"), true, "from-cache", 3, 1024);

		FcEventBusTest.waitFor(() -> 400 == all.counters.delivered.sum());
		Thread.sleep(200);
		assertEquals("every top-level request, no include", 400, all.received.size());
		int halfCount = half.received.size();
		assertTrue("about half: " + halfCount, 100 < halfCount && halfCount < 300);

		RequestCompleted event = (RequestCompleted) all.received.get(0);
		assertEquals("GET", event.method());
		assertEquals("www.example.com/search?q=x", event.uri());
		assertEquals("success", event.outcome());
		assertTrue(event.cacheable());
		assertEquals("from-cache", event.cache());
		assertEquals(1024, event.lengthBytes());
		assertEquals("203.0.113.7", event.clientKey());
		assertEquals("curl/8", event.userAgent());
		assertNull(event.fallbackReason());
		assertEquals(RequestCompleted.TYPE, event.type());
	}

	@Test
	public void theDefaultShareIsOnePercentAndNothingIsBuiltWithoutASubscriber() {
		assertEquals(0.0, FcEventBus.requestSample(), 0.0);
		Sink sink = new Sink();
		FcEventBus.subscribe("default", FcEventBusTest.listener(Set.of(RequestCompleted.class), sink.received::addAll),
				10, sink.counters);
		assertEquals(RequestCompleted.DEFAULT_SAMPLE, FcEventBus.requestSample(), 0.0);

		FcEventBus.stopAll();
		assertEquals(0.0, FcEventBus.requestSample(), 0.0);
	}

	@Test
	public void anInvalidationIsAnEvent() throws InterruptedException {
		List<FcEvent> received = Collections.synchronizedList(new ArrayList<>());
		PluginCounters counters = new PluginCounters();
		FcEventBus.subscribe("inv", FcEventBusTest.listener(Set.of(CacheInvalidated.class), received::addAll), 10,
				counters);

		CoreEvents.cacheInvalidated(CacheInvalidated.SCOPE_FILTER, "/product/42");
		CoreEvents.cacheInvalidated(CacheInvalidated.SCOPE_ALL, null);

		FcEventBusTest.waitFor(() -> 2 == counters.delivered.sum());
		assertEquals(new CacheInvalidated(((CacheInvalidated) received.get(0)).time(), received.get(0).node(), null,
				"filter", "/product/42"), received.get(0));
		assertEquals("all", ((CacheInvalidated) received.get(1)).scope());
		assertNull(((CacheInvalidated) received.get(1)).filter());
	}

	private static RequestContext request(String requestType) {
		RequestContext context = new RequestContext();
		context.setRequestType(requestType);
		context.setRequestId("req-1");
		context.setRequestURI("/search");
		context.setRequestQueryString("?q=x");
		context.setFrontCacheHost("www.example.com");
		context.setRequest((HttpServletRequest) Proxy.newProxyInstance(RequestEventsTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class }, (proxy, method, args) -> {
					switch (method.getName())
					{
					case "getRemoteAddr":
						return "203.0.113.7";
					case "getHeader":
						return "User-Agent".equalsIgnoreCase((String) args[0]) ? "curl/8" : null;
					case "getHeaders":
						return Collections.enumeration(Collections.<String>emptyList());
					case "getMethod":
						return "GET";
					default:
						return null;
					}
				}));
		return context;
	}
}
