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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.serial.SerialHystrixDashboardData;

/**
 * The golden key set of proposal section 7.2, captured from Hystrix before anything is switched over.
 *
 * The dashboard consumer is vendored JavaScript that hard-fails on a missing field -
 * {@code assertNotNull()} in hystrixCommand.js:191 and hystrixThreadPool.js:141 throw, blanking the
 * panel with a console error and no server-side symptom at all. That is the worst failure shape in
 * this migration: silent where it is deployed, invisible where it is tested.
 *
 * So the field names Hystrix emits today are frozen into
 * src/test/resources/dashboard-json-keys.txt, and this test asserts they are still exactly what the
 * serializer produces. When the new emitter arrives (PR 3) its own test asserts its output against
 * the same file, which turns "a key went missing" from a production discovery into a red build.
 *
 * Field VALUES are expected to differ after the migration - that is the point of it. Only the key set
 * is contractual.
 */
public class DashboardJsonKeysTest {

	private static final String GOLDEN_FILE = "src/test/resources/dashboard-json-keys.txt";

	private static final String COMMAND_SECTION = "HystrixCommand";
	private static final String THREAD_POOL_SECTION = "HystrixThreadPool";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@BeforeClass
	public static void executeCommandsSoMetricsExist() throws Exception
	{
		// One THREAD-isolated command is enough to materialize both a command-metrics instance and a
		// thread-pool-metrics instance, which is what the serializer reads.
		new GoldenKeyCommand().execute();

		// The rolling-metrics stream is asynchronous; give it a moment to publish the first window.
		Thread.sleep(1000);
	}

	@Test
	public void commandFrameKeysMatchTheGoldenFile() throws Exception
	{
		assertEquals("HystrixCommand JSON keys changed - if this is deliberate, regenerate "
				+ GOLDEN_FILE + " and say why in the release notes",
				readGolden(COMMAND_SECTION), commandFrameKeys());
	}

	@Test
	public void threadPoolFrameKeysMatchTheGoldenFile() throws Exception
	{
		assertEquals("HystrixThreadPool JSON keys changed - if this is deliberate, regenerate "
				+ GOLDEN_FILE + " and say why in the release notes",
				readGolden(THREAD_POOL_SECTION), threadPoolFrameKeys());
	}

	/**
	 * The 36 keys hystrixCommand.js asserts must all be present. Checked separately from the golden
	 * comparison so that a regenerated golden file cannot quietly drop one of them.
	 */
	@Test
	public void everyKeyTheDashboardAssertsIsEmitted() throws Exception
	{
		List<String> emitted = commandFrameKeys();
		for (String required : new String[] {
				"reportingHosts", "type", "name", "group", "isCircuitBreakerOpen", "errorPercentage",
				"errorCount", "requestCount", "rollingCountCollapsedRequests", "rollingCountExceptionsThrown",
				"rollingCountFailure", "rollingCountFallbackFailure", "rollingCountFallbackRejection",
				"rollingCountFallbackSuccess", "rollingCountResponsesFromCache", "rollingCountSemaphoreRejected",
				"rollingCountShortCircuited", "rollingCountSuccess", "rollingCountThreadPoolRejected",
				"rollingCountTimeout", "rollingCountBadRequests", "currentConcurrentExecutionCount",
				"latencyExecute_mean", "latencyExecute", "propertyValue_circuitBreakerRequestVolumeThreshold",
				"propertyValue_circuitBreakerSleepWindowInMilliseconds",
				"propertyValue_circuitBreakerErrorThresholdPercentage", "propertyValue_circuitBreakerForceOpen",
				"propertyValue_executionIsolationStrategy",
				"propertyValue_executionIsolationThreadTimeoutInMilliseconds",
				"propertyValue_executionIsolationThreadInterruptOnTimeout",
				"propertyValue_executionIsolationSemaphoreMaxConcurrentRequests",
				"propertyValue_fallbackIsolationSemaphoreMaxConcurrentRequests",
				"propertyValue_requestCacheEnabled", "propertyValue_requestLogEnabled",
				"propertyValue_metricsRollingStatisticalWindowInMilliseconds" })
			assertTrue("hystrixCommand.js asserts '" + required + "' and it is not emitted", emitted.contains(required));
	}

	/** The percentile map shape the dashboard plots directly. */
	@Test
	public void latencyExecuteCarriesThePercentileMap() throws Exception
	{
		JsonNode latency = MAPPER.readTree(commandFrameJson()).get("latencyExecute");
		assertNotNull("latencyExecute is missing", latency);

		// Insertion order, not sorted - the dashboard reads specific keys out of this map, and the new
		// emitter should reproduce the same ordering so a byte-diff of two frames stays readable.
		List<String> percentiles = fieldNames(latency);
		assertEquals("[0, 25, 50, 75, 90, 95, 99, 99.5, 100]", percentiles.toString());
	}

	// ---- helpers ------------------------------------------------------------

	private static String commandFrameJson()
	{
		HystrixCommandMetrics metrics = HystrixCommandMetrics.getInstance(
				HystrixCommandKey.Factory.asKey(GoldenKeyCommand.COMMAND_KEY));
		assertNotNull("no command metrics registered - did the command execute?", metrics);
		return SerialHystrixDashboardData.toJsonString(metrics);
	}

	private static String threadPoolFrameJson()
	{
		HystrixThreadPoolMetrics metrics = null;
		for (HystrixThreadPoolMetrics candidate : HystrixThreadPoolMetrics.getInstances())
			if (GoldenKeyCommand.POOL_KEY.equals(candidate.getThreadPoolKey().name()))
				metrics = candidate;

		assertNotNull("no thread pool metrics registered for " + GoldenKeyCommand.POOL_KEY, metrics);
		return SerialHystrixDashboardData.toJsonString(metrics);
	}

	private static List<String> commandFrameKeys() throws IOException
	{
		return sortedFieldNames(commandFrameJson());
	}

	private static List<String> threadPoolFrameKeys() throws IOException
	{
		return sortedFieldNames(threadPoolFrameJson());
	}

	private static List<String> sortedFieldNames(String json) throws IOException
	{
		List<String> names = fieldNames(MAPPER.readTree(json));
		Collections.sort(names);
		return names;
	}

	private static List<String> fieldNames(JsonNode node)
	{
		List<String> names = new ArrayList<String>();
		for (Iterator<String> it = node.fieldNames(); it.hasNext(); )
			names.add(it.next());
		return names;
	}

	/**
	 * The golden file holds both key sets, one per section, so a reviewer sees the whole wire contract
	 * in one place:
	 *
	 * <pre>
	 * [HystrixCommand]
	 * currentConcurrentExecutionCount
	 * ...
	 * [HystrixThreadPool]
	 * ...
	 * </pre>
	 */
	private static List<String> readGolden(String section) throws IOException
	{
		File file = new File(GOLDEN_FILE);
		assertTrue(GOLDEN_FILE + " is missing - it is the migration's wire contract and must be committed",
				file.isFile());

		List<String> keys = new ArrayList<String>();
		boolean inSection = false;
		for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
		{
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#"))
				continue;

			if (trimmed.startsWith("["))
			{
				inSection = trimmed.equals("[" + section + "]");
				continue;
			}

			if (inSection)
				keys.add(trimmed);
		}

		assertTrue("section [" + section + "] not found in " + GOLDEN_FILE, !keys.isEmpty());
		Collections.sort(keys);
		return keys;
	}

	private static class GoldenKeyCommand extends HystrixCommand<String> {

		static final String COMMAND_KEY = "Golden-Key-Capture";
		static final String POOL_KEY = "GoldenKeyPool";

		GoldenKeyCommand() {
			super(Setter
					.withGroupKey(HystrixCommandGroupKey.Factory.asKey("golden"))
					.andCommandKey(HystrixCommandKey.Factory.asKey(COMMAND_KEY))
					.andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey(POOL_KEY)));
		}

		@Override
		protected String run()
		{
			return "ok";
		}
	}

	/**
	 * Regenerates the golden file from the current Hystrix serializer. Gated on a system property so
	 * the normal build can only ever compare - a test that silently rewrites its own expectation is not
	 * a contract. Run deliberately, and review the diff, when a Hystrix upgrade legitimately changes
	 * the wire format:
	 *
	 * <pre>./gradlew :frontcache-core:test --tests '*DashboardJsonKeysTest' -Dfc.dashboard.keys.regenerate=true</pre>
	 */
	@Test
	public void regenerateGoldenFileWhenAsked() throws Exception
	{
		if (!Boolean.getBoolean("fc.dashboard.keys.regenerate"))
			return;

		StringBuilder sb = new StringBuilder();
		sb.append("# Dashboard JSON key sets, captured from Hystrix 1.5.18 via SerialHystrixDashboardData.\n");
		sb.append("# This is the compatibility contract of the Resilience4j migration: the new emitter must\n");
		sb.append("# produce exactly these keys, because the vendored dashboard JS throws on a missing one.\n");
		sb.append("# Regenerate with -Dfc.dashboard.keys.regenerate=true and review the diff - never edit by hand.\n");
		sb.append("# See docs/hystrix-to-resilience4j-proposal.md section 7.2.\n");
		sb.append("\n[").append(COMMAND_SECTION).append("]\n");
		for (String key : commandFrameKeys())
			sb.append(key).append('\n');
		sb.append("\n[").append(THREAD_POOL_SECTION).append("]\n");
		for (String key : threadPoolFrameKeys())
			sb.append(key).append('\n');

		Files.write(new File(GOLDEN_FILE).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
		System.out.println("wrote " + GOLDEN_FILE);
	}

}
