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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.frontcache.plugin.event.FcEvent;

/** For tests outside this package: subscribe to the event bus and collect what it delivers. */
public final class EventCollector {

	private final List<FcEvent> received = Collections.synchronizedList(new ArrayList<>());
	private final PluginCounters counters = new PluginCounters();

	private EventCollector() {
	}

	/** Subscribes a listener to these types, with every RequestCompleted in its sample. */
	@SafeVarargs
	public static EventCollector subscribe(Class<? extends FcEvent>... types) {
		EventCollector collector = new EventCollector();
		FcEventBus.subscribe("test", FcEventBusTest.listener(Set.of(types), collector.received::addAll), 1000, 1.0,
				collector.counters);
		return collector;
	}

	/** Waits until {@code count} events have been delivered, then returns every one so far. */
	public List<FcEvent> await(int count) throws InterruptedException {
		FcEventBusTest.waitFor(() -> count <= counters.delivered.sum());
		synchronized (received) {
			return new ArrayList<>(received);
		}
	}

	/** Everything delivered so far, after giving the dispatcher a moment. */
	public List<FcEvent> settled() throws InterruptedException {
		Thread.sleep(200);
		synchronized (received) {
			return new ArrayList<>(received);
		}
	}

	/** Stops every subscription - call from {@code @After}. */
	public static void stopAll() {
		FcEventBus.stopAll();
	}
}
