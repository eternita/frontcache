/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.replication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.WebResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link CacheReplicator} using Mockito-mocked {@link FrontCacheClient} instances.
 */
public class CacheReplicatorTest {

	private FrontCacheClient source;
	private FrontCacheClient target;

	@Before
	public void setUp() {
		source = mock(FrontCacheClient.class);
		target = mock(FrontCacheClient.class);

		when(source.getFrontCacheURL()).thenReturn("http://source.example.com");
		when(target.getFrontCacheURL()).thenReturn("http://target.example.com");
	}

	// --- Helper: stub getCachedKeys(OutputStream) to write the given keys ---

	private void stubCachedKeys(FrontCacheClient client, String... keys) {
		doAnswer(new Answer<Boolean>() {
			@Override
			public Boolean answer(InvocationOnMock invocation) throws Throwable {
				OutputStream os = invocation.getArgument(0);
				for (String key : keys) {
					os.write((key + "\n").getBytes("UTF-8"));
				}
				os.flush();
				return true;
			}
		}).when(client).getCachedKeys(any(OutputStream.class));
	}

	// --- Helper: stub getCachedKeysSet() to return the given keys ---

	private void stubCachedKeysSet(FrontCacheClient client, String... keys) {
		java.util.Set<String> keySet = new java.util.HashSet<String>();
		for (String k : keys) keySet.add(k);
		when(client.getCachedKeysSet()).thenReturn(keySet);
	}

	// --- Helper: stub getFromCache to return a WebResponse ---

	private void stubGetFromCache(FrontCacheClient client, String key, String content) {
		WebResponse wr = new WebResponse("http://source.example.com" + key, content.getBytes());
		when(client.getFromCache(eq(key))).thenReturn(wr);
	}

	// --- Tests ---

	@Test
	public void allEntriesTransferredWhenTargetIsEmpty() {
		stubCachedKeysSet(target); // empty target
		stubCachedKeys(source, "/page1.html", "/page2.html", "/page3.html");
		stubGetFromCache(source, "/page1.html", "content1");
		stubGetFromCache(source, "/page2.html", "content2");
		stubGetFromCache(source, "/page3.html", "content3");
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(true);

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED, result.getStatus());
		assertEquals(3, result.getTransferred());
		assertEquals(0, result.getSkipped());
		assertEquals(0, result.getErrors());
		assertEquals(3, result.getTotalSourceKeys());

		verify(target, org.mockito.Mockito.times(3)).putToCache(anyString(), any(WebResponse.class));
	}

	@Test
	public void existingEntriesAreSkipped() {
		// target already has /page2.html
		stubCachedKeysSet(target, "/page2.html");
		stubCachedKeys(source, "/page1.html", "/page2.html", "/page3.html");
		stubGetFromCache(source, "/page1.html", "content1");
		stubGetFromCache(source, "/page3.html", "content3");
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(true);

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED, result.getStatus());
		assertEquals(2, result.getTransferred());
		assertEquals(1, result.getSkipped());
		assertEquals(0, result.getErrors());

		// putToCache should NOT be called for /page2.html
		verify(target, never()).putToCache(eq("/page2.html"), any(WebResponse.class));
		verify(target, org.mockito.Mockito.times(2)).putToCache(anyString(), any(WebResponse.class));
	}

	@Test
	public void allSkippedWhenTargetHasAllEntries() {
		stubCachedKeysSet(target, "/page1.html", "/page2.html");
		stubCachedKeys(source, "/page1.html", "/page2.html");

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED, result.getStatus());
		assertEquals(0, result.getTransferred());
		assertEquals(2, result.getSkipped());
		assertEquals(0, result.getErrors());

		verify(target, never()).putToCache(anyString(), any(WebResponse.class));
	}

	@Test
	public void errorsCountedWhenGetFromCacheReturnsNull() {
		stubCachedKeysSet(target); // empty target
		stubCachedKeys(source, "/page1.html", "/page2.html");
		stubGetFromCache(source, "/page1.html", "content1");
		// /page2.html returns null (entry vanished)
		when(source.getFromCache(eq("/page2.html"))).thenReturn(null);
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(true);

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED_WITH_ERRORS, result.getStatus());
		assertEquals(1, result.getTransferred());
		assertEquals(0, result.getSkipped());
		assertEquals(1, result.getErrors());
	}

	@Test
	public void errorsCountedWhenPutToCacheFails() {
		stubCachedKeysSet(target); // empty target
		stubCachedKeys(source, "/page1.html");
		stubGetFromCache(source, "/page1.html", "content1");
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(false);

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_COMPLETED_WITH_ERRORS, result.getStatus());
		assertEquals(0, result.getTransferred());
		assertEquals(0, result.getSkipped());
		assertEquals(1, result.getErrors());
	}

	@Test
	public void progressListenerReceivesCallbacks() {
		stubCachedKeysSet(target); // empty target
		stubCachedKeys(source, "/a.html", "/b.html", "/c.html");
		stubGetFromCache(source, "/a.html", "a");
		stubGetFromCache(source, "/b.html", "b");
		stubGetFromCache(source, "/c.html", "c");
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(true);

		final List<long[]> progressSnapshots = new ArrayList<long[]>();
		final AtomicInteger completeCount = new AtomicInteger(0);

		ReplicationProgressListener listener = new ReplicationProgressListener() {
			@Override
			public void onProgress(long transferred, long skipped, long errors, long totalRemaining) {
				progressSnapshots.add(new long[]{transferred, skipped, errors, totalRemaining});
			}

			@Override
			public void onComplete(ReplicationResult result) {
				completeCount.incrementAndGet();
			}

			@Override
			public void onError(String key, Exception e) {
				// no errors in this test
			}
		};

		CacheReplicator replicator = new CacheReplicator(source, target);
		replicator.run(listener);

		// Should have 3 progress callbacks (one per entry)
		assertEquals(3, progressSnapshots.size());
		// Last callback should show all transferred
		long[] last = progressSnapshots.get(2);
		assertEquals(3, last[0]); // transferred
		assertEquals(0, last[1]); // skipped
		assertEquals(0, last[2]); // errors
		assertEquals(0, last[3]); // remaining

		// onComplete called exactly once
		assertEquals(1, completeCount.get());
	}

	@Test
	public void cancelStopsReplicationMidRun() {
		stubCachedKeysSet(target); // empty target
		stubCachedKeys(source, "/a.html", "/b.html", "/c.html", "/d.html", "/e.html");
		stubGetFromCache(source, "/a.html", "a");
		stubGetFromCache(source, "/b.html", "b");
		stubGetFromCache(source, "/c.html", "c");
		stubGetFromCache(source, "/d.html", "d");
		stubGetFromCache(source, "/e.html", "e");

		// Cancel after the first entry is transferred
		when(target.putToCache(anyString(), any(WebResponse.class)))
				.thenAnswer(new Answer<Boolean>() {
					private int callCount = 0;

					@Override
					public Boolean answer(InvocationOnMock invocation) {
						callCount++;
						if (callCount == 1) {
							// cancel after first successful transfer
							// Note: we can't easily access the replicator here,
							// so we'll use a different approach
						}
						return true;
					}
				});

		// Use a listener that cancels after first progress callback
		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationProgressListener cancellingListener = new ReplicationProgressListener() {
			@Override
			public void onProgress(long transferred, long skipped, long errors, long totalRemaining) {
				if (transferred >= 1) {
					replicator.cancel();
				}
			}

			@Override
			public void onComplete(ReplicationResult result) {
			}

			@Override
			public void onError(String key, Exception e) {
			}
		};

		ReplicationResult result = replicator.run(cancellingListener);

		assertEquals(ReplicationResult.STATUS_CANCELLED, result.getStatus());
		// Should have transferred at least 1 but not all 5
		assertTrue("should have transferred at least 1", result.getTransferred() >= 1);
		assertTrue("should not have transferred all 5", result.getTransferred() < 5);
	}

	@Test
	public void failedTargetKeyFetchResultsInFailedStatus() {
		when(target.getCachedKeysSet()).thenReturn(null); // simulate failure

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_FAILED, result.getStatus());
		assertEquals(0, result.getTransferred());
	}

	@Test
	public void failedSourceKeyFetchResultsInFailedStatus() {
		stubCachedKeysSet(target, "/existing.html");
		when(source.getCachedKeys(any(OutputStream.class))).thenReturn(false); // simulate failure

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals(ReplicationResult.STATUS_FAILED, result.getStatus());
	}

	@Test
	public void resultContainsSourceAndTargetUrls() {
		stubCachedKeysSet(target);
		stubCachedKeys(source);

		CacheReplicator replicator = new CacheReplicator(source, target);
		ReplicationResult result = replicator.run(null);

		assertEquals("http://source.example.com", result.getSourceUrl());
		assertEquals("http://target.example.com", result.getTargetUrl());
	}

	@Test
	public void isRunningReflectsState() {
		stubCachedKeysSet(target);
		stubCachedKeys(source, "/page.html");
		stubGetFromCache(source, "/page.html", "content");
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(true);

		CacheReplicator replicator = new CacheReplicator(source, target);
		assertFalse("should not be running before run()", replicator.isRunning());

		replicator.run(null);

		assertFalse("should not be running after completion", replicator.isRunning());
	}

	/**
	 * The entry is written to the target under the key the source listed it under, untouched.
	 *
	 * This test used to cover a domain-stamping step: the replicator read the source entry's domain,
	 * fell back to the source host when it was null, and passed it to putToCache. Entries are not
	 * domain-scoped any more, so the key is all that crosses.
	 */
	@Test
	public void entryIsWrittenToTargetUnderTheSourceKey() {
		stubCachedKeysSet(target); // empty target
		stubCachedKeys(source, "/page.html");

		WebResponse wr = new WebResponse("http://source.example.com/page.html", "content".getBytes());
		when(source.getFromCache(eq("/page.html"))).thenReturn(wr);
		when(target.putToCache(anyString(), any(WebResponse.class))).thenReturn(true);

		CacheReplicator replicator = new CacheReplicator(source, target);
		replicator.run(null);

		verify(target).putToCache(eq("/page.html"), eq(wr));
	}
}
