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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Properties;

import org.junit.After;
import org.junit.Test;

/**
 * The kill switch of proposal section 14.1: {@code front-cache.resilience.impl=hystrix|resilience4j}.
 *
 * What these tests are for is not "does Hystrix still work" - it is that everything ABOVE the library
 * boundary is identical on both paths, because that is what makes a rollback a config edit. Three
 * properties matter:
 *
 *  - the same {@link FcEventType} events are recorded, so FallbackLogger's strings and the
 *    failed-requests log do not change;
 *  - {@link FcCommandMetrics} is populated either way - the counters are recorded above the branch, so
 *    the metrics stream (PR 3) keeps working after a rollback instead of blanking the dashboard;
 *  - the exception types callers see ({@link FcBadRequestException}, {@link FcCommandException}) are
 *    ours on both paths, so ConcurrentIncludeProcessor's catch does not care which backend ran.
 */
public class FcCommandKillSwitchTest {

	private static final String GROUP = "test.example.com";

	@After
	public void tearDown()
	{
		System.clearProperty(FcCommand.RESILIENCE_IMPL_KEY);
		FcResilienceRegistry.reset();
		FcCommandMetrics.reset();
		FcThreadPools.shutdownAll();
		FcResilienceConfig.reset();
	}

	private static void useBackend(String impl, String commandKey)
	{
		System.setProperty(FcCommand.RESILIENCE_IMPL_KEY, impl);

		Properties props = new Properties();
		props.setProperty("hystrix.command." + commandKey + ".execution.isolation.strategy", "SEMAPHORE");
		props.setProperty("hystrix.command." + commandKey + ".execution.isolation.semaphore.maxConcurrentRequests", "20");
		FcResilienceConfig.install(props);
	}

	@Test
	public void defaultIsResilience4j()
	{
		System.clearProperty(FcCommand.RESILIENCE_IMPL_KEY);
		assertFalse("the switch must default to resilience4j, not to the legacy path",
				FcCommand.isHystrixBackend());

		System.setProperty(FcCommand.RESILIENCE_IMPL_KEY, "hystrix");
		assertTrue(FcCommand.isHystrixBackend());

		System.setProperty(FcCommand.RESILIENCE_IMPL_KEY, " HYSTRIX ");
		assertTrue("trimmed and case-insensitive - operators type what they type",
				FcCommand.isHystrixBackend());

		System.setProperty(FcCommand.RESILIENCE_IMPL_KEY, "typo");
		assertFalse("anything unrecognized means the current implementation, never the legacy one",
				FcCommand.isHystrixBackend());
	}

	@Test
	public void hystrixBackendResolvesTheFallbackAndRecordsTheSameEvents()
	{
		String key = "Kill-Switch-Hystrix-Failure";
		useBackend(FcCommand.IMPL_HYSTRIX, key);

		FailingCommand command = new FailingCommand(key);
		assertEquals("fallback", command.execute());

		assertTrue(command.getExecutionEvents().contains(FcEventType.FAILURE));
		assertTrue(command.getExecutionEvents().contains(FcEventType.FALLBACK_SUCCESS));
		assertNotNull("run()'s exception still reaches the fallback", command.getFailedExecutionException());
		assertEquals("origin down", command.getFailedExecutionException().getMessage());
		assertEquals("failure", FallbackLogger.failureType(command.getExecutionEvents()));
	}

	@Test
	public void resilience4jBackendRecordsTheSameEvents()
	{
		String key = "Kill-Switch-R4j-Failure";
		useBackend(FcCommand.IMPL_RESILIENCE4J, key);

		FailingCommand command = new FailingCommand(key);
		assertEquals("fallback", command.execute());

		assertTrue(command.getExecutionEvents().contains(FcEventType.FAILURE));
		assertTrue(command.getExecutionEvents().contains(FcEventType.FALLBACK_SUCCESS));
		assertEquals("origin down", command.getFailedExecutionException().getMessage());
		assertEquals("failure", FallbackLogger.failureType(command.getExecutionEvents()));
	}

	/**
	 * The property that makes the rollback safe once PR 3 rewrites the stream: the emitter reads
	 * FcCommandMetrics, so those counters have to be populated on the legacy path too.
	 */
	@Test
	public void metricsAreRecordedOnBothBackends()
	{
		String hystrixKey = "Kill-Switch-Metrics-Hystrix";
		useBackend(FcCommand.IMPL_HYSTRIX, hystrixKey);
		new SucceedingCommand(hystrixKey).execute();
		new FailingCommand(hystrixKey).execute();

		FcCommandMetrics viaHystrix = FcCommandMetrics.existing(hystrixKey);
		assertNotNull("the legacy path must still register metrics", viaHystrix);
		assertEquals(1, viaHystrix.getRollingCount(FcEventType.SUCCESS));
		assertEquals(1, viaHystrix.getRollingCount(FcEventType.FAILURE));
		assertEquals(1, viaHystrix.getRollingCount(FcEventType.FALLBACK_SUCCESS));
		assertEquals(2, viaHystrix.getRollingTotalRequests());
		assertEquals(50, viaHystrix.getErrorPercentage());

		String r4jKey = "Kill-Switch-Metrics-R4j";
		useBackend(FcCommand.IMPL_RESILIENCE4J, r4jKey);
		new SucceedingCommand(r4jKey).execute();
		new FailingCommand(r4jKey).execute();

		FcCommandMetrics viaR4j = FcCommandMetrics.existing(r4jKey);
		assertEquals("both backends produce the same counter shape",
				viaHystrix.getRollingCount(FcEventType.SUCCESS), viaR4j.getRollingCount(FcEventType.SUCCESS));
		assertEquals(viaHystrix.getRollingCount(FcEventType.FAILURE), viaR4j.getRollingCount(FcEventType.FAILURE));
		assertEquals(viaHystrix.getRollingCount(FcEventType.FALLBACK_SUCCESS),
				viaR4j.getRollingCount(FcEventType.FALLBACK_SUCCESS));
		assertEquals(viaHystrix.getErrorPercentage(), viaR4j.getErrorPercentage());
	}

	@Test
	public void badRequestSurfacesAsOurTypeOnBothBackends()
	{
		for (String impl : new String[] { FcCommand.IMPL_HYSTRIX, FcCommand.IMPL_RESILIENCE4J })
		{
			String key = "Kill-Switch-Bad-Request-" + impl;
			useBackend(impl, key);

			BadRequestCommand command = new BadRequestCommand(key);
			try {
				command.execute();
				fail(impl + ": FcBadRequestException must reach the caller");
			} catch (FcBadRequestException expected) {
				assertEquals(impl + ": message preserved", "malformed url", expected.getMessage());
				assertTrue(impl, command.getExecutionEvents().contains(FcEventType.BAD_REQUEST));
				assertFalse(impl + ": no fallback for a bad request",
						command.getExecutionEvents().contains(FcEventType.FALLBACK_SUCCESS));
			}
		}
	}

	/**
	 * ConcurrentIncludeProcessor catches FcCommandException and must not have to know which library ran
	 * the command - so the legacy path translates HystrixRuntimeException rather than letting it out.
	 */
	@Test
	public void missingFallbackThrowsFcCommandExceptionOnBothBackends()
	{
		for (String impl : new String[] { FcCommand.IMPL_HYSTRIX, FcCommand.IMPL_RESILIENCE4J })
		{
			String key = "Kill-Switch-No-Fallback-" + impl;
			useBackend(impl, key);

			try {
				new NoFallbackCommand(key).execute();
				fail(impl + ": expected FcCommandException");
			} catch (FcCommandException expected) {
				assertEquals(impl, key, expected.getCommandKey());
				assertEquals(impl + ": no Hystrix type escapes", FcEventType.FAILURE, expected.getFailureType());
			}
		}
	}

	// ---- fixtures -----------------------------------------------------------

	private static class FailingCommand extends FcCommand<String> {

		FailingCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			throw new RuntimeException("origin down");
		}

		@Override
		protected String getFallback()
		{
			return "fallback";
		}
	}

	private static class SucceedingCommand extends FcCommand<String> {

		SucceedingCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			return "ok";
		}
	}

	private static class BadRequestCommand extends FcCommand<String> {

		BadRequestCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			throw new FcBadRequestException("malformed url");
		}

		@Override
		protected String getFallback()
		{
			return "fallback";
		}
	}

	private static class NoFallbackCommand extends FcCommand<String> {

		NoFallbackCommand(String commandKey) {
			super(Setter.withGroupKey(GROUP).andCommandKey(commandKey));
		}

		@Override
		protected String run()
		{
			throw new RuntimeException("origin down");
		}
	}
}
