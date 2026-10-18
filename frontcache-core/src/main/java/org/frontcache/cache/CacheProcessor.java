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
package org.frontcache.cache;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;

public interface CacheProcessor {

	public final static String CACHED_ENTRIES = "cached-entries";

	public final static long NO_CACHE = 0;

	public final static long CACHE_FOREVER = -1;

	public void init(Properties properties);

	public void destroy();

	public void putToCache(String url, WebResponse component);

	public WebResponse getFromCache(String url, RequestContext context);

	public void removeFromCache(String filter);

	/**
	 * Removes every entry from this node.
	 *
	 * There used to be a domain-scoped flavour alongside this one. It only dropped entries stamped
	 * with the caller's domain, and replicated entries carry the domain of the edge they came from -
	 * so an "invalidate all" at the target edge walked straight past them and they stayed served.
	 * With one site per node there is one flavour, and it means what it says.
	 */
	public void removeFromCacheAll();

	public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) throws FrontCacheException;

	public Map<String, String> getCacheStatus();

	/**
	 * A cheap size-and-churn snapshot for the metrics exporter. See {@link CacheStats}.
	 *
	 * A {@code default} on purpose, unlike the breaking change made to {@code FallbackResolver} in the
	 * same release. The difference is what a stale implementation does when it ignores the addition:
	 * here it reports "not tracked" and simply exports no cache-size series, which is true and harmless.
	 * A resolver that ignored ITS change would have served the wrong content during an outage. An
	 * additive read-only accessor earns a default; a changed contract does not.
	 *
	 * {@code front-cache.cache-processor.impl} is an extension point, so a third-party processor keeps
	 * compiling and running across this release either way.
	 */
	public default CacheStats getCacheStats() {
		return CacheStats.NOT_TRACKED;
	}

	public List<String> getCachedKeys();

	/**
	 * At most {@code limit} cached keys, in no particular order - for a caller that samples the key
	 * space (the cache warmer's key-base check) rather than listing it.
	 *
	 * A {@code default} because it is additive: a processor that does not override it answers from
	 * {@link #getCachedKeys()}, which is correct and merely as expensive as it always was. The
	 * built-in L1/L2 processor overrides it to stop reading the index once it has enough.
	 */
	public default List<String> getCachedKeys(int limit) {
		List<String> keys = getCachedKeys();
		return (keys.size() <= limit) ? keys : keys.subList(0, Math.max(0, limit));
	}

	public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context);

	public void patch();

	public void purge();

	/**
	 * Backs up the on-disk index (L2) to a sibling directory at the edge.
	 * Cache processors without a persistent index (in-memory, no-cache) leave this a no-op.
	 *
	 * @return the name of the created backup directory, or {@code null} if the processor has nothing to back up
	 */
	public default String backup() {
		return null;
	}
}
