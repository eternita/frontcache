/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.cache.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.frontcache.core.WebResponse;
import org.junit.Before;
import org.junit.Test;

import net.sf.ehcache.Element;

/**
 * The arithmetic of {@link L1ByteCounter}, driven through the listener callbacks directly.
 *
 * {@link L1ByteCounterEvictionTest} drives the same counter through a real Ehcache instead, which is
 * what proves the callbacks actually fire on the paths that matter. This one covers the cases that
 * are awkward to provoke through a cache: a displaced entry of a known size, a value that is not a
 * WebResponse, and two threads racing on one key.
 */
public class L1ByteCounterTest {

	private L1ByteCounter counter;

	@Before
	public void setUp() {
		counter = new L1ByteCounter();
	}

	private static Element element(String url, int contentBytes) {
		byte[] content = new byte[contentBytes];
		return new Element(url, new WebResponse(url, content));
	}

	@Test
	public void putAddsTheContentLength() {
		counter.notifyElementPut(null, element("http://example.com/a", 1000));
		counter.notifyElementPut(null, element("http://example.com/b", 234));

		assertEquals(1234, counter.bytes());
		assertEquals(2, counter.trackedEntries());
	}

	/**
	 * The property the side map exists for. {@code notifyElementUpdated} carries the NEW element only,
	 * so without the map the displaced 1000 bytes would never be subtracted and the counter would climb
	 * on every refresh of a cached URL.
	 */
	@Test
	public void updateSubtractsTheDisplacedSize() {
		counter.notifyElementPut(null, element("http://example.com/a", 1000));
		counter.notifyElementUpdated(null, element("http://example.com/a", 300));

		assertEquals("only the new entry counts - the one it replaced is gone", 300, counter.bytes());
		assertEquals("still one entry, not two", 1, counter.trackedEntries());
	}

	@Test
	public void removedExpiredAndEvictedAllSubtract() {
		counter.notifyElementPut(null, element("http://example.com/removed", 100));
		counter.notifyElementPut(null, element("http://example.com/expired", 200));
		counter.notifyElementPut(null, element("http://example.com/evicted", 400));
		assertEquals(700, counter.bytes());

		counter.notifyElementRemoved(null, element("http://example.com/removed", 100));
		counter.notifyElementExpired(null, element("http://example.com/expired", 200));
		counter.notifyElementEvicted(null, element("http://example.com/evicted", 400));

		assertEquals(0, counter.bytes());
		assertEquals(0, counter.trackedEntries());
	}

	/** The callback carries no elements, so it has to reset - which is also what resynchronizes it. */
	@Test
	public void removeAllZeroesTheCounterAndTheMap() {
		counter.notifyElementPut(null, element("http://example.com/a", 1000));
		counter.notifyElementPut(null, element("http://example.com/b", 2000));

		counter.notifyRemoveAll(null);

		assertEquals(0, counter.bytes());
		assertEquals(0, counter.trackedEntries());
	}

	/**
	 * A drop the counter never saw the put for must not push it below zero. Reading floors at zero so
	 * the worst case of a missed notification is a stale-high number, which is misleading, rather than
	 * a negative one, which is a visible defect in a size gauge.
	 */
	@Test
	public void theCounterNeverReadsNegative() {
		counter.notifyElementEvicted(null, element("http://example.com/never-put", 5000));

		assertEquals(0, counter.bytes());
	}

	/** Runs on the cache's notification path: an unexpected value counts as zero, it does not throw. */
	@Test
	public void aNonWebResponseValueCountsAsZero() {
		counter.notifyElementPut(null, new Element("http://example.com/odd", "not a WebResponse"));
		counter.notifyElementPut(null, new Element("http://example.com/null-content", new WebResponse("u", null)));

		assertEquals(0, counter.bytes());
	}

	/**
	 * Each write computes its delta against the value it displaced, so concurrent puts on one key
	 * total correctly however they interleave - the counter must end up holding exactly the size of
	 * the entry that survived, not a sum of all of them.
	 */
	@Test
	public void concurrentPutsOnOneKeyLeaveOneEntrysWorth() throws Exception {
		final String url = "http://example.com/contended";
		final int threads = 16;
		final int writesPerThread = 200;
		final CountDownLatch start = new CountDownLatch(1);
		final List<Thread> workers = new ArrayList<Thread>();
		final List<Throwable> failures = new ArrayList<Throwable>();

		for (int t = 0; t < threads; t++)
		{
			final int size = 100 + t;      // every thread writes a different size to the same key
			Thread worker = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						for (int i = 0; i < writesPerThread; i++)
							counter.notifyElementUpdated(null, element(url, size));
					} catch (Throwable e) {
						synchronized (failures) { failures.add(e); }
					}
				}
			});
			workers.add(worker);
			worker.start();
		}

		start.countDown();
		for (Thread worker : workers)
			worker.join(TimeUnit.SECONDS.toMillis(30));

		assertTrue("worker failed: " + failures, failures.isEmpty());
		assertEquals("one key, so one entry tracked", 1, counter.trackedEntries());
		assertTrue("the total must be one surviving entry's size (100..115), not a sum of " + threads
				+ " threads x " + writesPerThread + " writes: " + counter.bytes(),
				counter.bytes() >= 100 && counter.bytes() <= 100 + threads);
	}

	/**
	 * Ehcache clones listeners when a cache is cloned from a template. A clone would carry one cache's
	 * byte total into another, so this fails at configuration time instead.
	 */
	@Test
	public void refusesToBeCloned() {
		try {
			counter.clone();
			fail("a stateful per-cache listener must not be cloneable");
		} catch (CloneNotSupportedException expected) {
			// the point of the test
		}
	}

}
