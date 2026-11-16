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
package org.frontcache.examples.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.frontcache.plugin.event.ChallengeIssued;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.event.RateLimitExceeded;

/**
 * E1: the guard's recent decisions, per visitor - the last {@code recent} of each of the last {@code visitors}
 * visitors seen, the least recently seen dropped first. Keyed by {@code PluginContext.hashClientKey} of the event's
 * {@code clientKey} (the address the guard counted), never by the address itself: what this keeps is personal data
 * only through a hash, and only for as long as the process lives.
 */
final class RecentDecisions implements EventListener {

	/** One decision, as the page shows it. */
	record Decision(long time, String event, String name, String outcome, int status, String uri, String requestId) {
	}

	private final LinkedHashMap<String, ArrayDeque<Decision>> byVisitor = new LinkedHashMap<>(64, 0.75f, true);
	private volatile UnaryOperator<String> hash;
	private int recent = DebugConfig.DEFAULT_RECENT;
	private int visitors = DebugConfig.DEFAULT_VISITORS;

	@Override
	public Set<Class<? extends FcEvent>> subscribes() {
		return Set.of(GuardMatched.class, RateLimitExceeded.class, ChallengeIssued.class);
	}

	/** The node's keyed hash - {@code PluginContext::hashClientKey}, set at start; until then nothing is kept. */
	void hashWith(UnaryOperator<String> hash) {
		this.hash = hash;
	}

	synchronized void limits(int recent, int visitors) {
		this.recent = recent;
		this.visitors = visitors;
		for (ArrayDeque<Decision> decisions : byVisitor.values())
			while (decisions.size() > recent)
				decisions.removeLast();
		trim();
	}

	@Override
	public void onEvents(List<FcEvent> batch) {
		UnaryOperator<String> h = hash;
		if (null == h)
			return;
		for (FcEvent event : batch)
		{
			String key;
			Decision decision;
			if (event instanceof GuardMatched g)
			{
				key = g.clientKey();
				decision = new Decision(g.time(), "guard", g.rule(), g.disposition(), g.status(), g.uri(), g.requestId());
			}
			else if (event instanceof RateLimitExceeded r)
			{
				key = r.clientKey();
				decision = new Decision(r.time(), "rate limit", r.bucket(), "over " + r.limit() + " per "
						+ r.windowMillis() / 1000.0 + " s (rule " + r.rule() + ")", 0, r.uri(), r.requestId());
			}
			else if (event instanceof ChallengeIssued c)
			{
				key = c.clientKey();
				decision = new Decision(c.time(), "challenge", c.rule(), "sent to " + c.provider() + " (plugin "
						+ c.plugin() + ")", 302, c.uri(), c.requestId());
			}
			else
				continue;
			String visitor = (null == key) ? null : h.apply(key);
			if (null != visitor)
				add(visitor, decision);
		}
	}

	private synchronized void add(String visitor, Decision decision) {
		ArrayDeque<Decision> decisions = byVisitor.computeIfAbsent(visitor, v -> new ArrayDeque<>());
		decisions.addFirst(decision);
		while (decisions.size() > recent)
			decisions.removeLast();
		trim();
	}

	private void trim() {
		Iterator<Map.Entry<String, ArrayDeque<Decision>>> eldest = byVisitor.entrySet().iterator();
		while (byVisitor.size() > visitors && eldest.hasNext())
		{
			eldest.next();
			eldest.remove();
		}
	}

	/** The decisions about one visitor, newest first; empty for an address the node did not resolve. */
	List<Decision> about(String clientAddress) {
		UnaryOperator<String> h = hash;
		if (null == h || null == clientAddress)
			return List.of();
		String visitor = h.apply(clientAddress);
		synchronized (this) {
			ArrayDeque<Decision> decisions = byVisitor.get(visitor);
			return (null == decisions) ? List.of() : new ArrayList<>(decisions);
		}
	}

	synchronized int visitorCount() {
		return byVisitor.size();
	}
}
