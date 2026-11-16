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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.frontcache.guard.GuardRule;
import org.frontcache.plugin.event.CacheInvalidated;
import org.frontcache.plugin.event.ChallengeIssued;
import org.frontcache.plugin.event.ChallengeResult;
import org.frontcache.plugin.event.CircuitStateChanged;
import org.frontcache.plugin.event.ConfigReloaded;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FallbackServed;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.event.GuardSummary;
import org.frontcache.plugin.event.OriginError;
import org.frontcache.plugin.event.PluginStateChanged;
import org.frontcache.plugin.event.RateLimitExceeded;
import org.frontcache.plugin.event.RequestCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The E1 event bus: where the node hands events to the plugins that observe it.
 *
 * <h2>On the request path</h2>
 *
 * An emit point asks {@link #wants(long)} - one volatile read of a bitmask, no allocation - and only when some
 * listener subscribed to that type does it build the event and {@link #publish} it, which {@code offer}s it into
 * each subscriber's <b>own bounded queue</b>. A full queue drops the event and counts it; nothing here ever blocks
 * a request thread, and a listener that blocks only fills its own queue.
 *
 * <h2>Delivery</h2>
 *
 * One daemon thread per subscriber drains its queue and calls {@link EventListener#onEvents} with whatever has
 * accumulated, up to {@value #MAX_BATCH} at a time: prompt when traffic is quiet, batched when it is not. A listener
 * that throws is logged (once a minute) and keeps receiving.
 *
 * <h2>Sampled events</h2>
 *
 * {@link RequestCompleted} is the one event that can come from every request, so each subscriber names the share
 * it wants ({@code front-cache.plugins.<id>.request-sample}). The emit point draws one number per request and
 * builds the event only when the draw is under the largest share wanted ({@link #requestSample()}); each subscriber
 * then takes it when the same draw is under its own share - so a subscriber's sample is a subset of any larger one.
 *
 * <p>Subscribers are replaced wholesale on every engine start ({@link FrontcachePlugins}), and one plugin's on a
 * reload of its config ({@link #resubscribe}); {@link #stopAll()} on engine stop counts whatever is still queued as
 * dropped.
 */
public final class FcEventBus {

	private static final Logger logger = LoggerFactory.getLogger(FcEventBus.class);

	public static final long GUARD_MATCHED = 1L;
	public static final long RATE_LIMIT_EXCEEDED = 1L << 1;
	public static final long GUARD_SUMMARY = 1L << 2;
	public static final long CIRCUIT_STATE_CHANGED = 1L << 3;
	public static final long CONFIG_RELOADED = 1L << 4;
	public static final long PLUGIN_STATE_CHANGED = 1L << 5;
	public static final long FALLBACK_SERVED = 1L << 6;
	public static final long ORIGIN_ERROR = 1L << 7;
	public static final long CACHE_INVALIDATED = 1L << 8;
	public static final long REQUEST_COMPLETED = 1L << 9;
	public static final long CHALLENGE_ISSUED = 1L << 10;
	public static final long CHALLENGE_RESULT = 1L << 11;

	/** Every event type this node emits, and its bit. A type a listener names that is not here is never emitted. */
	static final Map<Class<? extends FcEvent>, Long> BITS = Map.ofEntries(
			Map.entry(GuardMatched.class, GUARD_MATCHED),
			Map.entry(RateLimitExceeded.class, RATE_LIMIT_EXCEEDED),
			Map.entry(GuardSummary.class, GUARD_SUMMARY),
			Map.entry(CircuitStateChanged.class, CIRCUIT_STATE_CHANGED),
			Map.entry(ConfigReloaded.class, CONFIG_RELOADED),
			Map.entry(PluginStateChanged.class, PLUGIN_STATE_CHANGED),
			Map.entry(FallbackServed.class, FALLBACK_SERVED),
			Map.entry(OriginError.class, ORIGIN_ERROR),
			Map.entry(CacheInvalidated.class, CACHE_INVALIDATED),
			Map.entry(RequestCompleted.class, REQUEST_COMPLETED),
			Map.entry(ChallengeIssued.class, CHALLENGE_ISSUED),
			Map.entry(ChallengeResult.class, CHALLENGE_RESULT));

	public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

	static final int MAX_BATCH = 500;

	private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;

	/** The union of every subscriber's mask - what {@link #wants} reads. */
	private static volatile long mask = 0L;

	private static volatile List<Subscriber> subscribers = List.of();

	/** The largest {@link RequestCompleted} share any subscriber wants; 0 when none subscribed. */
	private static volatile double requestSample = 0.0;

	private static GuardSummaryTicker ticker = null;

	private FcEventBus() {
	}

	/** Whether any listener subscribed to the event type with this bit - check it before building the event. */
	public static boolean wants(long bit) {
		return 0L != (mask & bit);
	}

	/** The largest share of requests a subscriber wants as {@link RequestCompleted}; 0 when none does. */
	public static double requestSample() {
		return requestSample;
	}

	/** Offers the event to every subscriber of its type; never blocks. */
	public static void publish(FcEvent event) {
		publish(event, 0.0);
	}

	/**
	 * Offers a sampled event to every subscriber of its type whose share is above {@code draw}; never blocks.
	 *
	 * @param draw the emit point's draw in [0, 1) - see "Sampled events"
	 */
	public static void publish(FcEvent event, double draw) {

		Long bit = BITS.get(event.getClass());
		if (null == bit || !wants(bit))
			return;

		for (Subscriber subscriber : subscribers)
			subscriber.offer(event, bit, draw);
	}

	/** {@link #subscribe(String, EventListener, int, double, PluginCounters)} with the default request sample. */
	static long subscribe(String pluginId, EventListener listener, int capacity, PluginCounters counters) {
		return subscribe(pluginId, listener, capacity, RequestCompleted.DEFAULT_SAMPLE, counters);
	}

	/**
	 * Starts a dispatcher for one listener.
	 *
	 * @param requestSample the share of requests it gets as {@link RequestCompleted}, in (0, 1]
	 * @return the bits the listener subscribed to that this node emits
	 */
	static synchronized long subscribe(String pluginId, EventListener listener, int capacity, double requestSample,
			PluginCounters counters) {

		long listenerMask = maskOf(pluginId, listener, true);
		if (0L == listenerMask)
		{
			logger.warn("Plugin '{}' has an event listener that subscribes to nothing this node emits", pluginId);
			return 0L;
		}

		double sample = (0L == (listenerMask & REQUEST_COMPLETED)) ? 0.0 : Math.min(1.0, Math.max(0.0, requestSample));
		Subscriber subscriber = new Subscriber(pluginId, listener, listenerMask, Math.max(1, capacity), sample, counters);
		List<Subscriber> next = new ArrayList<>(subscribers);
		next.add(subscriber);
		subscribers = Collections.unmodifiableList(next);
		FcEventBus.requestSample = Math.max(FcEventBus.requestSample, sample);
		mask |= listenerMask;
		subscriber.start();
		return listenerMask;
	}

	/**
	 * Replaces one plugin's listeners - what a reload of its config does, since a new config may want other events.
	 * A listener the plugin hands back unchanged (the same instance, subscribing to the same types) keeps its queue
	 * and thread; one it no longer hands back is stopped, and what was still queued for it is counted as dropped.
	 *
	 * @return the bits the plugin's listeners now subscribe to that this node emits
	 */
	static synchronized long resubscribe(String pluginId, List<EventListener> listeners, int capacity,
			double requestSample, PluginCounters counters) {

		List<Subscriber> kept = new ArrayList<>();
		List<EventListener> added = new ArrayList<>();
		for (EventListener listener : listeners)
		{
			Subscriber same = null;
			for (Subscriber s : subscribers)
				if (s.pluginId.equals(pluginId) && s.listener == listener && s.mask == maskOf(pluginId, listener, false))
				{
					same = s;
					break;
				}
			if (null == same)
				added.add(listener);
			else
				kept.add(same);
		}

		List<Subscriber> next = new ArrayList<>();
		List<Subscriber> stopping = new ArrayList<>();
		for (Subscriber s : subscribers)
			if (!s.pluginId.equals(pluginId) || kept.contains(s))
				next.add(s);
			else
				stopping.add(s);

		subscribers = Collections.unmodifiableList(next);
		recompute();
		for (Subscriber s : stopping)
			s.stop();

		long bits = 0L;
		for (Subscriber s : kept)
			bits |= s.mask;
		for (EventListener listener : added)
			bits |= subscribe(pluginId, listener, capacity, requestSample, counters);
		return bits;
	}

	/** The bits a listener subscribes to that this node emits; WARNs about the others when asked to. */
	private static long maskOf(String pluginId, EventListener listener, boolean warn) {
		Set<Class<? extends FcEvent>> types = listener.subscribes();
		long listenerMask = 0L;
		if (null != types)
			for (Class<? extends FcEvent> type : types)
			{
				Long bit = BITS.get(type);
				if (null != bit)
					listenerMask |= bit;
				else if (warn)
					logger.warn("Plugin '{}' subscribes to {}, which this node does not emit - ignored", pluginId,
							(null == type) ? "null" : type.getName());
			}
		return listenerMask;
	}

	/** The union mask and the largest request sample, from the subscribers left. */
	private static void recompute() {
		long union = 0L;
		double sample = 0.0;
		for (Subscriber s : subscribers)
		{
			union |= s.mask;
			sample = Math.max(sample, s.requestSample);
		}
		mask = union;
		requestSample = sample;
	}

	/** Starts the 10-second {@link GuardSummary} ticker - only when someone subscribed to it. */
	static synchronized void startGuardSummary(Supplier<List<GuardRule>> rules, long intervalMillis) {
		if (null != ticker || !wants(GUARD_SUMMARY))
			return;
		ticker = new GuardSummaryTicker(rules, intervalMillis);
		ticker.start();
	}

	/** Stops the ticker and every dispatcher; what is still queued is counted as dropped. */
	static synchronized void stopAll() {

		if (null != ticker)
		{
			ticker.stop();
			ticker = null;
		}

		List<Subscriber> stopping = subscribers;
		mask = 0L;
		requestSample = 0.0;
		subscribers = List.of();
		for (Subscriber subscriber : stopping)
			subscriber.stop();
	}

	/** One listener: its queue, its thread, its share of the plugin's counters. */
	static final class Subscriber implements Runnable {

		private final String pluginId;
		private final EventListener listener;
		private final long mask;
		private final double requestSample;
		private final ArrayBlockingQueue<FcEvent> queue;
		private final PluginCounters counters;
		private final Thread thread;
		private volatile boolean running = true;
		private long lastErrorLog = 0L;

		Subscriber(String pluginId, EventListener listener, long mask, int capacity, double requestSample,
				PluginCounters counters) {
			this.pluginId = pluginId;
			this.listener = listener;
			this.mask = mask;
			this.requestSample = requestSample;
			this.queue = new ArrayBlockingQueue<>(capacity);
			this.counters = counters;
			this.thread = new Thread(this, "fc-plugin-" + pluginId + "-events");
			this.thread.setDaemon(true);
		}

		void start() {
			thread.start();
		}

		void offer(FcEvent event, long bit, double draw) {
			if (0L == (mask & bit))
				return;
			if (REQUEST_COMPLETED == bit && draw >= requestSample)
				return; // not in this subscriber's sample
			if (!queue.offer(event))
				counters.dropped.increment();
		}

		void stop() {
			running = false;
			thread.interrupt();
			try {
				thread.join(1000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			int left = queue.size();
			queue.clear();
			counters.dropped.add(left);
		}

		@Override
		public void run() {
			List<FcEvent> batch = new ArrayList<>(MAX_BATCH);
			while (running)
			{
				FcEvent first;
				try {
					first = queue.poll(500, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					return;
				}
				if (null == first)
					continue;

				batch.clear();
				batch.add(first);
				queue.drainTo(batch, MAX_BATCH - 1);
				try {
					listener.onEvents(Collections.unmodifiableList(new ArrayList<>(batch)));
					counters.delivered.add(batch.size());
				} catch (Throwable t) {
					counters.errors.increment();
					long now = System.currentTimeMillis();
					if (now - lastErrorLog >= ERROR_LOG_INTERVAL_MILLIS)
					{
						lastErrorLog = now;
						logger.error("Plugin '" + pluginId + "' threw from its event listener - it keeps receiving"
								+ " (logged once a minute)", t);
					}
				}
			}
		}
	}
}
