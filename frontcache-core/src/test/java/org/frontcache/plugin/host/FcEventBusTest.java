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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.frontcache.plugin.event.ConfigReloaded;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.junit.After;
import org.junit.Test;

/**
 * The bus's promises: nothing is built or offered while nobody listens; a stalled listener only fills - and then
 * overflows, counted - its own queue; a throwing one keeps receiving; stopping counts what was left.
 */
public class FcEventBusTest {

	@After
	public void tearDown() {
		FcEventBus.stopAll();
	}

	static EventListener listener(Set<Class<? extends FcEvent>> types, Consumer<List<FcEvent>> onEvents) {
		return new EventListener() {
			@Override
			public Set<Class<? extends FcEvent>> subscribes() {
				return types;
			}

			@Override
			public void onEvents(List<FcEvent> batch) {
				onEvents.accept(batch);
			}
		};
	}

	static ConfigReloaded reloaded(String file) {
		return new ConfigReloaded(System.currentTimeMillis(), "node", "www.example.com", file, true, 1, List.of());
	}

	@Test
	public void nobodyListeningMeansNothingIsWanted() {
		for (long bit : FcEventBus.BITS.values())
			assertFalse(FcEventBus.wants(bit));
		FcEventBus.publish(reloaded("guard-rules.conf")); // and publishing anyway is harmless
	}

	@Test
	public void onlySubscribedTypesAreWantedAndDelivered() throws InterruptedException {
		CountDownLatch got = new CountDownLatch(1);
		List<FcEvent> received = Collections.synchronizedList(new ArrayList<>());
		FcEventBus.subscribe("test", listener(Set.of(ConfigReloaded.class), batch -> {
			received.addAll(batch);
			got.countDown();
		}), 10, new PluginCounters());

		assertTrue(FcEventBus.wants(FcEventBus.CONFIG_RELOADED));
		assertFalse(FcEventBus.wants(FcEventBus.GUARD_MATCHED));

		FcEventBus.publish(new GuardMatched(0, "n", null, null, "r", "rejected", 403, "/", "guest", "203.0.113.7"));
		FcEventBus.publish(reloaded("bots.conf"));

		assertTrue(got.await(5, TimeUnit.SECONDS));
		assertEquals(1, received.size());
		assertEquals("bots.conf", ((ConfigReloaded) received.get(0)).file());
	}

	@Test
	public void aStalledListenerOverflowsOnlyItsOwnQueueAndTheDropsAreCounted() throws InterruptedException {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		PluginCounters stalled = new PluginCounters();
		FcEventBus.subscribe("stalled", listener(Set.of(ConfigReloaded.class), batch -> {
			inside.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}), 2, stalled);

		CountDownLatch healthyGot = new CountDownLatch(5);
		PluginCounters healthy = new PluginCounters();
		FcEventBus.subscribe("healthy", listener(Set.of(ConfigReloaded.class), batch -> {
			for (int i = 0; i < batch.size(); i++)
				healthyGot.countDown();
		}), 100, healthy);

		FcEventBus.publish(reloaded("1"));
		assertTrue("the stalled listener took the first event", inside.await(5, TimeUnit.SECONDS));

		long start = System.nanoTime();
		for (int i = 2; i <= 5; i++)
			FcEventBus.publish(reloaded(String.valueOf(i)));
		assertTrue("publish never waits for a listener", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);

		assertEquals("a queue of 2 holds two of the four, the other two are dropped", 2, stalled.dropped.sum());
		assertTrue("the other listener gets everything", healthyGot.await(5, TimeUnit.SECONDS));
		assertEquals(0, healthy.dropped.sum());

		release.countDown();
	}

	@Test
	public void aThrowingListenerKeepsReceiving() throws InterruptedException {
		CountDownLatch calls = new CountDownLatch(2);
		PluginCounters counters = new PluginCounters();
		FcEventBus.subscribe("thrower", listener(Set.of(ConfigReloaded.class), batch -> {
			calls.countDown();
			throw new IllegalStateException("boom");
		}), 10, counters);

		FcEventBus.publish(reloaded("1"));
		waitFor(() -> 1 <= counters.errors.sum());
		FcEventBus.publish(reloaded("2"));

		assertTrue(calls.await(5, TimeUnit.SECONDS));
		waitFor(() -> 2 <= counters.errors.sum());
		assertEquals(0, counters.delivered.sum());
	}

	@Test
	public void aBatchIsNeverLargerThanTheMaximum() throws InterruptedException {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());
		PluginCounters counters = new PluginCounters();
		FcEventBus.subscribe("batches", listener(Set.of(ConfigReloaded.class), batch -> {
			sizes.add(batch.size());
			inside.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}), 2000, counters);

		FcEventBus.publish(reloaded("first"));
		assertTrue(inside.await(5, TimeUnit.SECONDS));
		for (int i = 0; i < 1200; i++)
			FcEventBus.publish(reloaded(String.valueOf(i)));
		release.countDown();

		waitFor(() -> 1201 == counters.delivered.sum());
		for (int size : sizes)
			assertTrue("batch of " + size, size <= FcEventBus.MAX_BATCH);
		assertEquals(Integer.valueOf(FcEventBus.MAX_BATCH), sizes.get(1));
	}

	@Test
	public void stoppingCountsWhatWasStillQueuedAsDropped() throws InterruptedException {
		CountDownLatch inside = new CountDownLatch(1);
		PluginCounters counters = new PluginCounters();
		FcEventBus.subscribe("slow", listener(Set.of(ConfigReloaded.class), batch -> {
			inside.countDown();
			try {
				Thread.sleep(60_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}), 10, counters);

		FcEventBus.publish(reloaded("taken"));
		assertTrue(inside.await(5, TimeUnit.SECONDS));
		FcEventBus.publish(reloaded("queued-1"));
		FcEventBus.publish(reloaded("queued-2"));

		FcEventBus.stopAll();
		assertEquals(2, counters.dropped.sum());
		assertFalse(FcEventBus.wants(FcEventBus.CONFIG_RELOADED));
	}

	@Test
	public void aListenerSubscribingToNothingThisNodeEmitsIsNotStarted() {
		assertEquals(0L, FcEventBus.subscribe("none", listener(Set.of(), batch -> { }), 10, new PluginCounters()));
		assertEquals(0L, FcEventBus.subscribe("null", listener(null, batch -> { }), 10, new PluginCounters()));
		for (long bit : FcEventBus.BITS.values())
			assertFalse(FcEventBus.wants(bit));
	}

	static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean())
		{
			assertTrue("timed out", System.currentTimeMillis() < deadline);
			Thread.sleep(10);
		}
	}
}
