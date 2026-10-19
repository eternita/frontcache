/*
 * Copyright 2015 Eternita
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
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.frontcache.resilience.stream.FcDashboardJson;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The other half of the golden-key contract: {@code DashboardJsonKeysTest} froze what Hystrix emitted,
 * and this asserts the replacement emitter produces the same field names.
 *
 * Values are expected to differ - that is the migration - so only the key sets, the percentile map's
 * shape and the two literal {@code type} strings are contractual. One documented addition is allowed:
 * the watchdog's overrun counter (proposal section 6.2). Extra keys are safe because the dashboard
 * asserts presence and ignores what it does not know; a MISSING key is what blanks the panel.
 */
public class FcDashboardJsonTest {

	private static final String GOLDEN_FILE = "src/test/resources/dashboard-json-keys.txt";

	/** Added by us, not present in Hystrix's output. Section 6.2. */
	private static final String WATCHDOG_FIELD = "rollingCountBudgetOverrunObserved";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@After
	public void tearDown()
	{
		FcCommandMetrics.reset();
		FcResilienceRegistry.reset();
		FcThreadPools.shutdownAll();
		FcResilienceConfig.reset();
	}

	private static void configure(String commandKey, String poolKey)
	{
		Properties props = new Properties();
		props.setProperty("hystrix.command." + commandKey + ".execution.isolation.strategy", "THREAD");
		props.setProperty("hystrix.command." + commandKey + ".execution.isolation.thread.timeoutInMilliseconds", "10000");
		props.setProperty("hystrix.command." + commandKey + ".execution.isolation.semaphore.maxConcurrentRequests", "50");
		props.setProperty("hystrix.command." + commandKey + ".fallback.isolation.semaphore.maxConcurrentRequests", "50");
		props.setProperty("hystrix.threadpool." + poolKey + ".coreSize", "2");
		FcResilienceConfig.install(props);
	}

	@Test
	public void commandFrameCarriesEveryFieldHystrixEmitted() throws Exception
	{
		configure("Cache-Miss", "OriginPool");
		new SucceedingCommand("Cache-Miss", "OriginPool").execute();

		Set<String> emitted = fieldNames(FcDashboardJson.commandFrame(FcCommandMetrics.existing("Cache-Miss")));
		Set<String> golden = readGolden("HystrixCommand");

		Set<String> missing = new TreeSet<String>(golden);
		missing.removeAll(emitted);
		assertTrue("fields Hystrix emitted and we no longer do - each one of these blanks the dashboard: "
				+ missing, missing.isEmpty());

		Set<String> added = new TreeSet<String>(emitted);
		added.removeAll(golden);
		assertEquals("the only permitted addition is the watchdog counter", "[" + WATCHDOG_FIELD + "]",
				added.toString());
	}

	@Test
	public void threadPoolFrameCarriesEveryFieldHystrixEmitted() throws Exception
	{
		configure("Cache-Miss", "OriginPool");
		new SucceedingCommand("Cache-Miss", "OriginPool").execute();

		Set<String> emitted = fieldNames(FcDashboardJson.threadPoolFrame("OriginPool",
				FcThreadPools.existing("OriginPool")));

		assertEquals("thread-pool frame field set must match Hystrix's exactly",
				readGolden("HystrixThreadPool").toString(), new TreeSet<String>(emitted).toString());
	}

	/**
	 * The direct regression test for section 6.6 defect 1. A thread pool is a node-level resource, so
	 * its frames are emitted with no group comparison at all - the old code compared the POOL NAME
	 * against the caller's domain, which is false for every domain that can exist, and the console's
	 * thread-pool panel has therefore never rendered.
	 */
	@Test
	public void threadPoolFramesAreEmittedAlongsideCommandFrames() throws Exception
	{
		configure("Cache-Miss", "OriginPool");
		new SucceedingCommand("Cache-Miss", "OriginPool").execute();

		List<String> frames = FcDashboardJson.frames();

		int commandFrames = 0;
		int threadPoolFrames = 0;
		for (String frame : frames)
		{
			String type = MAPPER.readTree(frame).get("type").asText();
			if ("HystrixCommand".equals(type))
				commandFrames++;
			else if ("HystrixThreadPool".equals(type))
				threadPoolFrames++;
		}

		assertEquals("one command frame", 1, commandFrames);
		assertEquals("one thread-pool frame - this is the panel that has always been empty",
				1, threadPoolFrames);
	}

	@Test
	public void typeAndIdentityFieldsAreExactlyWhatTheDashboardDispatchesOn() throws Exception
	{
		configure("Cache-Hit", "OriginPool");
		new SucceedingCommand("Cache-Hit", "OriginPool").execute();

		JsonNode command = MAPPER.readTree(FcDashboardJson.commandFrame(FcCommandMetrics.existing("Cache-Hit")));
		assertEquals("HystrixCommand", command.get("type").asText());
		assertEquals("Cache-Hit", command.get("name").asText());
		assertEquals("test.example.com", command.get("group").asText());

		JsonNode pool = MAPPER.readTree(FcDashboardJson.threadPoolFrame("OriginPool",
				FcThreadPools.existing("OriginPool")));
		assertEquals("HystrixThreadPool", pool.get("type").asText());
		assertEquals("OriginPool", pool.get("name").asText());
	}

	@Test
	public void latencyMapsKeepTheirShapeAndOrder() throws Exception
	{
		configure("Cache-Hit", "OriginPool");
		new SucceedingCommand("Cache-Hit", "OriginPool").execute();

		JsonNode command = MAPPER.readTree(FcDashboardJson.commandFrame(FcCommandMetrics.existing("Cache-Hit")));
		for (String field : new String[] { "latencyExecute", "latencyTotal" })
		{
			assertNotNull(field + " is missing", command.get(field));
			assertEquals(field + " percentile keys, in order",
					"[0, 25, 50, 75, 90, 95, 99, 99.5, 100]",
					new ArrayList<String>(fieldNames(command.get(field).toString())).toString());
		}
	}

	/**
	 * The window has to be the real one: the dashboard divides by it to compute ratePerSecond, so a
	 * wrong value here silently scales every rate on the page.
	 */
	@Test
	public void countersAndPropertiesReflectRealValues() throws Exception
	{
		configure("Cache-Miss", "OriginPool");
		new SucceedingCommand("Cache-Miss", "OriginPool").execute();
		new FailingCommand("Cache-Miss", "OriginPool").execute();

		JsonNode frame = MAPPER.readTree(FcDashboardJson.commandFrame(FcCommandMetrics.existing("Cache-Miss")));

		assertEquals(1, frame.get("rollingCountSuccess").asLong());
		assertEquals(1, frame.get("rollingCountFailure").asLong());
		assertEquals(1, frame.get("rollingCountFallbackSuccess").asLong());
		assertEquals(2, frame.get("requestCount").asLong());
		assertEquals(1, frame.get("errorCount").asLong());
		assertEquals(50, frame.get("errorPercentage").asInt());
		assertEquals(false, frame.get("isCircuitBreakerOpen").asBoolean());

		assertEquals("THREAD", frame.get("propertyValue_executionIsolationStrategy").asText());
		assertEquals(10000, frame.get("propertyValue_executionIsolationThreadTimeoutInMilliseconds").asInt());
		assertEquals(50, frame.get("propertyValue_executionIsolationSemaphoreMaxConcurrentRequests").asInt());
		assertEquals("parsed and reported, never enforced",
				50, frame.get("propertyValue_fallbackIsolationSemaphoreMaxConcurrentRequests").asInt());
		assertEquals("structurally always zero", 0, frame.get("rollingCountFallbackRejection").asLong());
		assertEquals("the real window, because the dashboard divides by it",
				10000, frame.get("propertyValue_metricsRollingStatisticalWindowInMilliseconds").asInt());
		assertEquals("OriginPool", frame.get("threadPool").asText());
	}

	/** A configured domain reaches the JSON, so it is escaped rather than trusted. */
	@Test
	public void groupIsEscaped() throws Exception
	{
		configure("Quoted", "OriginPool");
		FcCommandMetrics metrics = FcCommandMetrics.getInstance("Quoted", "we\"ird\\domain", 10000);

		assertEquals("we\"ird\\domain", MAPPER.readTree(FcDashboardJson.commandFrame(metrics)).get("group").asText());
	}

	// ---- helpers ------------------------------------------------------------

	private static Set<String> fieldNames(String json) throws IOException
	{
		Set<String> names = new LinkedHashSet<String>();
		for (Iterator<String> it = MAPPER.readTree(json).fieldNames(); it.hasNext(); )
			names.add(it.next());
		return names;
	}

	private static Set<String> readGolden(String section) throws IOException
	{
		File file = new File(GOLDEN_FILE);
		assertTrue(GOLDEN_FILE + " is missing - it is the migration's wire contract", file.isFile());

		Set<String> keys = new TreeSet<String>();
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
		return keys;
	}

	private static class SucceedingCommand extends FcCommand<String> {

		SucceedingCommand(String commandKey, String poolKey) {
			super(Setter.withGroupKey("test.example.com").andCommandKey(commandKey).andThreadPoolKey(poolKey));
		}

		@Override
		protected String run()
		{
			return "ok";
		}
	}

	private static class FailingCommand extends FcCommand<String> {

		FailingCommand(String commandKey, String poolKey) {
			super(Setter.withGroupKey("test.example.com").andCommandKey(commandKey).andThreadPoolKey(poolKey));
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
}
