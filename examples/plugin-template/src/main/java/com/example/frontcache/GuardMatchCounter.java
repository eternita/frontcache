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
package com.example.frontcache;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;

/**
 * E1: counts the guard's matches by disposition ({@code rejected}, {@code redirected}, {@code challenged},
 * {@code dry-run}). The node calls {@link #onEvents} on this listener's own thread, after the fact, with what has
 * accumulated - never on a request thread, so it may take its time (but a listener that blocks only fills its queue).
 *
 * <p>It keeps counts, never an address: an event's {@code clientKey} is personal data.
 */
final class GuardMatchCounter implements EventListener {

	/** Dispositions are a handful of fixed words - but a later node may add one, so the map is bounded anyway. */
	private static final int MAX_KEYS = 32;

	private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

	@Override
	public Set<Class<? extends FcEvent>> subscribes() {
		return Set.of(GuardMatched.class);
	}

	@Override
	public void onEvents(List<FcEvent> batch) {
		for (FcEvent event : batch)
			if (event instanceof GuardMatched matched)
			{
				String key = (null == matched.disposition()) ? "unknown" : matched.disposition();
				if (counts.size() < MAX_KEYS || counts.containsKey(key))
					counts.computeIfAbsent(key, k -> new LongAdder()).increment();
			}
	}

	/** The counts so far, by disposition, sorted. */
	Map<String, Long> counts() {
		Map<String, Long> out = new TreeMap<>();
		counts.forEach((k, v) -> out.put(k, v.sum()));
		return out;
	}
}
