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
package org.frontcache.plugin.event;

import java.util.List;
import java.util.Set;

/**
 * Receives events after the fact ({@code kinds = observe}).
 *
 * <p>On the request path the node only checks whether anyone subscribed to an event's type (a volatile read, no
 * allocation when nobody did) and, if so, builds the event and {@code offer}s it into <b>this listener's own
 * bounded queue</b>. A full queue drops the event and counts it. One thread per listener drains the queue and
 * calls {@link #onEvents} with what has accumulated, up to 500 events at a time. A listener that throws is logged
 * (rate-limited) and keeps receiving; one that blocks only fills - and then overflows - its own queue.
 */
public interface EventListener {

	/**
	 * The event types to deliver - the node emits only what someone subscribed to. Read when the listener is
	 * subscribed: at the plugin's start, and on a reload of its config ({@code FrontcachePlugin#eventListeners}).
	 */
	Set<Class<? extends FcEvent>> subscribes();

	/** Called on this listener's own thread, never on a request thread. May block: the thread is the listener's. */
	void onEvents(List<FcEvent> batch);
}
