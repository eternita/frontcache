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
package org.frontcache.replication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.WebResponse;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * That replication actually transfers entries concurrently, and that the concurrency does not leak
 * into the parts that were never thread safe.
 *
 * Each entry costs two synchronous HTTP round trips (a get from the source, a put to the target) and
 * they used to run strictly one after another: at 5ms per round trip a 100 000-entry cache is about
 * 17 minutes of pure waiting. The counters and the progress listener stay on one thread, though -
 * {@link ReplicationResult}'s setters are plain and the listener is an interface the console
 * implements, so neither may be called from several threads at once.
 */
public class CacheReplicatorConcurrencyTest {

	private static final int KEYS = 40;

	private static FrontCacheClient client(String url) {
		FrontCacheClient client = mock(FrontCacheClient.class);
		when(client.getFrontCacheURL()).thenReturn(url);
		return client;
	}

	private static String key(int i) {
		return "/page-" + i + ".html";
	}

	/** source has KEYS keys, target has none, every get/put succeeds */
	private static void stubTransfer(FrontCacheClient source, FrontCacheClient target, final Answer<Boolean> putAnswer) {
		when(target.getCachedKeysSet()).thenReturn(new HashSet<String>());

		StringBuilder keys = new StringBuilder();
		for (int i = 0; i < KEYS; i++)
			keys.append(key(i)).append("\n");
		final byte[] keyBytes = keys.toString().getBytes(StandardCharsets.UTF_8);

		when(source.getCachedKeys(any(OutputStream.class))).thenAnswer(new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				((ByteArrayOutputStream) invocation.getArgument(0)).write(keyBytes);
				return Boolean.TRUE;
			}
		});

		when(source.getFromCache(anyString())).thenAnswer(new Answer<WebResponse>() {
			@Override
			public WebResponse answer(InvocationOnMock invocation) {
				return new WebResponse(invocation.<String>getArgument(0), "body".getBytes());
			}
		});

		when(target.putToCache(anyString(), any(WebResponse.class))).thenAnswer(putAnswer);
	}

	/**
	 * The change. Each put blocks briefly; if the transfers were still sequential the run would take
	 * KEYS x the delay, and the peak observed concurrency would be 1.
	 */
	@Test
	public void entriesAreTransferredConcurrently() {
		FrontCacheClient source = client("http://source.example.com");
		FrontCacheClient target = client("http://target.example.com");

		final AtomicInteger inFlight = new AtomicInteger();
		final AtomicInteger peakInFlight = new AtomicInteger();

		stubTransfer(source, target, new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				int now = inFlight.incrementAndGet();
				peakInFlight.accumulateAndGet(now, Math::max);
				try {
					Thread.sleep(50); // stand-in for an origin round trip
				} finally {
					inFlight.decrementAndGet();
				}
				return Boolean.TRUE;
			}
		});

		CacheReplicator replicator = new CacheReplicator(source, target);
		long startMillis = System.currentTimeMillis();
		ReplicationResult result = replicator.run(null);
		long elapsedMillis = System.currentTimeMillis() - startMillis;

		assertEquals(ReplicationResult.STATUS_COMPLETED, result.getStatus());
		assertEquals(KEYS, result.getTransferred());

		assertTrue("transfers must overlap - peak concurrency was " + peakInFlight.get(),
				peakInFlight.get() > 1);
		assertTrue("sequential would need at least " + (KEYS * 50) + "ms; took " + elapsedMillis + "ms",
				elapsedMillis < KEYS * 50L);
	}

	/**
	 * The constraint that survives. Counters and listener callbacks must still be single-threaded,
	 * because nothing on either is synchronized.
	 */
	@Test
	public void progressIsStillReportedFromOneThreadOnly() {
		FrontCacheClient source = client("http://source.example.com");
		FrontCacheClient target = client("http://target.example.com");

		stubTransfer(source, target, new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				Thread.sleep(5);
				return Boolean.TRUE;
			}
		});

		final Set<String> callbackThreads = ConcurrentHashMap.newKeySet();
		final AtomicInteger callbacks = new AtomicInteger();

		ReplicationResult result = new CacheReplicator(source, target).run(new ReplicationProgressListener() {
			@Override
			public void onProgress(long transferred, long skipped, long errors, long totalRemaining) {
				callbackThreads.add(Thread.currentThread().getName());
				callbacks.incrementAndGet();
			}
			@Override
			public void onComplete(ReplicationResult result) {
				callbackThreads.add(Thread.currentThread().getName());
			}
			@Override
			public void onError(String key, Exception e) {
				callbackThreads.add(Thread.currentThread().getName());
			}
		});

		assertEquals(KEYS, result.getTransferred());
		assertEquals("every progress callback must arrive on the same thread, got " + callbackThreads,
				1, callbackThreads.size());
		assertEquals("one callback per transferred entry", KEYS, callbacks.get());
	}

	/**
	 * Cancel must stop the bulk of the work, not merely stop counting it. Workers check the flag
	 * before doing their I/O, and the pool is shut down - so what remains in flight is bounded by the
	 * worker count rather than running to the end of the key list.
	 */
	@Test
	public void cancelStopsTheRemainingTransfers() {
		FrontCacheClient source = client("http://source.example.com");
		FrontCacheClient target = client("http://target.example.com");

		final AtomicInteger putsAttempted = new AtomicInteger();
		stubTransfer(source, target, new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				putsAttempted.incrementAndGet();
				Thread.sleep(20);
				return Boolean.TRUE;
			}
		});

		final CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(new ReplicationProgressListener() {
			@Override
			public void onProgress(long transferred, long skipped, long errors, long totalRemaining) {
				if (1 <= transferred)
					replicator.cancel();
			}
			@Override
			public void onComplete(ReplicationResult result) { }
			@Override
			public void onError(String key, Exception e) { }
		});

		assertEquals(ReplicationResult.STATUS_CANCELLED, result.getStatus());
		assertTrue("should have counted at least one transfer", 1 <= result.getTransferred());
		assertTrue("should not have counted all " + KEYS, result.getTransferred() < KEYS);
		assertTrue("cancel must stop the work, not just the counting - attempted " + putsAttempted.get()
				+ " of " + KEYS + " puts", putsAttempted.get() < KEYS);
	}

	/** Keys already on the target need no worker at all, and must not be counted as transfers. */
	@Test
	public void keysAlreadyOnTheTargetAreSkippedWithoutTransferring() {
		FrontCacheClient source = client("http://source.example.com");
		FrontCacheClient target = client("http://target.example.com");

		stubTransfer(source, target, new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) {
				return Boolean.TRUE;
			}
		});

		Set<String> everything = new HashSet<String>();
		for (int i = 0; i < KEYS; i++)
			everything.add(key(i));
		when(target.getCachedKeysSet()).thenReturn(everything);

		ReplicationResult result = new CacheReplicator(source, target).run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED, result.getStatus());
		assertEquals(KEYS, result.getSkipped());
		assertEquals(0, result.getTransferred());
		assertEquals(0, result.getErrors());
	}

	/** workerCount 1 restores the old strictly-sequential behaviour, for anyone who wants it back. */
	@Test
	public void aSingleWorkerRestoresSequentialTransfer() {
		FrontCacheClient source = client("http://source.example.com");
		FrontCacheClient target = client("http://target.example.com");

		final AtomicInteger inFlight = new AtomicInteger();
		final AtomicInteger peakInFlight = new AtomicInteger();
		stubTransfer(source, target, new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				try {
					Thread.sleep(2);
				} finally {
					inFlight.decrementAndGet();
				}
				return Boolean.TRUE;
			}
		});

		CacheReplicator replicator = new CacheReplicator(source, target);
		replicator.setWorkerCount(1);
		ReplicationResult result = replicator.run(null);

		assertEquals(KEYS, result.getTransferred());
		assertEquals("one worker means one transfer at a time", 1, peakInFlight.get());
	}

	/** Nothing to transfer must not start a pool or hang. */
	@Test
	public void anEmptySourceCompletesCleanly() {
		FrontCacheClient source = client("http://source.example.com");
		FrontCacheClient target = client("http://target.example.com");

		when(target.getCachedKeysSet()).thenReturn(new HashSet<String>());
		when(source.getCachedKeys(any(OutputStream.class))).thenReturn(Boolean.TRUE); // writes nothing

		ReplicationResult result = new CacheReplicator(source, target).run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED, result.getStatus());
		assertEquals(0, result.getTransferred());
		assertEquals(Collections.emptyList(), Collections.emptyList());
	}
}
