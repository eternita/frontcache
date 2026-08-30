/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * The counter itself. Time is passed in rather than read, so nothing here sleeps and the window
 * boundary can be stepped over exactly.
 */
public class WindowLimiterTest {

	private static final int SLOTS = 4096;
	private static final long WINDOW = 10000L; // the 10-second slot from the proposal

	private static long key(String address) {
		return WindowLimiter.hash("test-bucket", address);
	}

	// ---- the basic contract: N per window ----------------------------------------------------

	@Test
	public void limitRequestsAreAllowedAndTheNextOneIsNot() {
		WindowLimiter limiter = new WindowLimiter("test-bucket", 100, WINDOW, SLOTS);
		long ip = key("203.0.113.7");

		for (int i = 1; i <= 100; i++)
			assertEquals("request " + i + " of 100 must be allowed", 0, limiter.checkAndCount(ip, 1000));

		assertTrue("request 101 must be limited", 0 < limiter.checkAndCount(ip, 1000));
	}

	@Test
	public void staysLimitedForTheRestOfTheWindow() {
		WindowLimiter limiter = new WindowLimiter("test-bucket", 3, WINDOW, SLOTS);
		long ip = key("203.0.113.7");

		for (int i = 0; i < 3; i++)
			limiter.checkAndCount(ip, 0);

		assertTrue(0 < limiter.checkAndCount(ip, 1));
		assertTrue(0 < limiter.checkAndCount(ip, 5000));
		assertTrue(0 < limiter.checkAndCount(ip, WINDOW - 1));
	}

	@Test
	public void countResetsWhenTheWindowRollsOver() {
		WindowLimiter limiter = new WindowLimiter("test-bucket", 3, WINDOW, SLOTS);
		long ip = key("203.0.113.7");

		for (int i = 0; i < 3; i++)
			limiter.checkAndCount(ip, 9000);

		assertTrue("over the limit inside the slot", 0 < limiter.checkAndCount(ip, 9999));
		assertEquals("first request of the next slot starts from zero", 0, limiter.checkAndCount(ip, 10000));
	}

	@Test
	public void differentAddressesDoNotShareACount() {
		WindowLimiter limiter = new WindowLimiter("test-bucket", 2, WINDOW, SLOTS);

		limiter.checkAndCount(key("203.0.113.7"), 0);
		limiter.checkAndCount(key("203.0.113.7"), 0);
		assertTrue(0 < limiter.checkAndCount(key("203.0.113.7"), 0));

		assertEquals("a different client has its own budget", 0, limiter.checkAndCount(key("198.51.100.9"), 0));
	}

	@Test
	public void oneBigWindowIsNotSlicedByTheGrid() {
		// a 1h window: two requests an hour apart must land in different slots, two minutes apart
		// in the same one
		WindowLimiter limiter = new WindowLimiter("test-bucket", 1, 3600000L, SLOTS);
		long ip = key("203.0.113.7");

		assertEquals(0, limiter.checkAndCount(ip, 0));
		assertTrue(0 < limiter.checkAndCount(ip, 120000));
		assertEquals(0, limiter.checkAndCount(ip, 3600000));
	}

	// ---- Retry-After -------------------------------------------------------------------------

	@Test
	public void retryAfterIsTheTimeLeftInTheWindowAndNeverZero() {
		WindowLimiter limiter = new WindowLimiter("test-bucket", 1, WINDOW, SLOTS);
		long ip = key("203.0.113.7");

		limiter.checkAndCount(ip, 12000);                         // slot 1, starts at 10000
		assertEquals(WINDOW - 2000, limiter.checkAndCount(ip, 12000));
		assertEquals(1, limiter.checkAndCount(ip, 19999));        // the very last millisecond still says "wait"
	}

	// ---- packing edges -----------------------------------------------------------------------

	@Test
	public void countSaturatesInsteadOfOverflowingIntoTheWindowId() {
		// A count that wrapped past its 20 bits would corrupt the window id and silently reset the
		// client. Drive one key past the ceiling and check it is still limited afterwards.
		WindowLimiter limiter = new WindowLimiter("test-bucket", 1, WINDOW, SLOTS);
		long ip = key("203.0.113.7");

		for (int i = 0; i < WindowLimiter.COUNT_MASK + 16; i++)
			limiter.checkAndCount(ip, 0);

		assertTrue("still limited after saturating", 0 < limiter.checkAndCount(ip, 0));
		assertEquals("and still resets on the next window", 0, limiter.checkAndCount(ip, WINDOW));
	}

	@Test
	public void aTakeoverStartsAFreshCountRatherThanInheritingAForeignOne() {
		// A one-slot array forces every key to collide, which is the case that must not let an
		// attacker get a chosen victim limited by exhausting a colliding slot.
		WindowLimiter limiter = new WindowLimiter("test-bucket", 2, WINDOW, 1);
		long attacker = key("203.0.113.7");
		long victim = key("198.51.100.9");

		for (int i = 0; i < 50; i++)
			limiter.checkAndCount(attacker, 0);

		assertEquals("the colliding client must not inherit an exhausted count",
				0, limiter.checkAndCount(victim, 0));
		assertTrue("collisions are visible as takeovers", 0 < limiter.getTakeovers());
	}

	@Test
	public void anUncontendedSlotReportsNoTakeovers() {
		WindowLimiter limiter = new WindowLimiter("test-bucket", 100, WINDOW, SLOTS);
		long ip = key("203.0.113.7");

		for (int i = 0; i < 50; i++)
			limiter.checkAndCount(ip, 0);

		assertEquals(0, limiter.getTakeovers());
	}

	@Test
	public void hashSpreadsAcrossIndexAndTag() {
		// index comes from the low bits and the tag from the high ones; if they were correlated the
		// tag would add no collision discrimination at all
		long a = WindowLimiter.hash("bucket", "203.0.113.7");
		long b = WindowLimiter.hash("bucket", "203.0.113.8");
		long c = WindowLimiter.hash("other-bucket", "203.0.113.7");

		assertTrue("neighbouring addresses must not collide", a != b);
		assertTrue("the bucket name must be part of the key", a != c);
	}

	// ---- concurrency -------------------------------------------------------------------------

	@Test
	public void concurrentRequestsForOneKeyDoNotLoseOrTearState() throws Exception {
		final WindowLimiter limiter = new WindowLimiter("test-bucket", 200, WINDOW, SLOTS);
		final long ip = key("203.0.113.7");

		final int threads = 8;
		final int perThread = 100;
		final AtomicInteger allowed = new AtomicInteger();
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++)
		{
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						for (int i = 0; i < perThread; i++)
							if (0 == limiter.checkAndCount(ip, 0))
								allowed.incrementAndGet();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						done.countDown();
					}
				}
			}).start();
		}

		start.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS));

		// Contention beyond the CAS retries fails OPEN, so the count can drift up but never down:
		// at least the limit is admitted, and never more than the total sent.
		int admitted = allowed.get();
		assertTrue("admitted " + admitted + ", expected at least the limit", 200 <= admitted);
		assertTrue("admitted " + admitted + ", expected no more than everything sent", threads * perThread >= admitted);
	}

}
