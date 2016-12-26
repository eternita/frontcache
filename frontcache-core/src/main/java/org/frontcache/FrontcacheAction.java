package org.frontcache;

import java.util.Map;
import java.util.TreeMap;

public abstract class FrontcacheAction {

	
	public static final String INVALIDATE = "invalidate";
	
	public static final String DUMP_KEYS = "dump-keys";
	
	public static final String GET_FALLBACK_CONFIGS = "get-fallback-configs";

	public static final String RELOAD_FALLBACKS = "reload-fallbacks";
	
	public static final String GET_CACHE_STATE = "get-cache-state";

	public static final String GET_CACHED_KEYS = "get-cached-keys";
	
	public static final String GET_FROM_CACHE = "get-from-cache";
	
	public static final String GET_BOTS = "get-bots";
	
	public static final String GET_DYNAMIC_URLS = "get-dynamic-urls";
	
	public static Map<String, String> actionsDescriptionMap = new TreeMap<>();
	
	static {
		actionsDescriptionMap.put(FrontcacheAction.GET_CACHE_STATE, "get cache state: cache processor, amount cached items");
		actionsDescriptionMap.put(FrontcacheAction.GET_FALLBACK_CONFIGS, "get fallback configs from ./conf/fallbacks.conf");
		actionsDescriptionMap.put(FrontcacheAction.RELOAD_FALLBACKS, "reload fallback configs from ./conf/fallbacks.conf");
		actionsDescriptionMap.put(FrontcacheAction.INVALIDATE, "Invalidate chache, accept 'filter' param with regexp for invalidation");
		actionsDescriptionMap.put(FrontcacheAction.DUMP_KEYS, "dump keys to a file at the edge - keys are saved to ./warmer dir");
		actionsDescriptionMap.put(FrontcacheAction.GET_CACHED_KEYS, "get cached keys");
		actionsDescriptionMap.put(FrontcacheAction.GET_FROM_CACHE, "get content from cache, accept 'key' parameter");
		actionsDescriptionMap.put(FrontcacheAction.GET_BOTS, "get substring to determine bots based on User-Agent HTTP header from ./conf/bots.conf");
		actionsDescriptionMap.put(FrontcacheAction.GET_DYNAMIC_URLS, "get dynamic URL patterns (patterns are not processed by FrontCache)  fromt ./conf/dynamic-urls.conf");
	}
	
}
