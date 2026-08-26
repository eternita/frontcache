/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.reqlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * The request log's timestamp column.
 *
 * Two separate things are pinned here, and the second is the reason the first has to be:
 *
 *  - the format is a CONSUMED CONTRACT. These lines are parsed by an ELK pipeline, so replacing the
 *    formatter is only safe if the bytes are identical to what the old SimpleDateFormat produced.
 *  - the formatter has to be safe to share. It is used from every Jetty request thread, every
 *    include-processor worker and every soft-invalidation thread concurrently. SimpleDateFormat is
 *    not thread safe, and its failure here was not a crash but a silently mangled timestamp.
 */
public class RequestLoggerTimestampTest {

	/** The format the log has always been written in - see RequestLogger's class javadoc example. */
	private static final String LEGACY_PATTERN = "yyyy-MM-dd'T'HH:mm:ss,SSSZ";

	private static String legacyFormat(Instant instant) {
		// deliberately a fresh instance per call: this is the reference implementation, and using one
		// shared instance here would reintroduce exactly the bug under test
		return new SimpleDateFormat(LEGACY_PATTERN).format(Date.from(instant));
	}

	/**
	 * Byte-identical output. The awkward cases are the ones that would slip through a happy-path
	 * check: sub-100ms fractions (does the pattern zero-pad?), midnight and end-of-day, and instants
	 * on either side of a DST switch (does the offset move with them?).
	 */
	@Test
	public void formatIsIdenticalToTheSimpleDateFormatItReplaced() {
		Instant[] cases = {
				Instant.parse("2016-06-03T21:06:35.092Z"), // the example in RequestLogger's javadoc
				Instant.parse("2016-06-03T21:06:35.000Z"), // no fraction
				Instant.parse("2016-06-03T21:06:35.001Z"), // one-digit fraction - must pad to 001
				Instant.parse("2016-06-03T21:06:35.010Z"), // two-digit fraction - must pad to 010
				Instant.parse("2016-01-01T00:00:00.000Z"), // midnight, winter (standard time)
				Instant.parse("2016-07-01T00:00:00.000Z"), // midnight, summer (DST, if the zone has it)
				Instant.parse("2016-12-31T23:59:59.999Z"), // end of day / end of year
				Instant.parse("2026-03-08T09:59:00.000Z"), // just before a US DST transition
				Instant.parse("2026-03-08T10:01:00.000Z"), // just after it
				Instant.ofEpochMilli(0L),
		};

		for (Instant instant : cases)
			assertEquals("timestamp format changed for " + instant + " - the request log is parsed downstream",
					legacyFormat(instant), RequestLogger.formatLogTime(instant));
	}

	/**
	 * Formatting the same instant from many threads at once must yield one distinct string.
	 *
	 * This is the regression guard: a shared SimpleDateFormat here produced interleaved garbage
	 * (and occasionally threw out of Calendar), so a second distinct value means someone has put a
	 * mutable formatter back.
	 */
	@Test
	public void theFormatterIsSafeToShareAcrossThreads() throws Exception {
		final Instant instant = Instant.parse("2016-06-03T21:06:35.092Z");
		final String expected = legacyFormat(instant);

		final int threads = 16;
		final int perThread = 4000;
		final Set<String> produced = ConcurrentHashMap.newKeySet();
		final Set<String> failures = ConcurrentHashMap.newKeySet();

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			pool.submit(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						for (int i = 0; i < perThread; i++)
							produced.add(RequestLogger.formatLogTime(instant));
					} catch (Throwable t) {
						failures.add(t.toString()); // a mutable formatter can also THROW under contention
					} finally {
						done.countDown();
					}
				}
			});
		}

		start.countDown();
		assertTrue("formatting threads did not finish", done.await(60, TimeUnit.SECONDS));
		pool.shutdownNow();

		assertEquals("formatting must not throw under concurrency: " + failures,
				Collections.emptySet(), failures);
		assertEquals("one instant formatted concurrently must produce exactly one string; "
				+ "more than one means the formatter is mutable shared state: " + produced,
				Collections.singleton(expected), produced);
	}
}
