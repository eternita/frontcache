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
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import org.frontcache.hystrix.FcCommandConfig.Isolation;
import org.junit.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * conf/hystrix.properties keeps its name and its keys, so an operator's existing tuning has to survive
 * the migration untouched. These tests pin the resolution rules and the translation to Resilience4j.
 */
public class FcResilienceConfigTest {

	/** The real shipped file, so the assertions below are about production values, not invented ones. */
	private static Properties shippedProperties()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command.default.execution.isolation.thread.interruptOnTimeout", "true");
		props.setProperty("hystrix.command.default.circuitBreaker.errorThresholdPercentage", "50");
		props.setProperty("hystrix.command.default.circuitBreaker.requestVolumeThreshold", "50");
		props.setProperty("hystrix.command.default.circuitBreaker.sleepWindowInMilliseconds", "5000");
		props.setProperty("hystrix.command.Input-Requests.execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("hystrix.command.Input-Requests.execution.isolation.thread.timeoutInMilliseconds", "20000");
		props.setProperty("hystrix.command.Input-Requests.execution.isolation.semaphore.maxConcurrentRequests", "50");
		props.setProperty("hystrix.command.Input-Requests.fallback.isolation.semaphore.maxConcurrentRequests", "50");
		props.setProperty("hystrix.command.Cache-Hits.execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("hystrix.command.Cache-Hits.execution.isolation.thread.timeoutInMilliseconds", "1500");
		props.setProperty("hystrix.command.Cache-Origin-http.execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.command.Cache-Origin-http.execution.isolation.thread.timeoutInMilliseconds", "10000");
		props.setProperty("hystrix.threadpool.default.coreSize", "100");
		props.setProperty("hystrix.threadpool.OriginHitsPool.coreSize", "100");
		props.setProperty("hystrix.config.stream.maxConcurrentConnections", "15");
		return props;
	}

	@Test
	public void perCommandOverridesWinOverDefaultsWhichWinOverBuiltIns()
	{
		FcResilienceConfig config = new FcResilienceConfig(shippedProperties());

		FcCommandConfig inputRequests = config.forCommand("Input-Requests", null);
		assertEquals("per-command override", 20000, inputRequests.getTimeoutMs());
		assertEquals("per-command override", Isolation.SEMAPHORE, inputRequests.getIsolation());
		assertEquals("falls through to hystrix.command.default", 50, inputRequests.getRequestVolumeThreshold());
		assertEquals("falls through to hystrix.command.default", 5000, inputRequests.getSleepWindowMs());
		assertEquals("no key anywhere -> Hystrix built-in default",
				FcResilienceConfig.DEFAULT_ROLLING_STATS_MS, inputRequests.getRollingStatsMs());

		FcCommandConfig cacheOrigin = config.forCommand("Cache-Origin-http", "OriginHitsPool");
		assertEquals(10000, cacheOrigin.getTimeoutMs());
		assertEquals(Isolation.THREAD, cacheOrigin.getIsolation());
		assertEquals("OriginHitsPool", cacheOrigin.getThreadPoolKey());
	}

	@Test
	public void unknownCommandGetsDefaultsAndDoesNotThrow()
	{
		FcCommandConfig unknown = new FcResilienceConfig(shippedProperties()).forCommand("Not-In-The-File", null);

		assertEquals(FcResilienceConfig.DEFAULT_TIMEOUT_MS, unknown.getTimeoutMs());
		assertEquals("defaults say THREAD, as Hystrix does", Isolation.THREAD, unknown.getIsolation());
		assertEquals(50, unknown.getErrorThresholdPercentage());
	}

	/**
	 * A typo in one tuning key must not take the node down - but must not pass silently either, since
	 * the symptom an operator would see is "my timeout is being ignored".
	 */
	@Test
	public void malformedNumberFallsBackToTheDefault()
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command.Typo.execution.isolation.thread.timeoutInMilliseconds", "20 000");

		assertEquals(FcResilienceConfig.DEFAULT_TIMEOUT_MS,
				new FcResilienceConfig(props).forCommand("Typo", null).getTimeoutMs());
	}

	@Test
	public void circuitBreakerConfigMatchesHystrixSemantics()
	{
		CircuitBreakerConfig breaker = new FcResilienceConfig(shippedProperties())
				.forCommand("Input-Requests", null).circuitBreakerConfig();

		assertEquals(50f, breaker.getFailureRateThreshold(), 0.001);
		assertEquals(50, breaker.getMinimumNumberOfCalls());
		assertEquals("sleepWindowInMilliseconds -> waitDurationInOpenState",
				5000L, breaker.getWaitIntervalFunctionInOpenState().apply(1).longValue());

		// R4j defaults to 10 here. Hystrix probes a recovering origin with exactly one call, and ten
		// concurrent probes against something that just came back up is how you knock it down again.
		assertEquals("half-open probe count must be 1, not R4j's default of 10",
				1, breaker.getPermittedNumberOfCallsInHalfOpenState());
		assertFalse("transition on the next call, as Hystrix does",
				breaker.isAutomaticTransitionFromOpenToHalfOpenEnabled());
	}

	/**
	 * Bulkhead rejections and thread-pool rejections must not count against the failure rate. Hystrix
	 * counts neither; Resilience4j's decorators would record both, because they are just exceptions
	 * thrown by the decorated call. Left in, a saturated node opens its own circuits for being busy.
	 */
	@Test
	public void rejectionsAndBadRequestsAreExcludedFromTheFailureRate()
	{
		CircuitBreakerConfig breaker = new FcResilienceConfig(shippedProperties())
				.forCommand("Input-Requests", null).circuitBreakerConfig();

		assertTrue("a bad request says nothing about dependency health",
				breaker.getIgnoreExceptionPredicate().test(new FcBadRequestException("bad")));
		assertTrue("semaphore rejection is load shedding, not failure",
				breaker.getIgnoreExceptionPredicate().test(
						io.github.resilience4j.bulkhead.BulkheadFullException.createBulkheadFullException(
								io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("t"))));
		assertTrue("thread-pool rejection is load shedding, not failure",
				breaker.getIgnoreExceptionPredicate().test(new java.util.concurrent.RejectedExecutionException()));
		assertFalse("a real failure still counts",
				breaker.getIgnoreExceptionPredicate().test(new RuntimeException("origin down")));
	}

	@Test
	public void semaphoreBulkheadNeverQueues()
	{
		assertEquals("a non-zero wait would turn Hystrix's immediate rejection into a queued wait",
				Duration.ZERO,
				new FcResilienceConfig(shippedProperties()).forCommand("Input-Requests", null)
						.bulkheadConfig().getMaxWaitDuration());
	}

	/**
	 * Parsed and reported, never enforced - see proposal section 6.3. The value has to survive parsing
	 * because the dashboard asserts the field exists.
	 */
	@Test
	public void fallbackSemaphoreIsParsedButNotTurnedIntoABulkhead()
	{
		FcCommandConfig config = new FcResilienceConfig(shippedProperties()).forCommand("Input-Requests", null);

		assertEquals(50, config.getFallbackSemaphoreMaxConcurrentRequests());
		assertEquals("the enforced semaphore is the execution one, and it is the only one",
				50, config.getSemaphoreMaxConcurrentRequests());
	}

	@Test
	public void streamAndPoolKeysAreRead()
	{
		FcResilienceConfig config = new FcResilienceConfig(shippedProperties());

		assertEquals(15, config.streamMaxConcurrentConnections());
		assertEquals(100, config.poolCoreSize("OriginHitsPool"));
		assertEquals("no key -> hystrix.threadpool.default.coreSize", 100, config.poolCoreSize("SomeOtherPool"));
		assertEquals("Hystrix's default is -1: no queue at all", -1, config.poolMaxQueueSize("OriginHitsPool"));
	}

	/**
	 * The queue choice is the one saturation behavior that would only show up under load. maxQueueSize
	 * of -1 means "reject when every thread is busy", not "buffer indefinitely".
	 */
	@Test
	public void poolWithoutAQueueRejectsInsteadOfBuffering()
	{
		ThreadPoolExecutor noQueue = FcThreadPools.create("NoQueuePool", new FcResilienceConfig(shippedProperties()));
		assertTrue("maxQueueSize -1 must be a SynchronousQueue",
				noQueue.getQueue() instanceof SynchronousQueue);
		noQueue.shutdownNow();

		Properties queued = shippedProperties();
		queued.setProperty("hystrix.threadpool.QueuedPool.maxQueueSize", "25");
		ThreadPoolExecutor withQueue = FcThreadPools.create("QueuedPool", new FcResilienceConfig(queued));
		assertTrue(withQueue.getQueue() instanceof LinkedBlockingQueue);
		assertEquals(25, withQueue.getQueue().remainingCapacity());
		withQueue.shutdownNow();
	}

	/**
	 * FallbackLogger's return values land in the failed-requests log and in {@code RequestLogger}'s
	 * positional output, which log tooling parses - so they are pinned as literals here.
	 *
	 * This started life as a test that every {@code HystrixEventType} FallbackLogger referenced had a
	 * same-named {@link FcEventType}, which guarded the switch-over in PR 2. With Hystrix gone there is
	 * nothing left to compare against, so it now asserts the strings themselves - which is the thing
	 * that actually must not change, and outlives the migration.
	 */
	@Test
	public void failureTypeStringsAreUnchanged()
	{
		assertEquals("short-circuited", FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.SHORT_CIRCUITED)));
		assertEquals("timeout", FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.TIMEOUT)));
		assertEquals("rejected", FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.THREAD_POOL_REJECTED)));
		assertEquals("rejected", FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.SEMAPHORE_REJECTED)));
		assertEquals("bad-request", FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.BAD_REQUEST)));
		assertEquals("failure", FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.FAILURE)));

		assertEquals("no known event -> error", "error",
				FallbackLogger.failureType(java.util.Arrays.asList(FcEventType.SUCCESS)));
		assertEquals("null -> error", "error", FallbackLogger.failureType(null));

		// Precedence matters: a short-circuited call that also timed out is reported as short-circuited.
		assertEquals("short-circuited", FallbackLogger.failureType(
				java.util.Arrays.asList(FcEventType.TIMEOUT, FcEventType.SHORT_CIRCUITED)));

		// The watchdog's overrun is not a failure of any kind - the call succeeded, just late.
		assertEquals("error", FallbackLogger.failureType(
				java.util.Arrays.asList(FcEventType.BUDGET_OVERRUN_OBSERVED)));
	}
}
