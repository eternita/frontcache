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
package org.frontcache.bots;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Top-level requests by visitor type (the leaf), counted where the type is decided - what the exporter reports as
 * {@code frontcache_visitor_requests_total{type}} (2.13.0).
 *
 * Top-level only, for the reason the rate limiter has {@code scope=toplevel}: a fragment re-entering the engine is the
 * same visitor again, so counting includes would count a visitor once per fragment of their page. Warm requests are
 * not visitors and are not counted.
 *
 * Bounded: a type is counted only once classification gave it, so the names are the roots and the declared types
 * (at most {@value VisitorTypes#MAX_DECLARED} + 2 at a time); the map keeps a name a reload removed, so its counter
 * stays monotonic, and refuses a new one past {@link #MAX_NAMES} - many reloads of many renames - rather than grow.
 */
public final class VisitorTypeRequests {

	/** The most names ever counted in one process. */
	public static final int MAX_NAMES = 64;

	private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<String, LongAdder>();

	private VisitorTypeRequests() {
	}

	/** One top-level request of this type. Allocates only the first time a name is seen. */
	public static void count(String visitorType) {
		if (null == visitorType)
			return;
		LongAdder adder = COUNTS.get(visitorType);
		if (null == adder)
		{
			if (MAX_NAMES <= COUNTS.size())
				return;
			adder = COUNTS.computeIfAbsent(visitorType, t -> new LongAdder());
		}
		adder.increment();
	}

	/** @return the requests counted for the type since the process started, 0 for a name never counted */
	public static long requests(String visitorType) {
		LongAdder adder = COUNTS.get(visitorType);
		return (null == adder) ? 0 : adder.sum();
	}

	/** @return every name counted so far */
	public static Set<String> names() {
		return Collections.unmodifiableSet(COUNTS.keySet());
	}

	/** for tests */
	public static void reset() {
		COUNTS.clear();
	}
}
