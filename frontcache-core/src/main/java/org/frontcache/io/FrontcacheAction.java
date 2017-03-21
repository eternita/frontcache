/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

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

	public static final String PATCH = "patch";
	
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
