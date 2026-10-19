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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Applying an edited conf/resilience.properties without a restart.
 *
 * <h2>Why this needs testing at all</h2>
 *
 * The file is read once into a cached singleton, and TWO further caches are built from it - the
 * Resilience4j objects per command key and the executors per pool key. So "re-read the file" applies
 * nothing a request can observe, and a reload that got only that far would be indistinguishable, from
 * the console, from one that worked. These tests are the difference.
 *
 * <h2>And why the warnings are asserted</h2>
 *
 * Two things genuinely cannot be applied in place, and both are silent: dropping the cached breakers
 * loses circuit state, and a thread pool's queue TYPE is fixed at construction. A reload that did not
 * report those would leave an operator believing the live node matches its file.
 */
public class FcResilienceReloadTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private String previousHome;
	private File conf;

	@Before
	public void setUp() throws IOException
	{
		previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		conf = tmp.newFolder("conf");
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, conf.getParentFile().getAbsolutePath());

		// FCConfig's static initializer throws without one, and which test class first touches it
		// depends on run order.
		write("frontcache.properties", "front-cache.default-domain=localhost\n");

		FcResilienceConfig.reset();
		FcResilienceRegistry.reset();
		FcThreadPools.shutdownAll();
	}

	@After
	public void tearDown()
	{
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);

		FcResilienceConfig.reset();
		FcResilienceRegistry.reset();
		FcThreadPools.shutdownAll();
	}

	private void write(String fileName, String content) throws IOException
	{
		try (FileWriter writer = new FileWriter(new File(conf, fileName))) {
			writer.write(content);
		}
	}

	// ---- the file is re-read -----------------------------------------------------------------

	@Test
	public void aChangedCommandTimeoutTakesEffect() throws IOException
	{
		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=1234\n");
		assertEquals(1234, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());

		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=9876\n");
		FcResilienceReload.reload();

		assertEquals(9876, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/**
	 * A file that has been deleted leaves the node on built-in defaults rather than throwing. Same rule
	 * as the initial load, and for the same reason: this runs from a management request, and a node
	 * that starts answering 500s because a config file went missing is worse than one on defaults.
	 */
	@Test
	public void adeletedFileFallsBackToDefaults() throws IOException
	{
		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=1234\n");
		assertEquals(1234, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());

		assertTrue(new File(conf, "resilience.properties").delete());
		FcResilienceReload.reload();

		assertEquals(FcResilienceConfig.DEFAULT_TIMEOUT_MS,
				FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	// ---- the cached Resilience4j objects are dropped ------------------------------------------

	/**
	 * The cached breaker has to go, or a command whose thresholds just changed keeps running on the old
	 * ones - which is the failure mode this whole class exists to prevent.
	 */
	@Test
	public void cachedCircuitBreakersAreDropped() throws IOException
	{
		write("resilience.properties", "resilience.command.A.circuitBreaker.errorThresholdPercentage=50\n");

		FcCommandConfig config = FcResilienceConfig.getInstance().forCommand("A", null);
		FcResilienceRegistry.circuitBreaker(config); // caches one under "A"

		write("resilience.properties", "resilience.command.A.circuitBreaker.errorThresholdPercentage=90\n");
		FcResilienceReload.reload();

		assertEquals("the breaker must be gone, so the next command rebuilds it from the new config",
				null, FcResilienceRegistry.existingCircuitBreaker("A"));
	}

	/** ...and the operator is told, because an open circuit snapping closed is visible on the dashboard. */
	@Test
	public void theCircuitStateResetIsReported() throws IOException
	{
		write("resilience.properties", "resilience.command.A.circuitBreaker.errorThresholdPercentage=50\n");

		List<String> warnings = FcResilienceReload.reload().warnings();

		assertTrue("expected a circuit-state warning, got " + warnings,
				warnings.stream().anyMatch(w -> w.contains("circuit-breaker state")));
	}

	// ---- thread pools: three numbers apply, the fourth cannot --------------------------------

	@Test
	public void poolSizesAreReAppliedInPlace() throws IOException
	{
		write("resilience.properties", "resilience.threadpool.P.coreSize=5\nresilience.threadpool.P.maxQueueSize=-1\n");

		ThreadPoolExecutor pool = FcThreadPools.pool("P");
		assertEquals(5, pool.getCorePoolSize());

		write("resilience.properties", "resilience.threadpool.P.coreSize=25\nresilience.threadpool.P.maxQueueSize=-1\n");
		List<String> warnings = FcResilienceReload.reload().warnings();

		assertSame("the SAME executor must be reconfigured - recreating it would abandon in-flight"
				+ " origin calls", pool, FcThreadPools.pool("P"));
		assertEquals(25, pool.getCorePoolSize());
		assertTrue(25 <= pool.getMaximumPoolSize());

		assertFalse("nothing to warn about - the queue setting did not change",
				warnings.stream().anyMatch(w -> w.contains("maxQueueSize")));
	}

	@Test
	public void poolShrinkingAlsoWorks() throws IOException
	{
		write("resilience.properties", "resilience.threadpool.P.coreSize=40\nresilience.threadpool.P.maxQueueSize=-1\n");

		ThreadPoolExecutor pool = FcThreadPools.pool("P");
		assertEquals(40, pool.getCorePoolSize());

		// Shrinking needs core lowered before maximum, growing needs the opposite - setCorePoolSize and
		// setMaximumPoolSize each throw when the new value crosses the other one.
		write("resilience.properties", "resilience.threadpool.P.coreSize=2\nresilience.threadpool.P.maxQueueSize=-1\n");
		FcResilienceReload.reload();

		assertEquals(2, pool.getCorePoolSize());
		assertEquals(2, pool.getMaximumPoolSize());
	}

	/**
	 * The one setting that needs a restart, and the reason it is reported rather than ignored.
	 *
	 * maxQueueSize selects the queue TYPE at construction - below 1 means a SynchronousQueue that
	 * rejects instead of buffering - and an executor's queue cannot be replaced. Silently ignoring the
	 * change is how an operator concludes the setting does nothing.
	 */
	@Test
	public void aChangedMaxQueueSizeIsReportedAndNotApplied() throws IOException
	{
		write("resilience.properties", "resilience.threadpool.P.coreSize=5\nresilience.threadpool.P.maxQueueSize=-1\n");

		ThreadPoolExecutor pool = FcThreadPools.pool("P");
		assertTrue("the fixture starts with no queue", pool.getQueue() instanceof SynchronousQueue);

		write("resilience.properties", "resilience.threadpool.P.coreSize=5\nresilience.threadpool.P.maxQueueSize=100\n");
		List<String> warnings = FcResilienceReload.reload().warnings();

		assertTrue("the live pool must still have no queue", pool.getQueue() instanceof SynchronousQueue);
		assertTrue("expected a restart-needed warning naming the pool, got " + warnings,
				warnings.stream().anyMatch(w -> w.contains("threadpool.P.maxQueueSize") && w.contains("RESTART")));
	}
}
