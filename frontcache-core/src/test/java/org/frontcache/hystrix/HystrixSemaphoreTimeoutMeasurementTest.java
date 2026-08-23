/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixEventType;

/**
 * The measurement proposal section 6.2 asks for, done against the library instead of reasoned about -
 * and it came back with an answer neither of the two predicted branches covered.
 *
 * conf/hystrix.properties sets execution.isolation.thread.timeoutInMilliseconds for the two commands
 * that run under execution.isolation.strategy=SEMAPHORE: Input-Requests (FC_Total, 20s) and Cache-Hits
 * (FC_ThroughCache, 1.5s). The proposal expected those budgets to be inert, on the reasoning that
 * semaphore isolation runs run() inline on the calling thread so the timer has nothing to preempt.
 *
 * Measured, the budget is HALF enforced, which is worse than either branch:
 *
 *  - It changes the RESULT. At the budget the command is marked TIMEOUT and the caller is handed the
 *    fallback - so a Cache-Hits read slower than 1.5s has its real value discarded and returns null,
 *    which the engine reads as a cache miss and re-fetches from origin.
 *  - It does NOT change the LATENCY. execute() still blocks for the whole of run(): 1528ms against a
 *    200ms budget here. The work is completed in full and then thrown away.
 *  - The fallback runs on the Hystrix TIMER thread, at the budget, while run() is still executing on
 *    the caller's thread. For FC_Total that means two threads writing one servlet output stream - the
 *    exact hazard FC_BypassCache documents and works around by parking its fallback. FC_Total's
 *    fallback does not park: it writes the body directly.
 *
 * Which is why section 6.2's watchdog decision does more than add reporting: it removes the concurrent
 * writer and stops discarding completed work. It is also a behavior change and has to ship as one - a
 * slow cache read will start returning its value instead of a null.
 *
 * Asserted rather than printed so a Hystrix upgrade that changes any of this fails the build instead
 * of quietly invalidating the decision.
 */
public class HystrixSemaphoreTimeoutMeasurementTest {

	private static final String COMMAND_KEY = "Semaphore-Timeout-Measurement";

	private static final int TIMEOUT_MS = 200;
	private static final long RUN_DURATION_MS = 1500;

	@Test
	public void semaphoreIsolatedTimeoutSwapsTheResultButNotTheLatency() throws Exception
	{
		ConfigurationManager.getConfigInstance().setProperty(
				"hystrix.command." + COMMAND_KEY + ".execution.isolation.strategy", "SEMAPHORE");
		ConfigurationManager.getConfigInstance().setProperty(
				"hystrix.command." + COMMAND_KEY + ".execution.isolation.thread.timeoutInMilliseconds", TIMEOUT_MS);

		SlowSemaphoreCommand command = new SlowSemaphoreCommand();
		final String callerThread = Thread.currentThread().getName();

		long startedAt = System.currentTimeMillis();
		String result = command.execute();
		long elapsed = System.currentTimeMillis() - startedAt;

		// The budget IS observed: TIMEOUT is recorded and the fallback value is what the caller gets.
		assertTrue("TIMEOUT recorded, so the budget is not inert", command.getExecutionEvents().contains(HystrixEventType.TIMEOUT));
		assertEquals("the caller receives the fallback, not run()'s value", "fallback", result);

		// ...and it is NOT enforced: the caller waits for the whole of run(), which completes and has
		// its result discarded. Uninterrupted, because a semaphore-isolated run() owns the caller's
		// thread and there is nothing for interruptOnTimeout to interrupt.
		assertTrue("caller blocked for the full run body (" + elapsed + "ms against a " + TIMEOUT_MS + "ms budget)",
				elapsed >= RUN_DURATION_MS);
		assertTrue("run() completed in full and its work was thrown away", command.finished.get());
		assertFalse("run() was never interrupted", command.interrupted.get());

		// The fallback ran early, on another thread, while run() was still going.
		assertEquals("run() executes inline on the caller's thread", callerThread, command.runThread);
		assertNotEquals("the fallback runs on the Hystrix timer thread, not the caller's",
				callerThread, command.fallbackThread);
		assertTrue("the fallback ran at the budget, not at the end of run() - overlapping it: fallback at "
				+ (command.fallbackAtMs - startedAt) + "ms, run body " + RUN_DURATION_MS + "ms",
				(command.fallbackAtMs - startedAt) < RUN_DURATION_MS);

		// Relied on by the existing commands: FC_ThroughCache_HttpClient passes this to SLF4J as the
		// throwable and expects null for a timeout so it logs a message without a stack trace.
		assertNull("getFailedExecutionException() is null for a timeout", command.getFailedExecutionException());
	}

	private static class SlowSemaphoreCommand extends HystrixCommand<String> {

		private final AtomicBoolean finished = new AtomicBoolean(false);
		private final AtomicBoolean interrupted = new AtomicBoolean(false);
		private volatile String runThread = "?";
		private volatile String fallbackThread = "?";
		private volatile long fallbackAtMs = -1;

		SlowSemaphoreCommand() {
			super(Setter
					.withGroupKey(HystrixCommandGroupKey.Factory.asKey("measurement"))
					.andCommandKey(HystrixCommandKey.Factory.asKey(COMMAND_KEY)));
		}

		@Override
		protected String run() throws Exception
		{
			runThread = Thread.currentThread().getName();
			try {
				Thread.sleep(RUN_DURATION_MS);
			} catch (InterruptedException e) {
				interrupted.set(true);
				throw e;
			}
			finished.set(true);
			return "completed";
		}

		@Override
		protected String getFallback()
		{
			fallbackThread = Thread.currentThread().getName();
			fallbackAtMs = System.currentTimeMillis();
			return "fallback";
		}
	}
}
