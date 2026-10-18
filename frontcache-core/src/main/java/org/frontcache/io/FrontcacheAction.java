/*
 * Copyright 2015 Eternita
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
package org.frontcache.io;

import java.util.Map;
import java.util.TreeMap;

public abstract class FrontcacheAction {


	public static final String INVALIDATE = "invalidate";

	public static final String DUMP_KEYS = "dump-keys";

	public static final String GET_FALLBACK_CONFIGS = "get-fallback-configs";

	/**
	 * The raw conf/fallbacks.conf, as {@link #GET_GUARD_CONFIG} is to {@link #GET_GUARD_RULES}: this
	 * one returns the file, {@link #GET_FALLBACK_CONFIGS} returns the entries parsed from it.
	 *
	 * The two spellings are one letter apart and mean different things. The older one is plural
	 * because it counts ENTRIES; the plural here belongs to the file name.
	 */
	public static final String GET_FALLBACKS_CONFIG = "get-fallbacks-config";

	public static final String RELOAD_FALLBACKS = "reload-fallbacks";

	public static final String GET_CACHE_STATE = "get-cache-state";

	/**
	 * The cheap, pollable companion of {@link #GET_CACHE_STATE}: typed cache sizes plus the JVM's heap,
	 * CPU and uptime, all read from state the process already holds. {@code get-cache-state} opens a
	 * Lucene reader per call, which is fine for a console page and wrong for a poll loop.
	 */
	public static final String GET_NODE_STATS = "get-node-stats";

	public static final String GET_CACHED_KEYS = "get-cached-keys";

	public static final String GET_FROM_CACHE = "get-from-cache";

	public static final String PUT_TO_CACHE = "put-to-cache";

	public static final String GET_BOTS = "get-bots";

	/**
	 * The raw conf/bots.conf, as {@link #GET_GUARD_CONFIG} is to {@link #GET_GUARD_RULES}: this one
	 * returns the file, {@link #GET_BOTS} returns what the node parsed out of it.
	 */
	public static final String GET_BOTS_CONFIG = "get-bots-config";

	public static final String RELOAD_BOTS = "reload-bots";

	public static final String GET_DYNAMIC_URLS = "get-dynamic-urls";

	/**
	 * The raw conf/dynamic-urls.conf, as {@link #GET_GUARD_CONFIG} is to {@link #GET_GUARD_RULES}:
	 * this one returns the file, {@link #GET_DYNAMIC_URLS} returns the patterns parsed from it.
	 */
	public static final String GET_DYNAMIC_URLS_CONFIG = "get-dynamic-urls-config";

	public static final String GET_RESILIENCE_CONFIGS = "get-resilience-configs";

	/**
	 * Pre-2.7 spelling of {@link #GET_RESILIENCE_CONFIGS}, still accepted. The console is deployed
	 * separately from the nodes it reads, so a 2.6 console asking a 2.7 node has to keep working - an
	 * unrecognized action answers with a ping response, which would render the panel empty with no
	 * error. Kept until no supported console predates 2.7.
	 *
	 * @deprecated send {@link #GET_RESILIENCE_CONFIGS} instead
	 */
	@Deprecated
	public static final String GET_HYSTRIX_CONFIGS = "get-hystrix-configs";

	public static final String GET_GUARD_RULES = "get-guard-rules";

	public static final String GET_GUARD_CONFIG = "get-guard-config";

	public static final String RELOAD_GUARD_RULES = "reload-guard-rules";

	/**
	 * Writes one config file and (unless {@code apply=false}) applies it - the console's Configs
	 * screens save through this.
	 *
	 * <b>One action for all five files, and the {@code file} parameter is a logical id, never a path
	 * or a file name</b> - {@code resilience}, {@code guard-rules}, {@code bots},
	 * {@code dynamic-urls}, {@code fallbacks}. The read side has five actions because each returns a
	 * differently-named field for wire-format reasons that predate the current shape; the write side
	 * has one uniform outcome, so five actions would be one operation spelled five times. And a
	 * caller-supplied file name - even one checked for {@code ..} - is how this becomes an arbitrary
	 * file write on a node, so it is not accepted at all.
	 *
	 * Unlike every other action here this one refuses a node with no API key configured, where
	 * {@code ApiKey.isAuthorized} allows it. See {@code ApiKey.isAuthorizedToWrite}.
	 */
	public static final String SET_CONFIG = "set-config";

	/**
	 * Re-reads ./conf/resilience.properties and applies what can be applied without a restart.
	 *
	 * New because there was no way to apply that file at all: it is read once into a cached singleton
	 * and two further caches are built from it. See {@code FcResilienceReload}.
	 */
	public static final String RELOAD_RESILIENCE = "reload-resilience";

	/**
	 * Re-reads ./conf/dynamic-urls.conf.
	 *
	 * New for the same reason: the file is read by FCConfig's static initializer, and the {@code reload}
	 * action does NOT re-read it (the FCConfig.init() call in FrontCacheEngine is commented out), so
	 * adding a pattern meant restarting the node.
	 */
	public static final String RELOAD_DYNAMIC_URLS = "reload-dynamic-urls";

	public static final String PATCH = "patch";

	public static final String PURGE = "purge";

	public static final String BACKUP_INDEX = "backup-index";

	public static final String PING = "ping";

	public static final String HELP = "help";

	public static Map<String, String> actionsDescriptionMap = new TreeMap<>();

	static {
		actionsDescriptionMap.put(FrontcacheAction.GET_CACHE_STATE, "get cache state: cache processor, amount cached items");
		actionsDescriptionMap.put(FrontcacheAction.GET_NODE_STATS, "get node stats: cache sizes per tier plus JVM heap, process CPU and uptime - cheap enough to poll");
		actionsDescriptionMap.put(FrontcacheAction.GET_FALLBACK_CONFIGS, "get fallback configs from ./conf/fallbacks.conf");
		actionsDescriptionMap.put(FrontcacheAction.GET_FALLBACKS_CONFIG, "get raw fallback configuration from ./conf/fallbacks.conf");
		actionsDescriptionMap.put(FrontcacheAction.RELOAD_FALLBACKS, "reload fallback configs from ./conf/fallbacks.conf");
		actionsDescriptionMap.put(FrontcacheAction.INVALIDATE, "Invalidate chache, accept 'filter' param with url/tag for invalidation ('*' - everything). '*' empties the whole node, replicated entries included");
		actionsDescriptionMap.put(FrontcacheAction.DUMP_KEYS, "dump keys to a file at the edge - keys are saved to ./warmer dir");
		actionsDescriptionMap.put(FrontcacheAction.GET_CACHED_KEYS, "get cached keys");
		actionsDescriptionMap.put(FrontcacheAction.GET_FROM_CACHE, "get content from cache, accept 'key' parameter");
		actionsDescriptionMap.put(FrontcacheAction.PUT_TO_CACHE, "store a WebResponse entry into the local cache (used for replication); accepts 'key', 'domain', and 'webResponse' (JSON) parameters");
		actionsDescriptionMap.put(FrontcacheAction.GET_BOTS, "get bot classification rules (in evaluation order, with hit counts) and bare User-Agent keywords from ./conf/bots.conf");
		actionsDescriptionMap.put(FrontcacheAction.GET_BOTS_CONFIG, "get raw bot classification configuration from ./conf/bots.conf");
		actionsDescriptionMap.put(FrontcacheAction.RELOAD_BOTS, "reload bot classification rules from ./conf/bots.conf (resets hit counters)");
		actionsDescriptionMap.put(FrontcacheAction.GET_DYNAMIC_URLS, "get dynamic URL patterns (patterns are not processed by FrontCache)  fromt ./conf/dynamic-urls.conf");
		actionsDescriptionMap.put(FrontcacheAction.GET_DYNAMIC_URLS_CONFIG, "get raw dynamic URL configuration from ./conf/dynamic-urls.conf");
		actionsDescriptionMap.put(FrontcacheAction.GET_RESILIENCE_CONFIGS, "get raw resilience configuration from ./conf/resilience.properties");
		actionsDescriptionMap.put(FrontcacheAction.GET_HYSTRIX_CONFIGS, "deprecated alias of " + FrontcacheAction.GET_RESILIENCE_CONFIGS);
		actionsDescriptionMap.put(FrontcacheAction.GET_GUARD_RULES, "get guard rules (checks running before cache & origin) in evaluation order, with hit counts - built-ins plus ./conf/guard-rules.conf");
		actionsDescriptionMap.put(FrontcacheAction.GET_GUARD_CONFIG, "get raw guard rules configuration from ./conf/guard-rules.conf");
		actionsDescriptionMap.put(FrontcacheAction.RELOAD_GUARD_RULES, "reload guard rules from ./conf/guard-rules.conf (resets hit counters)");
		actionsDescriptionMap.put(FrontcacheAction.SET_CONFIG, "write one config file and apply it; accepts 'file' (resilience|guard-rules|bots|dynamic-urls|fallbacks), 'content', optional 'baseHash' (sha-256 of the content being replaced) and optional 'apply' (default true). Requires a configured API key");
		actionsDescriptionMap.put(FrontcacheAction.RELOAD_RESILIENCE, "reload ./conf/resilience.properties (resets circuit-breaker state; a changed threadpool maxQueueSize still needs a restart)");
		actionsDescriptionMap.put(FrontcacheAction.RELOAD_DYNAMIC_URLS, "reload dynamic URL patterns from ./conf/dynamic-urls.conf");
		actionsDescriptionMap.put(FrontcacheAction.PURGE, "purge expired entries from the cache (keeps non-expired and cache-forever entries)");
		actionsDescriptionMap.put(FrontcacheAction.BACKUP_INDEX, "backup the L2 (Lucene) index to a zip archive at the edge (l2-lucene-index.backup-{yyyyMMdd_HHmmss}.zip)");
		actionsDescriptionMap.put(FrontcacheAction.PING, "ping for GSLB");
		actionsDescriptionMap.put(FrontcacheAction.HELP, "help");
	}

}
