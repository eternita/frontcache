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

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.FCConfig;
import org.frontcache.core.EvidenceCookies;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * E2 Serve: the paths plugins own, {@code /fc-x/<id>/}, and the challenge providers they offer.
 *
 * <h2>The prefix is reserved</h2>
 *
 * {@code FrontCacheEngine.processRequest} hands every request under {@value #PREFIX} here, after the guard rules and
 * before the cache - in server and filter mode alike. Such a request is never cached, never forwarded to the origin
 * and never a cache key; every answer carries {@code Cache-Control: no-store}. A path no started plugin owns answers
 * {@code 404}, which is what a node without the plugin answers. A site whose origin serves pages under {@code /fc-x/}
 * loses them to this - the release note says so.
 *
 * <h2>A plugin that fails is a plugin that is absent - for its own path only</h2>
 *
 * Each plugin has a concurrency cap ({@value #MAX_CONCURRENT_PROPERTY}, default {@value #DEFAULT_MAX_CONCURRENT}):
 * over it, {@code 503} with {@code Retry-After}, without reaching the plugin. Its response is buffered
 * ({@link CorePluginResponse}), so one that throws - or writes more than the buffer - answers {@code 503} and nothing
 * it began reaches the visitor; a body over {@value #MAX_BODY_PROPERTY} (default 64 KiB) answers {@code 413}. Every
 * answer is a request-log line whose is-cached column is {@code plugin:<id>} ({@code plugin:none} for a 404).
 */
public final class PluginEndpoints {

	private static final Logger logger = LoggerFactory.getLogger(PluginEndpoints.class);

	public static final String PREFIX = "/fc-x/";

	/** {@code front-cache.plugins.<id>.max-concurrent}: requests its endpoint answers at once. */
	static final String MAX_CONCURRENT_PROPERTY = "front-cache.plugins.%s.max-concurrent";
	static final int DEFAULT_MAX_CONCURRENT = 32;

	/** {@code front-cache.plugins.<id>.max-body}: the largest request body its endpoint is handed, in bytes. */
	static final String MAX_BODY_PROPERTY = "front-cache.plugins.%s.max-body";
	static final long DEFAULT_MAX_BODY = 64 * 1024;

	/** Retry-After on the 503 of a plugin over its cap. */
	static final int BUSY_RETRY_AFTER_SECONDS = 5;

	/** What the request log's is-cached column says when no plugin answered. */
	static final String NO_PLUGIN = "plugin:none";

	/** How often a failing endpoint is logged at ERROR. */
	private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;

	private static final String NO_STORE = "no-store, no-cache, max-age=0, must-revalidate";

	/** A provider's plugin, and the cookies a pass from it may be under - what the guard's challenge needs. */
	public record Challenge(String plugin, Set<String> cookies) {
	}

	/** One started plugin's endpoint. */
	static final class Served {
		final String id;
		final PluginManifest manifest;
		final PluginEndpoint endpoint;
		final PluginCounters counters;
		final Semaphore permits;
		final long maxBody;
		volatile long lastErrorLogged;

		Served(String id, PluginManifest manifest, PluginEndpoint endpoint, PluginCounters counters, int maxConcurrent,
				long maxBody) {
			this.id = id;
			this.manifest = manifest;
			this.endpoint = endpoint;
			this.counters = counters;
			this.permits = new Semaphore(maxConcurrent);
			this.maxBody = maxBody;
		}
	}

	/** By plugin id; replaced wholesale, never mutated. */
	private static volatile Map<String, Served> endpoints = Map.of();

	/** By provider; replaced wholesale, never mutated. */
	private static volatile Map<String, Challenge> providers = Map.of();

	private static volatile boolean running = false;

	private PluginEndpoints() {
	}

	/** @return whether {@code uri} is under the reserved prefix ({@code /fc-x} itself included) */
	public static boolean isReserved(String uri) {
		return null != uri && (uri.startsWith(PREFIX) || uri.equals(PREFIX.substring(0, PREFIX.length() - 1)));
	}

	/** Whether the plugins have been started (and not stopped) - before that no provider is known yet. */
	public static boolean isRunning() {
		return running;
	}

	/**
	 * The plugin offering {@code provider} - when one is started with an endpoint <b>and</b> the node can sign a
	 * pass. Null otherwise: a challenge no one can pass must not be issued.
	 */
	public static Challenge challenge(String provider) {
		if (null == provider)
			return null;
		Challenge challenge = providers.get(provider);
		return (null != challenge && EvidenceCookies.isConfigured()) ? challenge : null;
	}

	/** Called by FrontcachePlugins once the plugins have started. */
	static synchronized void install(Map<String, Served> served) {

		Map<String, Challenge> byProvider = new LinkedHashMap<>();
		for (Served s : served.values())
			for (String provider : s.manifest.challenges())
			{
				Challenge previous = byProvider.putIfAbsent(provider, new Challenge(s.id, s.manifest.cookies()));
				if (null != previous)
					logger.warn("Plugin '{}' offers challenge provider '{}', which plugin '{}' already offers - the first"
							+ " one wins", s.id, provider, previous.plugin());
			}

		endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(served));
		providers = Collections.unmodifiableMap(byProvider);
		running = true;

		for (Served s : served.values())
			logger.info("Plugin '{}' serves {}{}/ (at most {} at once){}", s.id, PREFIX, s.id,
					s.permits.availablePermits(), s.manifest.challenges().isEmpty() ? ""
							: " - challenge providers " + s.manifest.challenges());
		if (!byProvider.isEmpty() && !EvidenceCookies.isConfigured())
			logger.error("Challenge providers {} are started, but {} is not set - no pass can be signed, so every"
					+ " challenge rule acts as a dry run (or its else action)", byProvider.keySet(),
					EvidenceCookies.SECRET_PROPERTY);
	}

	/** A plugin started late - its config was fixed and reloaded - adds its endpoint to the running ones. */
	static synchronized void add(Map<String, Served> more) {
		Map<String, Served> all = new LinkedHashMap<>(endpoints);
		all.putAll(more);
		install(all);
	}

	/** Called by FrontcachePlugins as the plugins stop. */
	static synchronized void clear() {
		endpoints = Map.of();
		providers = Map.of();
		running = false;
	}

	static Served served(String id, PluginManifest manifest, PluginEndpoint endpoint, PluginCounters counters) {
		return new Served(id, manifest, endpoint, counters,
				(int) positive(String.format(MAX_CONCURRENT_PROPERTY, id), DEFAULT_MAX_CONCURRENT),
				positive(String.format(MAX_BODY_PROPERTY, id), DEFAULT_MAX_BODY));
	}

	private static long positive(String property, long defaultValue) {
		String value = FCConfig.getProperty(property);
		if (null == value)
			return defaultValue;
		try {
			long parsed = Long.parseLong(value.trim());
			if (0 < parsed && parsed <= Integer.MAX_VALUE)
				return parsed;
		} catch (NumberFormatException e) {
			// fall through
		}
		logger.warn("{} is not a positive number: '{}' - using {}", property, value, defaultValue);
		return defaultValue;
	}

	/**
	 * Answers a request under {@value #PREFIX}. Never throws: whatever goes wrong is the plugin's path answering 503.
	 *
	 * @param redirectAllowed what a plugin's redirect target is checked against - the guard's own rule
	 */
	public static void serve(RequestContext context, Predicate<String> redirectAllowed) {

		long start = System.currentTimeMillis();
		HttpServletResponse response = context.getResponse();
		HttpServletRequest request = context.getRequest();

		String rest = context.getRequestURI().length() > PREFIX.length() ? context.getRequestURI().substring(PREFIX.length()) : "";
		int slash = rest.indexOf('/');
		String id = (-1 < slash) ? rest.substring(0, slash) : rest;
		String path = (-1 < slash) ? rest.substring(slash) : "/";

		Served served = endpoints.get(id);
		if (null == served)
		{
			long length = plain(response, 404, "404 Not Found", 0);
			log(context, NO_PLUGIN, 404, start, length);
			return;
		}

		String label = "plugin:" + served.id;
		if (!served.permits.tryAcquire())
		{
			served.counters.serveRefused.increment();
			long length = plain(response, 503, "503 Service Unavailable", BUSY_RETRY_AFTER_SECONDS);
			log(context, label, 503, start, length);
			return;
		}

		int status;
		long length;
		try {
			long declared = (null == request) ? -1 : request.getContentLengthLong();
			if (declared > served.maxBody)
			{
				served.counters.serveRefused.increment();
				status = 413;
				length = plain(response, status, "413 Content Too Large", 0);
			}
			else
			{
				CorePluginRequest pluginRequest = new CorePluginRequest(context, served.id, path, served.manifest.cookies(),
						served.maxBody);
				CorePluginResponse pluginResponse = new CorePluginResponse(served.manifest.cookies(),
						"https".equals(pluginRequest.scheme()), redirectAllowed);
				status = handle(served, pluginRequest, pluginResponse);
				if (0 == status)
				{
					status = pluginResponse.status();
					length = pluginResponse.writeTo(response, NO_STORE);
					served.counters.served.increment();
				}
				else
					length = plain(response, status, (413 == status) ? "413 Content Too Large" : "503 Service Unavailable", 0);
			}
		} finally {
			served.permits.release();
		}

		log(context, label, status, start, length);
	}

	/** @return 0 when the endpoint answered, else the status the node answers for it */
	private static int handle(Served served, CorePluginRequest request, CorePluginResponse response) {
		try {
			served.endpoint.handle(request, response);
			if (response.overflowed())
				throw new java.io.IOException("the response is over " + CorePluginResponse.MAX_BODY + " bytes");
			return 0;
		} catch (CorePluginRequest.BodyTooLarge e) {
			served.counters.serveRefused.increment();
			return 413;
		} catch (Throwable t) {
			served.counters.serveFailed.increment();
			served.counters.errors.increment();
			long now = System.currentTimeMillis();
			if (now - served.lastErrorLogged > ERROR_LOG_INTERVAL_MILLIS)
			{
				served.lastErrorLogged = now;
				logger.error("Plugin '" + served.id + "' failed to answer " + PREFIX + served.id + request.path()
						+ " - answered 503 (logged at most once a minute)", t);
			}
			return 503;
		}
	}

	/** A one-line text answer of the node's own, no-store. @return the body's length */
	private static long plain(HttpServletResponse response, int status, String body, int retryAfterSeconds) {
		byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
		try {
			response.setStatus(status);
			response.setHeader(FCHeaders.CACHE_CONTROL, NO_STORE);
			response.setHeader("X-Content-Type-Options", "nosniff");
			if (0 < retryAfterSeconds)
				response.setHeader(FCHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds));
			response.setContentType("text/plain; charset=UTF-8");
			response.setContentLength(bytes.length);
			response.getOutputStream().write(bytes);
		} catch (Exception e) {
			logger.debug("Error writing a " + status + " for " + PREFIX + ": " + e);
		}
		return bytes.length;
	}

	private static void log(RequestContext context, String label, int status, long start, long length) {
		try {
			RequestLogger.logServedRequest(context.getFrontCacheHost() + context.getRequestURI()
					+ context.getRequestQueryString(), label, status, System.currentTimeMillis() - start, length, context);
		} catch (RuntimeException e) {
			logger.debug("Could not log a " + PREFIX + " request: " + e);
		}
	}

	/** For FrontcachePlugins' stats: whether a started plugin's endpoint is answering its path. */
	static boolean serves(String id) {
		return endpoints.containsKey(id);
	}
}
