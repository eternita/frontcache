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
package org.frontcache.cache.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.frontcache.core.WebResponse;

import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.event.CacheEventListener;

/**
 * How many bytes of response content the in-memory (L1) tier is holding, maintained as the cache
 * changes rather than measured on demand.
 *
 * <h2>Why a counter at all</h2>
 *
 * Ehcache does not know. The shipped {@code fc-l1-ehcache-config.xml} sizes the cache by ENTRIES
 * ({@code maxElementsInMemory}), so the byte-sizing engine is never engaged and
 * {@code getStatistics().getLocalHeapSizeInBytes()} stays empty; {@code calculateInMemorySize()} does
 * know, by walking the object graph, and is far too expensive to put behind a number the console
 * polls. Turning byte-based sizing on to get the figure for free is not free either - it changes what
 * the cache evicts on, and adds a sizeOf traversal to every put.
 *
 * <h2>Why the cache event listener, and not the put site</h2>
 *
 * The obvious version - {@code bytes += size} next to {@code ehCache.put()} in
 * {@link L1L2CacheProcessor#putToCacheImpl} - is wrong, and would be visibly wrong within minutes.
 * The put site sees only puts. It does not see LFU eviction at {@code maxElementsInMemory}, TTL
 * expiry, {@code removeFromCache}, or {@code truncate()}'s {@code removeAll} - and with the shipped
 * cap of 100 entries, eviction is not an edge case but the steady state, so a put-site counter climbs
 * forever. Ehcache notifies on every one of those paths, which is why the number is kept here.
 *
 * <h2>Why the side map</h2>
 *
 * {@link #notifyElementUpdated} hands over the NEW element only. Re-caching a URL displaces an entry
 * whose size is not in the callback and can no longer be read from the cache, so a key-to-size map is
 * what makes the subtraction possible. Each write computes its delta against the value it actually
 * displaced ({@code sizes.put} returns it), so two threads racing on one key still leave the total
 * correct - there is no read-then-write window to lose. The map is bounded by the cache's own entry
 * cap: a key string and a boxed long per entry.
 *
 * <h2>What the number is</h2>
 *
 * The sum of {@link WebResponse#getContentLenth()} over what L1 holds - <b>response bodies, not
 * retained heap</b>. It excludes header maps, key strings, {@code Element} and {@code WebResponse}
 * object overhead, and Ehcache's own structures. Anything displaying it next to a JVM heap figure
 * should say so.
 *
 * Two behaviours are accepted rather than fixed. Expiry in Ehcache 2 is lazy, so between an entry
 * expiring and being reaped its bytes are still counted - but {@code getSize()} still counts that
 * entry too, so the byte figure and the entry figure stay consistent with each other, which is what a
 * reader compares. And a notification that never arrives cannot be recovered without a rescan, so
 * {@link #bytes()} floors at zero: the worst case is a stale-high reading rather than a negative one,
 * and {@code removeAll} resynchronizes.
 */
class L1ByteCounter implements CacheEventListener {

	private final AtomicLong bytes = new AtomicLong();

	/** key -> the size currently counted for it, so an overwrite knows what it displaced. */
	private final ConcurrentHashMap<String, Long> sizes = new ConcurrentHashMap<String, Long>();

	/** Content bytes currently held in L1. Never negative - see the class javadoc. */
	long bytes() {
		return Math.max(0L, bytes.get());
	}

	/** Entries the counter is tracking. Package-private for tests: it should track the cache's own size. */
	int trackedEntries() {
		return sizes.size();
	}

	private static String keyOf(Element element) {
		Object key = (null == element) ? null : element.getObjectKey();
		return (null == key) ? null : key.toString();
	}

	/**
	 * Bytes to charge for an element. A value that is not a {@link WebResponse}, or a response with no
	 * body, counts as zero rather than throwing: this runs on the cache's notification path, and a
	 * surprising value must not take the cache down with it.
	 */
	private static long sizeOf(Element element) {
		Object value = (null == element) ? null : element.getObjectValue();
		if (!(value instanceof WebResponse))
			return 0L;

		long length = ((WebResponse) value).getContentLenth();
		return (length < 0) ? 0L : length;
	}

	private void added(Element element) {
		String key = keyOf(element);
		if (null == key)
			return;

		long size = sizeOf(element);
		Long displaced = sizes.put(key, Long.valueOf(size));
		bytes.addAndGet(size - (null == displaced ? 0L : displaced.longValue()));
	}

	private void dropped(Element element) {
		String key = keyOf(element);
		if (null == key)
			return;

		Long counted = sizes.remove(key);
		if (null != counted)
			bytes.addAndGet(-counted.longValue());
	}

	@Override
	public void notifyElementPut(Ehcache cache, Element element) {
		added(element);
	}

	@Override
	public void notifyElementUpdated(Ehcache cache, Element element) {
		added(element);
	}

	@Override
	public void notifyElementRemoved(Ehcache cache, Element element) {
		dropped(element);
	}

	@Override
	public void notifyElementExpired(Ehcache cache, Element element) {
		dropped(element);
	}

	@Override
	public void notifyElementEvicted(Ehcache cache, Element element) {
		dropped(element);
	}

	/** The callback carries no elements, so a reset is the only correct answer - and a resynchronizing one. */
	@Override
	public void notifyRemoveAll(Ehcache cache) {
		sizes.clear();
		bytes.set(0L);
	}

	@Override
	public void dispose() {
		notifyRemoveAll(null);
	}

	/**
	 * Not cloneable, deliberately. {@link CacheEventListener} extends {@code Cloneable} because Ehcache
	 * clones listeners when a cache is cloned from a template - and a clone of a counter would report
	 * one cache's bytes for another. Failing loudly at configuration time beats a number that is
	 * quietly about the wrong cache.
	 */
	@Override
	public Object clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException(getClass().getName() + " holds per-cache state and must not be cloned");
	}

}
