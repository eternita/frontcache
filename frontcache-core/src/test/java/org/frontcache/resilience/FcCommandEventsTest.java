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
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Properties;

import org.frontcache.core.FrontCacheException;
import org.frontcache.plugin.event.FallbackServed;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.OriginError;
import org.frontcache.plugin.host.EventCollector;
import org.junit.After;
import org.junit.Test;

/** The E1 events a resilience command emits after the fact: {@code OriginError} and {@code FallbackServed}. */
public class FcCommandEventsTest {

	private static final String GROUP = "test.example.com";

	@After
	public void tearDown() {
		EventCollector.stopAll();
		FcResilienceRegistry.reset();
		FcCommandMetrics.reset();
		FcThreadPools.shutdownAll();
		FcResilienceConfig.reset();
	}

	private static void semaphore(String... commandKeys) {
		Properties props = new Properties();
		for (String key : commandKeys)
			props.setProperty("hystrix.command." + key + ".execution.isolation.strategy", "SEMAPHORE");
		FcResilienceConfig.install(props);
	}

	@Test
	public void aFailedCallIsAnOriginErrorAndThenAFallbackServed() throws InterruptedException {
		semaphore("Events-Failing");
		EventCollector events = EventCollector.subscribe(OriginError.class, FallbackServed.class);

		assertEquals("fallback", new Failing("Events-Failing", new IllegalStateException("origin down")).execute());

		List<FcEvent> received = events.await(2);
		OriginError error = (OriginError) received.get(0);
		assertEquals("Events-Failing", error.command());
		assertEquals("failure", error.failure());
		assertEquals(0, error.status());
		assertEquals(IllegalStateException.class.getName(), error.cause());
		assertEquals("https://www.example.com/p?x=1", error.uri());
		assertNull("no request", error.requestId());
		assertTrue(0 <= error.latencyMillis());

		FallbackServed fallback = (FallbackServed) received.get(1);
		assertEquals("Events-Failing", fallback.command());
		assertEquals("failure", fallback.failure());
		assertEquals(IllegalStateException.class.getName(), fallback.cause());
	}

	@Test
	public void theOriginsStatusIsCarried() throws InterruptedException {
		semaphore("Events-Status");
		EventCollector events = EventCollector.subscribe(OriginError.class);

		new Failing("Events-Status", FrontCacheException.originStatus(503, "Wrong response code 503")).execute();

		OriginError error = (OriginError) events.await(1).get(0);
		assertEquals(503, error.status());
		assertEquals(FrontCacheException.class.getName(), error.cause());
	}

	@Test
	public void anInnerCommandsFailureIsReportedOnceByTheInnerCommand() throws InterruptedException {
		semaphore("Events-Inner", "Events-Outer");
		EventCollector events = EventCollector.subscribe(OriginError.class, FallbackServed.class);

		assertEquals("outer fallback", new Outer("Events-Outer", "Events-Inner").execute());

		List<FcEvent> received = events.await(2);
		assertEquals(received.toString(), 2, events.settled().size());
		OriginError error = (OriginError) received.get(0);
		assertEquals("the inner command reports its own failure", "Events-Inner", error.command());
		FallbackServed fallback = (FallbackServed) received.get(1);
		assertEquals("the outer command served the fallback", "Events-Outer", fallback.command());
	}

	@Test
	public void onlyTheSubscribedTypesAreDelivered() throws InterruptedException {
		semaphore("Events-Quiet");
		EventCollector events = EventCollector.subscribe(FallbackServed.class);

		new Failing("Events-Quiet", new IllegalStateException("down")).execute();

		List<FcEvent> received = events.await(1);
		assertTrue(received.toString(), received.stream().allMatch(e -> e instanceof FallbackServed));
	}

	// ---- fixtures -----------------------------------------------------------

	private static class Failing extends FcCommand<String> {
		private final Exception failure;

		Failing(String commandKey, Exception failure) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
			this.failure = failure;
		}

		@Override
		protected String run() throws Exception {
			throw failure;
		}

		@Override
		protected String getFallback() {
			return "fallback";
		}

		@Override
		protected String eventUri() {
			return "https://www.example.com/p?x=1";
		}
	}

	/** Runs an inner command that has no fallback, so the inner failure reaches this one as an FcCommandException. */
	private static class Outer extends FcCommand<String> {
		private final String innerKey;

		Outer(String commandKey, String innerKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
			this.innerKey = innerKey;
		}

		@Override
		protected String run() {
			return new FcCommand<String>(Setter.withGroupKey(GROUP).andCommandKey(innerKey)) {
				@Override
				protected String run() {
					throw new IllegalStateException("include down");
				}
			}.execute();
		}

		@Override
		protected String getFallback() {
			return "outer fallback";
		}
	}
}
