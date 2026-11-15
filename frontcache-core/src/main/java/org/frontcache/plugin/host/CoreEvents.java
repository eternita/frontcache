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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import org.frontcache.FCConfig;
import org.frontcache.core.RequestContext;
import org.frontcache.core.FCHeaders;
import org.frontcache.plugin.event.CacheInvalidated;
import org.frontcache.plugin.event.ChallengeIssued;
import org.frontcache.plugin.event.ChallengeResult;
import org.frontcache.plugin.event.CircuitStateChanged;
import org.frontcache.plugin.event.ConfigReloaded;
import org.frontcache.plugin.event.FallbackServed;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.event.OriginError;
import org.frontcache.plugin.event.PluginStateChanged;
import org.frontcache.plugin.event.RateLimitExceeded;
import org.frontcache.plugin.event.RequestCompleted;

/**
 * The core's emit points, one method per event: each checks {@link FcEventBus#wants} before building anything, so
 * a node nobody observes pays one volatile read per point and allocates nothing. Never throws into its caller.
 */
public final class CoreEvents {

	/** The public domain of the engine that started the plugins; null when unset. */
	private static volatile String site = null;

	private CoreEvents() {
	}

	static void setSite(String publicDomain) {
		site = publicDomain;
	}

	static String site() {
		return site;
	}

	static String node() {
		return FCConfig.getProperty(FCConfig.FRONTCACHE_ID_KEY);
	}

	/** A guard rule acted on a request (rejected / redirected) or matched it as a dry run (status 0). */
	public static void guardMatched(RequestContext context, String rule, String disposition, int status) {
		if (!FcEventBus.wants(FcEventBus.GUARD_MATCHED))
			return;
		try {
			FcEventBus.publish(new GuardMatched(System.currentTimeMillis(), node(), site, context.getRequestId(), rule,
					disposition, status, uri(context), context.getClientType(), context.getRateLimitKey()));
		} catch (RuntimeException e) {
			// a request view that cannot answer (a recycled request) is no reason to fail the request
		}
	}

	/** A challenge rule sent a visitor to a provider's page - after the redirect is written. */
	public static void challengeIssued(RequestContext context, String rule, String provider, String plugin) {
		if (!FcEventBus.wants(FcEventBus.CHALLENGE_ISSUED))
			return;
		try {
			FcEventBus.publish(new ChallengeIssued(System.currentTimeMillis(), node(), site, context.getRequestId(), rule,
					provider, plugin, uri(context), context.getClientType(), context.getRateLimitKey()));
		} catch (RuntimeException e) {
			// as above
		}
	}

	/** A plugin's evidence signer signed a pass ({@code pass}) or was told a challenge was not passed. */
	static void challengeResult(RequestContext context, String provider, String plugin, String outcome) {
		if (!FcEventBus.wants(FcEventBus.CHALLENGE_RESULT))
			return;
		try {
			FcEventBus.publish(new ChallengeResult(System.currentTimeMillis(), node(), site, context.getRequestId(),
					provider, plugin, outcome, context.getRateLimitKey()));
		} catch (RuntimeException e) {
			// as above
		}
	}

	/** A client crossed a bucket's limit - called once per client per window, by the rate-limit predicate. */
	public static void rateLimitExceeded(RequestContext context, String bucket, String rule, int limit,
			long windowMillis, String clientKey) {
		if (!FcEventBus.wants(FcEventBus.RATE_LIMIT_EXCEEDED))
			return;
		try {
			FcEventBus.publish(new RateLimitExceeded(System.currentTimeMillis(), node(), site, context.getRequestId(),
					bucket, rule, limit, windowMillis, uri(context), clientKey));
		} catch (RuntimeException e) {
			// as above
		}
	}

	public static void circuitStateChanged(String commandKey, String from, String to, float failureRate) {
		if (!FcEventBus.wants(FcEventBus.CIRCUIT_STATE_CHANGED))
			return;
		FcEventBus.publish(new CircuitStateChanged(System.currentTimeMillis(), node(), site, commandKey, from, to,
				failureRate));
	}

	public static void configReloaded(String file, boolean ok, int entries, List<String> reasons) {
		if (!FcEventBus.wants(FcEventBus.CONFIG_RELOADED))
			return;
		FcEventBus.publish(new ConfigReloaded(System.currentTimeMillis(), node(), site, file, ok, entries,
				(null == reasons) ? List.of() : reasons));
	}

	static void pluginStateChanged(String plugin, String state, String reason) {
		if (!FcEventBus.wants(FcEventBus.PLUGIN_STATE_CHANGED))
			return;
		FcEventBus.publish(new PluginStateChanged(System.currentTimeMillis(), node(), site, plugin, state, reason));
	}

	/**
	 * A resilience command's origin call failed.
	 *
	 * @param context null for a call made outside a request (a management lookup)
	 * @param failure the command's failure event, as {@link #failureName} spells it
	 * @param status  the origin's status when it answered one outside 2xx-4xx; 0 otherwise
	 * @param cause   what the call threw; null for a refusal (circuit, bulkhead, pool)
	 */
	public static void originError(RequestContext context, String command, String failure, int status, String uri,
			Throwable cause, long latencyMillis) {
		if (!FcEventBus.wants(FcEventBus.ORIGIN_ERROR))
			return;
		try {
			FcEventBus.publish(new OriginError(System.currentTimeMillis(), node(), site, requestId(context), command,
					failure, status, truncate(uri), className(cause), latencyMillis));
		} catch (RuntimeException e) {
			// as above
		}
	}

	/**
	 * A fallback was served instead of the origin.
	 *
	 * @param context null for a call made outside a request
	 * @param cause   what the command threw; null for a refusal
	 */
	public static void fallbackServed(RequestContext context, String command, String failure, String uri,
			Throwable cause) {
		if (!FcEventBus.wants(FcEventBus.FALLBACK_SERVED))
			return;
		try {
			FcEventBus.publish(new FallbackServed(System.currentTimeMillis(), node(), site, requestId(context), command,
					failure, truncate(uri), className(cause)));
		} catch (RuntimeException e) {
			// as above
		}
	}

	/** @param filter null for {@code all} and {@code purge} */
	public static void cacheInvalidated(String scope, String filter) {
		if (!FcEventBus.wants(FcEventBus.CACHE_INVALIDATED))
			return;
		FcEventBus.publish(new CacheInvalidated(System.currentTimeMillis(), node(), site, scope, filter));
	}

	/**
	 * A request was logged. Only a top-level one is an event, and only when the draw falls in a subscriber's sample
	 * - so a node that is observed but not for requests pays one volatile read here, and one that is pays a random
	 * draw per request and builds the event for the sampled share only.
	 *
	 * @param cache {@code from-cache}, {@code dynamic}, {@code dynamic-soft} or {@code plugin:<id>}, as the request log
	 *              writes it
	 */
	public static void requestCompleted(RequestContext context, boolean cacheable, String cache,
			long runtimeMillis, long lengthBytes) {
		if (!FcEventBus.wants(FcEventBus.REQUEST_COMPLETED))
			return;
		try {
			if (!FCHeaders.COMPONENT_TOPLEVEL.equals(context.getRequestType()))
				return;
			double draw = ThreadLocalRandom.current().nextDouble();
			if (draw >= FcEventBus.requestSample())
				return;

			boolean error = context.isFallbackUsed();
			String method = (null == context.getRequest()) ? null : context.getRequest().getMethod();
			FcEventBus.publish(new RequestCompleted(System.currentTimeMillis(), node(), site, context.getRequestId(),
					method, uri(context), error ? "error" : "success", cacheable, cache, runtimeMillis, lengthBytes,
					context.getRateLimitKey(), context.getClientType(), context.getClientTypeRule(),
					truncate(context.getUserAgent()), error ? context.getFallbackReason() : null), draw);
		} catch (RuntimeException e) {
			// a request view that cannot answer (a recycled request) is no reason to fail the request
		}
	}

	/** A resilience event type as an event spells it: {@code TIMEOUT} -> {@code timeout}. */
	public static String failureName(Enum<?> failure) {
		return (null == failure) ? null : failure.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	private static String requestId(RequestContext context) {
		return (null == context) ? null : context.getRequestId();
	}

	private static String className(Throwable cause) {
		return (null == cause) ? null : cause.getClass().getName();
	}

	private static String truncate(String value) {
		if (null == value)
			return null;
		return (value.length() > FcEvent.MAX_URI_LENGTH) ? value.substring(0, FcEvent.MAX_URI_LENGTH) : value;
	}

	/** host + path + query, as the guard log line writes it, truncated. */
	public static String uri(RequestContext context) {
		String uri = Objects.toString(context.getFrontCacheHost(), "") + Objects.toString(context.getRequestURI(), "")
				+ Objects.toString(context.getRequestQueryString(), "");
		return (uri.length() > FcEvent.MAX_URI_LENGTH) ? uri.substring(0, FcEvent.MAX_URI_LENGTH) : uri;
	}
}
