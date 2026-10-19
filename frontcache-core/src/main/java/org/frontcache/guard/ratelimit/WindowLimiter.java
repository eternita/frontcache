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
package org.frontcache.guard.ratelimit;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-window request counter: how many requests one key sent inside the current measurement slot.
 *
 * The window is a slot on a fixed grid - slot {@code w} covers
 * {@code [w * windowMillis, (w+1) * windowMillis)} - so a key's count resets when the slot rolls
 * over. There is no per-key timer, no sweeper and no expiry list: a slot id that no longer matches
 * IS the reset, which is what keeps per-key state down to two numbers.
 *
 * <h3>Storage</h3>
 *
 * The key space is attacker-chosen and unbounded, so a map keyed by client address is a
 * memory-exhaustion primitive that then needs a cap, an eviction policy and a sweeper thread. This
 * is instead one fixed {@link AtomicLongArray}, allocated once, with each key's state packed into a
 * single long:
 *
 * <pre>
 *   value = (tag &lt;&lt; 44) | (windowId &lt;&lt; 20) | count
 *           20 bits          24 bits            20 bits
 * </pre>
 *
 * so an update is a single CAS and tag, window and count can never be torn apart. {@code windowId}
 * is compared for equality only, never ordered, so its wrap after 2^24 windows (5.3 years at a 10s
 * window) is harmless. {@code count} saturates rather than overflowing into {@code windowId} - a
 * key at a million requests in one window is long since limited.
 *
 * <h3>Collisions</h3>
 *
 * Two keys landing in the same array slot with different tags: the arriving one takes the slot over
 * and starts a fresh count. This is the failure direction that matters - a takeover never lets a
 * visitor inherit a foreign, exhausted count, so no attacker can get a chosen victim limited by
 * brute-forcing keys that collide with them. The cost of undersizing is therefore under-counting,
 * visible as a rising takeover rate, and never a false rejection.
 *
 * Never throws, and fails open: CAS contention beyond a couple of retries returns "allowed".
 */
public class WindowLimiter {

	static final int COUNT_BITS = 20;
	static final int WINDOW_BITS = 24;
	static final int TAG_BITS = 20;

	private static final int WINDOW_SHIFT = COUNT_BITS;
	private static final int TAG_SHIFT = COUNT_BITS + WINDOW_BITS;

	static final long COUNT_MASK = (1L << COUNT_BITS) - 1;
	private static final long WINDOW_MASK = (1L << WINDOW_BITS) - 1;
	private static final long TAG_MASK = (1L << TAG_BITS) - 1;

	/** highest limit the packed count can still distinguish from saturation */
	public static final int MAX_LIMIT = (int) COUNT_MASK - 1;

	private static final int MAX_CAS_RETRIES = 2;

	/**
	 * Monotonic time base. Deliberately not currentTimeMillis(): an NTP step backwards on a
	 * wall-clock window id would reset - or refuse - every client at once, silently and fleet-wide.
	 */
	private static final long NANO_BASE = System.nanoTime();

	private final String bucket;
	private final int limit;
	private final long windowMillis;
	private final AtomicLongArray slots;
	private final int indexMask;
	private final LongAdder takeovers = new LongAdder();

	/**
	 * @param slotCount number of array slots; rounded up to a power of two by the caller
	 */
	public WindowLimiter(String bucket, int limit, long windowMillis, int slotCount) {
		this.bucket = bucket;
		this.limit = limit;
		this.windowMillis = windowMillis;
		this.slots = new AtomicLongArray(slotCount);
		this.indexMask = slotCount - 1;
	}

	public static long nowMillis() {
		return (System.nanoTime() - NANO_BASE) / 1000000L;
	}

	/**
	 * Counts one request for {@code keyHash} and says whether it is over the limit.
	 *
	 * @param keyHash  64-bit mix of bucket name and key (see {@link #hash})
	 * @param nowMillis monotonic millis, from {@link #nowMillis()}
	 * @return 0 when the request is within the limit, otherwise the millis remaining until the
	 *         current window rolls over (i.e. the Retry-After the client should be told)
	 */
	public long checkAndCount(long keyHash, long nowMillis)
	{
		long window = (nowMillis / windowMillis) & WINDOW_MASK;
		int index = (int) (keyHash & indexMask);

		long tag = (keyHash >>> TAG_SHIFT) & TAG_MASK;
		if (0 == tag)
			tag = 1; // 0 is reserved so that an empty slot is a zero long

		for (int attempt = 0; attempt <= MAX_CAS_RETRIES; attempt++)
		{
			long current = slots.get(index);

			long count;
			boolean takeover = false;

			if (0 == current || ((current >>> TAG_SHIFT) & TAG_MASK) != tag)
			{
				count = 0;                     // empty slot, or another key's - start fresh
				takeover = (0 != current);
			}
			else if (((current >>> WINDOW_SHIFT) & WINDOW_MASK) != window)
			{
				count = 0;                     // same key, new window - the reset
			}
			else
			{
				count = current & COUNT_MASK;
			}

			long next = count + 1;
			if (next > COUNT_MASK)
				next = COUNT_MASK;             // saturate rather than overflow into windowId

			long updated = (tag << TAG_SHIFT) | (window << WINDOW_SHIFT) | next;

			if (slots.compareAndSet(index, current, updated))
			{
				if (takeover)
					takeovers.increment();

				return (next > limit) ? millisToWindowEnd(nowMillis) : 0L;
			}
		}

		// Contention beyond the retries: under-count rather than refuse. A rate limiter that fails
		// closed converts its own bug into the outage it exists to prevent.
		return 0L;
	}

	private long millisToWindowEnd(long nowMillis) {
		return windowMillis - (nowMillis % windowMillis);
	}

	/**
	 * 64-bit mix of bucket name and key. FNV-1a for the bytes, then a murmur3 finalizer so that the
	 * array index (low bits) and the collision tag (high bits) are independent of each other - a tag
	 * taken from bits that also drive the index would add no discrimination at all.
	 */
	public static long hash(String bucket, String key)
	{
		long h = 0xcbf29ce484222325L;

		h = mixBytes(h, bucket);
		h ^= 0xFF;
		h *= 0x100000001b3L;
		h = mixBytes(h, key);

		h ^= (h >>> 33);
		h *= 0xff51afd7ed558ccdL;
		h ^= (h >>> 33);
		h *= 0xc4ceb9fe1a85ec53L;
		h ^= (h >>> 33);

		return h;
	}

	private static long mixBytes(long h, String value)
	{
		for (int i = 0; i < value.length(); i++)
		{
			h ^= value.charAt(i);
			h *= 0x100000001b3L;
		}
		return h;
	}

	public String getBucket() {
		return bucket;
	}

	public int getLimit() {
		return limit;
	}

	public long getWindowMillis() {
		return windowMillis;
	}

	public int getSlotCount() {
		return slots.length();
	}

	/**
	 * @return how often an arriving key has taken a slot from a different key. Climbing steadily
	 *         means the slot array is undersized for the traffic.
	 */
	public long getTakeovers() {
		return takeovers.sum();
	}

	@Override
	public String toString() {
		return "WindowLimiter[" + bucket + " " + limit + "/" + windowMillis + "ms, " + slots.length() + " slots]";
	}

}
