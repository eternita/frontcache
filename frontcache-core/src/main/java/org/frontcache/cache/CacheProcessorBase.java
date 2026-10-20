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
package org.frontcache.cache;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.FC_ThroughCache;
import org.frontcache.reqlog.RequestLogger;
import org.frontcache.warmer.WarmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CacheProcessorBase implements CacheProcessor {

	protected Logger logger = LoggerFactory.getLogger(getClass());

    protected ExecutorService softInvalidationExecutor = null;

	private static final String[] NON_PERSISTENT_HEADERS = new String[]{
			"Set-Cookie",
			"Date",
			FCHeaders.X_FRONTCACHE_ID,
			FCHeaders.X_FRONTCACHE_COMPONENT,
//			FCHeaders.X_FRONTCACHE_FALLBACK_IS_USED, - if you see it in cache - something wrong - requests with fallbacks should not be cached
//			FCHeaders.X_FRONTCACHE_COMPONENT_CACHE_LEVEL,
//			FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE,
//			FCHeaders.X_FRONTCACHE_COMPONENT_REFRESH_TYPE,
//			FCHeaders.X_FRONTCACHE_SOFT_REFRESH,
//			FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, //  tags should not be filtered by FC (e.g. client -> fc2 (standalone) -> fc1 (filter) -> origin)
			FCHeaders.X_FRONTCACHE_REQUEST_ID,
			FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL,
			FCHeaders.X_FRONTCACHE_CLIENT_IP,
			FCHeaders.X_FRONTCACHE_TRACE,
			FCHeaders.X_FRONTCACHE_TRACE_REQUEST
		};

	public abstract WebResponse getFromCacheImpl(String url);

	/**
	 * Stores the entry. Called by {@link #putToCache(String, WebResponse)} once the headers
	 * that must never be persisted (Set-Cookie in particular) have been removed.
	 *
	 * @param url cache key
	 * @param component response to store - the cache takes ownership of it
	 */
	public abstract void putToCacheImpl(String url, WebResponse component);

	/**
	 * Single entry point for writing to the cache.
	 *
	 * The header strip lives here rather than in the callers so that no write path can bypass it -
	 * the origin/refresh paths below, the admin API (FrontCacheIOServlet) and replication all end up
	 * here. Caching a Set-Cookie would replay one visitor's session cookie to every later visitor.
	 *
	 * The response is modified in place, so callers must hand over an instance that is not being
	 * served to a client (the origin paths below pass a copy() - that is what keeps the visitor's own
	 * Set-Cookie on the live response of a cache miss).
	 */
	@Override
	public final void putToCache(String url, WebResponse component) {

		if (null == component)
			return;

		cleanupNonPersistentHeaders(component.getHeaders());
		putToCacheImpl(url, component);
	}

	@Override
	public final WebResponse getFromCache(String url, RequestContext context)
	{
		WebResponse cachedWebResponse = new FC_ThroughCache(this, url, context).execute();

		return cachedWebResponse;
	}


	@Override
	public WebResponse processRequest(String originUrlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) throws FrontCacheException {

		long start = System.currentTimeMillis();
		boolean isRequestCacheable = true;
		boolean isCached = false;

		long lengthBytes = -1;

		String currentRequestURL = context.getCurrentRequestURL();

		WebResponse cachedWebResponse = new FC_ThroughCache(this, currentRequestURL, context).execute();

		// isDynamicForClientType depends on clientType (bot|guest) - maxAge="[bot|guest:]30d"
		// content is cached for bots and dynamic for guests
		// when dynamic - don't update cache
		boolean isCacheableForClientType = true; // true - save/update to cache (default value is incorrect when include is pure dynamic);  false - don't save/update to cache

		boolean isWarmRequest = context.isWarmRequest();

		if (null != cachedWebResponse)
		{
			String clientType = context.getClientType(); // bot | guest
			Map<String, Long> expireTimeMap = cachedWebResponse.getExpireTimeMap();

			isCacheableForClientType = FCUtils.isWebComponentCacheableForClientType(expireTimeMap, clientType);

			if (isCacheableForClientType && isWarmRequest && WarmRequest.MODE_REFRESH.equals(context.getWarmMode()))
			{
				// A refresh-mode warm run reads past the entry, fresh or not, and the fetch below
				// overwrites it. It is NOT removed first - the reasoning of doSoftInvalidation: removing
				// opens a one-round-trip window in which every visitor misses, and a refresh whose
				// origin call fails would leave the key empty instead of leaving the old copy serving.
				cachedWebResponse = null;

			// if data is cacheable for client type -> check data for expiration (only)
			// if data is dynamic for client type -> no expiration / invalidation check
			} else if (isCacheableForClientType && FCUtils.isWebComponentExpired(expireTimeMap, clientType))
			{

				String refreshType = cachedWebResponse.getRefreshType();
				if (FCHeaders.COMPONENT_REFRESH_TYPE_SOFT.equalsIgnoreCase(refreshType))
				{
					if (isWarmRequest)
					{
						// Soft expiration on a warm request is refreshed on THIS thread, through the miss
						// path below, and again without a remove. Handed to softInvalidationExecutor, the
						// warmer would read "fresh" at loopback speed while queueing one background origin
						// fetch per key - single-flight dedupes a key, not a list - and the delay between
						// warm calls would pace nothing.
						// IncludeProcessorBase.getIncludeFromCache carries the same branch.
						cachedWebResponse = null;
					} else {
						// soft expiration
						doSoftInvalidation(currentRequestURL, originUrlStr, requestHeaders, client, context);
					}
				} else {
					// Regular expiration - but only for THIS request's client type. With two live TTLs
					// (maxage="bot:30d, guest:1d") the entry is one shared copy carrying two expiration
					// stamps, so a guest arriving at T+25h must not delete the copy that is valid for
					// bots for another 29 days. Drop it only once NO client type can still be served
					// from it - the same max(T_bot, T_guest) predicate purge() already uses.
					//
					// Usually the refetch below puts the entry straight back, which is what makes this
					// invisible in testing. The cases where it does not are the ones that hurt: the
					// write is skipped when a fallback was served (origin down), on a 5xx, on an empty
					// body, on a non-text content type. Then the delete succeeded, the repopulate did
					// not, and every crawler goes to a dead origin because one human happened by.
					long latestExpireTime = cachedWebResponse.getExpireTimeMillis(); // max across client types
					if (CacheProcessor.CACHE_FOREVER != latestExpireTime
							&& latestExpireTime < System.currentTimeMillis())
						removeFromCache(currentRequestURL);

					cachedWebResponse = null; // refresh from origin either way
				}
			}
		}

		if (!isCacheableForClientType || // call origin if request is dynamic for client type [bot|guest] or component is null
				null == cachedWebResponse || 0 >= cachedWebResponse.getContentLenth())
		{
			try
			{
				cachedWebResponse = FCUtils.dynamicCall(originUrlStr, requestHeaders, client, context); // it can be pure dynamic include -> check if we need to save to cache

				String clientType = context.getClientType(); // bot | guest
				Map<String, Long> expireTimeMap = cachedWebResponse.getExpireTimeMap();

				boolean isFreshDataCacheableForClientType = FCUtils.isWebComponentCacheableForClientType(expireTimeMap, clientType);

				lengthBytes = cachedWebResponse.getContentLenth();

				// save to cache
				if (!context.isFallbackUsed() // don't cache fallbacks
						&& isCacheableForClientType
						&& isFreshDataCacheableForClientType
						&& cachedWebResponse.isCacheable()
                        && lengthBytes > 0 // don't cache empty responses
                )
				{
					WebResponse copy4cache = cachedWebResponse.copy(); // copy - putToCache strips headers in place
					copy4cache.setUrl(currentRequestURL);
					putToCache(currentRequestURL, copy4cache); // put to cache copy

					if (isWarmRequest)
						context.setWarmResult(WarmRequest.RESULT_STORED);
				}

				if (isWarmRequest && null == context.getWarmResult())
					context.setWarmResult(context.isFallbackUsed() ? WarmRequest.RESULT_FALLBACK : WarmRequest.RESULT_NOT_CACHEABLE);
			} catch (FrontCacheException ex) {
				throw ex;
			} catch (Exception ex) {
				ex.printStackTrace();
				throw new FrontCacheException(ex);
			}

		} else {

			cachedWebResponse = cachedWebResponse.copy(); //to avoid modification instance in cache
			isCached = true;

			if (isWarmRequest)
				context.setWarmResult(WarmRequest.RESULT_FRESH);
			context.setToplevelCached();
			lengthBytes = cachedWebResponse.getContentLenth();
		}


		RequestLogger.logRequest(currentRequestURL, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);

		return cachedWebResponse;
	}

	/**
	 * Keys with a soft refresh already queued or running.
	 *
	 * Soft refresh is a per-key singleton: a second refresh of the same URL while one is in flight
	 * fetches the same bytes from the same origin and overwrites them with themselves. Without this
	 * set, every request arriving during the refresh window submits its own task, because the entry
	 * stays expired until the winner's putToCache lands - so a hot fragment turns one expiry into a
	 * herd of identical origin calls that grows with request rate. Measured on a production node:
	 * 33 fragment URLs generating 608 origin fetches per second, one URL taking 170/s of it.
	 *
	 * A plain set, not a map of futures: callers never wait for the refresh - they serve the stale
	 * entry and return - so there is nothing to hand back to a loser.
	 */
	private final Set<String> softRefreshInFlight = ConcurrentHashMap.newKeySet();

	/**
	 * perform async invalidation
	 *
	 * @param currentRequestURL
	 * @param originUrlStr
	 * @param requestHeaders
	 * @param client
	 * @param context
	 */
	public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context)
	{
		// Single-flight. Losing this race is the normal case under load and is not an error: the
		// caller has a stale entry in hand and serves it, which is the whole point of a soft refresh.
		if (!softRefreshInFlight.add(currentRequestURL))
			return;

		// Snapshot the request headers synchronously on the caller's thread. The async task below runs
		// later on a pool thread, while the caller carries on adding trace headers to this same map
		// (the include level, the request id, the client IP) - iterating it there would race and throw
		// ConcurrentModificationException. Copying it now, before submitting, avoids that.
		//
		// The copy is only safe because the map belongs to this one caller: includes are handed a copy
		// per include in ConcurrentIncludeProcessor.parseIncludes, made on the request thread. When
		// they all shared the parent's map, this line was itself the racing reader.
		final Map<String, List<String>> requestHeadersCopy = new HashMap<String, List<String>>(requestHeaders);

		boolean submitted = false;
		try {
			// async invalidation
			softInvalidationExecutor.submit(new Runnable(){

				@Override
				public void run() {
					try {

						// The entry is deliberately NOT removed first. Soft refresh exists to keep
						// serving the stale value while a fresh one is fetched, and putToCache
						// overwrites, so a removal buys nothing and costs everything: it opens a window,
						// one origin round trip wide, in which getFromCache returns null and every
						// request in flight falls through to the origin. That window was the herd this
						// method now suppresses. It also means a refresh that FAILS leaves the stale
						// entry in place instead of leaving the key empty.
						RequestContext ctxCopy = context.copy();
						ctxCopy.setFilterChain(null); // async calls for ServletFilter doesnt works (some objects already disposed), so use http calls for soft resets

						requestHeadersCopy.put(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST, Arrays.asList(new String[]{"true"}));
						requestHeadersCopy.put(FCHeaders.X_FRONTCACHE_SOFT_REFRESH, Arrays.asList(new String[]{"true"}));

						WebResponse copy4cache = FCUtils.dynamicCall(originUrlStr, requestHeadersCopy, client, ctxCopy);
						copy4cache.setUrl(currentRequestURL);

						if (!ctxCopy.isFallbackUsed()) // don't cache fallbacks
							putToCache(currentRequestURL, copy4cache); // put to cache copy

					} catch (Exception e) {

						logger.error("Soft invalidation/refresh failed: " + originUrlStr, e);
					} finally {
						// Released here rather than in the submitting method: the claim must outlive the
						// submit and cover the whole run, or the next request re-submits while this task
						// is still fetching - which is exactly the herd.
						softRefreshInFlight.remove(currentRequestURL);
					}
				}

			});
			submitted = true;
		} finally {
			// A rejected submit (pool shut down) would otherwise leave the key claimed forever, and
			// that key would never refresh again for the life of the process.
			if (!submitted)
				softRefreshInFlight.remove(currentRequestURL);
		}

		return;
	}

	/** Test seam: how many soft refreshes are queued or running. */
	int softRefreshInFlightCount()
	{
		return softRefreshInFlight.size();
	}


	/**
	 * remove header not supposed to be stored in cache
	 * right before saving to cache
	 *
	 * @param headers
	 */
	/**
	 * The lowercased form of {@link #NON_PERSISTENT_HEADERS}, and the trace-request prefix.
	 *
	 * Static, because both derive from compile-time constants. They were rebuilt - a HashSet plus a
	 * toLowerCase() per entry - on every single cache write.
	 */
	private static final Set<String> NON_PERSISTENT_HEADERS_LOWER = buildNonPersistentLower();

	private static final String TRACE_REQUEST_PREFIX_LOWER =
			FCHeaders.X_FRONTCACHE_TRACE_REQUEST.toLowerCase();

	private static Set<String> buildNonPersistentLower()
	{
		Set<String> lower = new HashSet<String>();
		for (String name : NON_PERSISTENT_HEADERS)
			lower.add(name.toLowerCase());

		return Collections.unmodifiableSet(lower);
	}

	private void cleanupNonPersistentHeaders(Map<String, List<String>> headers)
	{
		if (null == headers)
			return;

		// HTTP header names are case-insensitive (RFC 7230) and intermediaries (e.g. Cloudflare)
		// may re-case them - so match case-insensitively. Otherwise a downstream node's trace
		// headers that arrive re-cased (e.g. "X-Frontcache-Trace-Request.1.0") slip past the strip,
		// get cached and are replayed frozen on every cache hit instead of being (re)stamped live.
		//
		// Removed through the iterator rather than collected into a second Set and removed afterwards:
		// that Set existed only to avoid a ConcurrentModificationException, which Iterator.remove()
		// does not have.
		Iterator<String> keys = headers.keySet().iterator();
		while (keys.hasNext())
		{
			String keyLower = keys.next().toLowerCase();
			if (NON_PERSISTENT_HEADERS_LOWER.contains(keyLower)
					|| keyLower.startsWith(TRACE_REQUEST_PREFIX_LOWER)) // x-frontcache-trace-request.0, x-frontcache-trace-request.1.1.2.3.4.33, etc
				keys.remove();
		}

		return;
	}

	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = new HashMap<String, String>();
		status.put("impl", this.getClass().getName());

		return status;
	}

	@Override
	public void init(Properties properties) {
		Objects.requireNonNull(properties, "Properties should not be null");
		int threadAmount = 2;
		softInvalidationExecutor = Executors.newFixedThreadPool(threadAmount);

	}

	@Override
	public void destroy() {
		softInvalidationExecutor.shutdown();
	}

}
