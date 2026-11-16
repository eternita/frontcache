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
package org.frontcache.plugin.testkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.plugin.event.CacheInvalidated;
import org.frontcache.plugin.event.ChallengeIssued;
import org.frontcache.plugin.event.ChallengeResult;
import org.frontcache.plugin.event.CircuitStateChanged;
import org.frontcache.plugin.event.ConfigReloaded;
import org.frontcache.plugin.event.FallbackServed;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.event.GuardSummary;
import org.frontcache.plugin.event.OriginError;
import org.frontcache.plugin.event.PluginStateChanged;
import org.frontcache.plugin.event.RateLimitExceeded;
import org.frontcache.plugin.event.RequestCompleted;

/**
 * Plausible events of every type the API defines - what {@link PluginContract} feeds a listener, and a start for a
 * plugin's own tests (the records' constructors are public: build the exact event a test needs). A type this testkit
 * does not know (a later API's) is built by reflection, every component a neutral value.
 */
public final class SampleEvents {

	public static final String NODE = "fc-test-1";
	public static final String SITE = "www.example.com";
	public static final String CLIENT = "203.0.113.7";

	private SampleEvents() {
	}

	/** Every event type of this API. */
	public static List<Class<? extends FcEvent>> types() {
		return List.of(GuardMatched.class, RateLimitExceeded.class, GuardSummary.class, CircuitStateChanged.class,
				ConfigReloaded.class, PluginStateChanged.class, FallbackServed.class, OriginError.class,
				CacheInvalidated.class, RequestCompleted.class, ChallengeIssued.class, ChallengeResult.class);
	}

	/** A few events of the type - more than one, varied, a null where the event allows one. */
	public static List<FcEvent> of(Class<? extends FcEvent> type) {
		long t = System.currentTimeMillis();
		List<FcEvent> events = new ArrayList<>();
		if (GuardMatched.class == type)
		{
			events.add(new GuardMatched(t, NODE, SITE, "r-1", "block-wp", "rejected", 403, SITE + "/wp-login.php", "guest", CLIENT));
			events.add(new GuardMatched(t, NODE, SITE, "r-2", "go-home", "redirected", 302, SITE + "/old?x=1", "browser", "2001:db8::7"));
			events.add(new GuardMatched(t, NODE, SITE, null, "watch", "dry-run", 0, SITE + "/", null, CLIENT));
			events.add(new GuardMatched(t, NODE, null, "r-3", "prove-it", "challenged", 302, SITE + "/search?q=a", "guest", CLIENT));
		}
		else if (RateLimitExceeded.class == type)
		{
			events.add(new RateLimitExceeded(t, NODE, SITE, "r-4", "search", "search-rate", 60, 60_000, SITE + "/search?q=x", CLIENT));
			events.add(new RateLimitExceeded(t, NODE, null, null, "api", "api-rate", 10, 1_000, SITE + "/api", "2001:db8::7"));
		}
		else if (GuardSummary.class == type)
		{
			events.add(new GuardSummary(t, NODE, SITE, 10_000,
					List.of(new GuardSummary.RuleCount("block-wp", "rejected", 3), new GuardSummary.RuleCount("watch", "dry-run", 0)),
					List.of(new GuardSummary.BucketCount("search", 60, 60_000, 1))));
			events.add(new GuardSummary(t, NODE, null, 10_000, List.of(), List.of()));
		}
		else if (CircuitStateChanged.class == type)
		{
			events.add(new CircuitStateChanged(t, NODE, SITE, "Origin-Request", "CLOSED", "OPEN", 62.5f));
			events.add(new CircuitStateChanged(t, NODE, SITE, "Origin-Request", "OPEN", "HALF_OPEN", -1));
		}
		else if (ConfigReloaded.class == type)
		{
			events.add(new ConfigReloaded(t, NODE, SITE, "guard-rules.conf", true, 12, List.of()));
			events.add(new ConfigReloaded(t, NODE, SITE, "bots.conf", false, -1, List.of("line 3: unknown predicate")));
		}
		else if (PluginStateChanged.class == type)
		{
			events.add(new PluginStateChanged(t, NODE, SITE, "example", "started", null));
			events.add(new PluginStateChanged(t, NODE, SITE, "example", "failed", "IllegalStateException"));
		}
		else if (FallbackServed.class == type)
		{
			events.add(new FallbackServed(t, NODE, SITE, "r-5", "Cache-Miss", "timeout", SITE + "/p/1", null));
			events.add(new FallbackServed(t, NODE, SITE, null, "include", "fallback-failed", SITE + "/inc", "java.io.IOException"));
		}
		else if (OriginError.class == type)
		{
			events.add(new OriginError(t, NODE, SITE, "r-6", "Cache-Miss", "failure", 502, SITE + "/p/2", null, 35));
			events.add(new OriginError(t, NODE, SITE, null, "Cache-Bypass", "timeout", 0, SITE + "/p/3", "java.net.SocketTimeoutException", 5_000));
		}
		else if (CacheInvalidated.class == type)
		{
			events.add(new CacheInvalidated(t, NODE, SITE, CacheInvalidated.SCOPE_FILTER, "/products"));
			events.add(new CacheInvalidated(t, NODE, SITE, CacheInvalidated.SCOPE_ALL, null));
			events.add(new CacheInvalidated(t, NODE, SITE, CacheInvalidated.SCOPE_PURGE, null));
		}
		else if (RequestCompleted.class == type)
		{
			events.add(new RequestCompleted(t, NODE, SITE, "r-7", "GET", SITE + "/", "success", true, "from-cache", 3, 5120,
					CLIENT, "browser", "browsers", "Mozilla/5.0", null));
			events.add(new RequestCompleted(t, NODE, SITE, "r-8", "POST", SITE + "/cart", "error", false, "dynamic", 900, 0,
					"2001:db8::7", null, null, null, "timeout"));
		}
		else if (ChallengeIssued.class == type)
		{
			events.add(new ChallengeIssued(t, NODE, SITE, "r-9", "prove-it", "pow", "challenge", SITE + "/search?q=a", "guest", CLIENT));
		}
		else if (ChallengeResult.class == type)
		{
			events.add(new ChallengeResult(t, NODE, SITE, "r-10", "pow", "challenge", ChallengeResult.PASS, CLIENT));
			events.add(new ChallengeResult(t, NODE, SITE, "r-11", "pow", "challenge", ChallengeResult.ERROR, null));
		}
		else
			events.add(reflective(type, t));
		return events;
	}

	/** A batch with every sample of each of these types. */
	public static List<FcEvent> batchOf(Set<Class<? extends FcEvent>> types) {
		List<FcEvent> batch = new ArrayList<>();
		if (null != types)
			for (Class<? extends FcEvent> type : types)
				batch.addAll(of(type));
		return batch;
	}

	private static FcEvent reflective(Class<? extends FcEvent> type, long time) {
		if (!type.isRecord())
			throw new IllegalArgumentException("no sample of " + type.getName() + " - build one in the test");
		RecordComponent[] components = type.getRecordComponents();
		Class<?>[] types = new Class<?>[components.length];
		Object[] values = new Object[components.length];
		for (int i = 0; i < components.length; i++)
		{
			Class<?> c = components[i].getType();
			types[i] = c;
			String name = components[i].getName();
			if (long.class == c)
				values[i] = "time".equals(name) ? time : 0L;
			else if (int.class == c)
				values[i] = 0;
			else if (float.class == c)
				values[i] = 0f;
			else if (double.class == c)
				values[i] = 0d;
			else if (boolean.class == c)
				values[i] = false;
			else if (String.class == c)
				values[i] = "node".equals(name) ? NODE : "site".equals(name) ? SITE : "clientKey".equals(name) ? CLIENT : name;
			else if (List.class == c)
				values[i] = List.of();
			else if (Set.class == c)
				values[i] = Set.of();
			else if (Map.class == c)
				values[i] = Map.of();
			else
				values[i] = null;
		}
		try {
			Constructor<? extends FcEvent> constructor = type.getDeclaredConstructor(types);
			return constructor.newInstance(values);
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("no sample of " + type.getName() + " - build one in the test", e);
		}
	}
}
