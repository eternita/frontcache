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
package org.frontcache.resilience.fr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.frontcache.core.FcHttpClient;
import org.frontcache.core.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A fallback must be obtainable before {@code init} has run.
 *
 * {@code FrontCacheEngine} calls {@link FallbackResolverFactory#init} from a background thread behind
 * a deliberate {@code Thread.sleep(3000)}, because fallbacks sourced from URLs cannot be fetched until
 * the node is listening. {@code getInstance()} used to throw
 * {@code RuntimeException("FallbackResolver is not initialized.")} for that whole window - and its
 * callers are {@code FC_Total}, {@code FC_BypassCache}, both {@code FC_ThroughCache_*} and the include
 * processor, i.e. only ever code already handling a failure. So a fragment that failed in the first
 * three seconds failed the entire page instead of degrading, during a restart, which is exactly when a
 * cache is least able to absorb it. Measured on a production edge: 24% of {@code Input-Request}
 * failing on a node that had been up 17 seconds, every one of them this exception.
 *
 * Creation never needed the server - {@code FileBasedFallbackResolver} reads {@code fallbacks.conf} in
 * its constructor - so the fix is to separate creating the resolver from warming it up. These tests pin
 * that separation in both directions: available immediately, and warmed up exactly once.
 */
public class FallbackResolverStartupTest {

	/** Counts how many times the origin warm-up leg ran. */
	private static class CountingResolver implements FallbackResolver {

		final AtomicInteger initCalls = new AtomicInteger();

		@Override public void init(FcHttpClient client) { initCalls.incrementAndGet(); }

		@Override public WebResponse getFallback(String fallbackSource, String urlStr) { return null; }

		@Override public Set<FallbackConfigEntry> getFallbackEntries() { return Collections.emptySet(); }
	}

	@Before
	@After
	public void reset() {
		FallbackResolverFactory.destroy();
	}

	/** The regression, stated directly. */
	@Test
	public void aResolverIsAvailableBeforeInitHasRun() {

		assertNotNull("getInstance() must not throw before init() - the callers are failure handlers",
				FallbackResolverFactory.getInstance());
	}

	/** And it is the same one init() then warms up, not a second instance. */
	@Test
	public void initWarmsUpTheInstanceThatWasAlreadyHandedOut() {

		FallbackResolver early = FallbackResolverFactory.getInstance();
		FallbackResolver afterInit = FallbackResolverFactory.init(null);

		assertSame("init() must not replace an instance whose fallbacks are already being served",
				early, afterInit);
	}

	/**
	 * The warm-up runs once, not per call. {@code FrontCacheIOServlet} calls {@code init} on every
	 * {@code get-fallback-configs}, so a per-call warm-up would re-fetch every URL-sourced fallback
	 * from the origin each time the console polled.
	 */
	@Test
	public void theOriginWarmUpRunsOnceEvenWhenInitIsCalledRepeatedly() {

		CountingResolver resolver = install(new CountingResolver());

		for (int i = 0; i < 5; i++)
			FallbackResolverFactory.init(null);

		assertEquals("warm-up must not repeat per init() call", 1, resolver.initCalls.get());
	}

	/** destroy() is what makes the servlet's destroy-then-init a real reload. */
	@Test
	public void destroyReArmsTheWarmUp() {

		CountingResolver first = install(new CountingResolver());
		FallbackResolverFactory.init(null);
		assertEquals(1, first.initCalls.get());

		FallbackResolverFactory.destroy();

		CountingResolver second = install(new CountingResolver());
		FallbackResolverFactory.init(null);
		assertEquals("after destroy(), init() must warm up again", 1, second.initCalls.get());
	}

	/**
	 * A warm-up that throws must not be retried on every later call, or one unreachable fallback URL
	 * turns every console poll into another origin attempt.
	 */
	@Test
	public void aFailedWarmUpIsNotRetriedForever() {

		final AtomicInteger attempts = new AtomicInteger();
		install(new FallbackResolver() {
			@Override public void init(FcHttpClient client) {
				attempts.incrementAndGet();
				throw new RuntimeException("origin unreachable");
			}
			@Override public WebResponse getFallback(String s, String u) { return null; }
			@Override public Set<FallbackConfigEntry> getFallbackEntries() { return Collections.emptySet(); }
		});

		for (int i = 0; i < 3; i++) {
			try { FallbackResolverFactory.init(null); } catch (RuntimeException expectedOnce) { /* first call only */ }
		}

		assertEquals("a throwing warm-up must be attempted once", 1, attempts.get());
	}

	/** Concurrent first callers get one resolver, not one each. */
	@Test
	public void concurrentFirstCallersShareOneInstance() throws Exception {

		final int threads = 32;
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);
		final AtomicReference<FallbackResolver> seen = new AtomicReference<FallbackResolver>();
		final AtomicInteger distinct = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			new Thread(new Runnable() {
				public void run() {
					try {
						start.await();
						FallbackResolver r = FallbackResolverFactory.getInstance();
						if (!seen.compareAndSet(null, r) && seen.get() != r)
							distinct.incrementAndGet();
					} catch (Exception e) {
						distinct.incrementAndGet();
					} finally {
						done.countDown();
					}
				}
			}).start();
		}

		start.countDown();
		assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS));
		assertEquals("every caller must see the same resolver", 0, distinct.get());
	}

	/**
	 * Puts a fixture in place of the reflection-loaded resolver. There is no setter, so this goes
	 * through the private static field - the alternative is a FRONTCACHE_HOME plus a config property,
	 * and FCConfig reads its properties once in a static initializer with no reload.
	 */
	private static <T extends FallbackResolver> T install(T resolver) {
		try {
			java.lang.reflect.Field f = FallbackResolverFactory.class.getDeclaredField("instance");
			f.setAccessible(true);
			f.set(null, resolver);
			return resolver;
		} catch (Exception e) {
			throw new RuntimeException("could not install the fixture resolver", e);
		}
	}
}
