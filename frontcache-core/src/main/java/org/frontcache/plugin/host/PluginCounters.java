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

import java.util.concurrent.atomic.LongAdder;

/** One plugin's counters, kept for the life of the process - across reloads, which replace its subscribers. */
final class PluginCounters {

	/** Events handed to the plugin's listeners. */
	final LongAdder delivered = new LongAdder();

	/** Events dropped because a listener's queue was full, or still queued when the engine stopped. */
	final LongAdder dropped = new LongAdder();

	/** Anything the plugin threw: in start, stop, a listener, listing its actions, its endpoint. */
	final LongAdder errors = new LongAdder();

	/** Requests its endpoint answered. */
	final LongAdder served = new LongAdder();

	/** Requests refused before reaching its endpoint: over its concurrency cap (503) or its body limit (413). */
	final LongAdder serveRefused = new LongAdder();

	/** Requests its endpoint failed - it threw, or wrote more than the node buffers (503). */
	final LongAdder serveFailed = new LongAdder();
}
