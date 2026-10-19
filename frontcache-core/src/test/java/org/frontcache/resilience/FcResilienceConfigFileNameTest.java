/*
 * Copyright 2018 Eternita
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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The config FILE was renamed in 2.7 (conf/hystrix.properties -> conf/resilience.properties), read under
 * the old name for one more release, and **2.9.0 stopped reading it**.
 *
 * These tests drive that off a real FRONTCACHE_HOME on disk, because the interesting part is not the
 * parser - {@link FcResilienceConfigTest} covers that - but the file lookup, and the lookup has a trap
 * in it: {@code FCConfig.getConfigInputStream} reports "not found" by THROWING, so a missing file has
 * to be caught rather than propagated or the node fails to start instead of falling back to defaults.
 *
 * <h2>What replaced the fallback is a message, and it is the point of these tests</h2>
 *
 * Losing the legacy name silently would mean a node that starts fine and runs every command on
 * built-in defaults - a 1-second origin timeout and 10 concurrent permits - which is invisible until
 * load arrives and then looks like an origin problem. So a leftover hystrix.properties is still looked
 * FOR, never read, purely to say so: an error when it is the only file present, a warning when it sits
 * beside a live one. {@code aLeftoverLegacyFileIsReportedNotRead} is the case that matters.
 *
 * The KEY PREFIX is a separate contract and did not change: {@code hystrix.command.*} inside a
 * correctly-named file is still read, indefinitely. Renaming the file was always the whole ask.
 */
public class FcResilienceConfigFileNameTest {

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

		// FCConfig's static initializer reads conf/frontcache.properties from whatever home is set when
		// it is first touched, and throws if it is missing - so the fixture home needs one even though
		// nothing in these tests reads a property out of it.
		write("frontcache.properties", "front-cache.default-domain=localhost\n");

		FcResilienceConfig.reset();
	}

	@After
	public void tearDown()
	{
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);

		FcResilienceConfig.reset();
	}

	@Test
	public void readsTheNewFileName() throws IOException
	{
		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=1234");

		assertEquals(1234, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/**
	 * The removal. A node with only the legacy file runs on built-in defaults - it does NOT pick the
	 * old name up, and it does not throw either.
	 */
	@Test
	public void theLegacyFileNameIsNotRead() throws IOException
	{
		write("hystrix.properties", "hystrix.command.A.execution.isolation.thread.timeoutInMilliseconds=2345");

		assertEquals("the pre-2.7 file name must not be read as of 2.9.0",
				FcResilienceConfig.DEFAULT_TIMEOUT_MS,
				FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/**
	 * ...but it is not silent about it, which is the whole reason the file name is still looked for.
	 * A node quietly running every command at timeout=1000 and 10 permits looks like an origin problem,
	 * not a config problem, and the operator has no reason to suspect a file they never touched.
	 */
	@Test
	public void aLeftoverLegacyFileIsReportedNotRead() throws IOException
	{
		write("hystrix.properties", "hystrix.command.A.execution.isolation.thread.timeoutInMilliseconds=2345");

		Logger configLogger = (Logger) LoggerFactory.getLogger(FcResilienceConfig.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
		appender.start();
		configLogger.addAppender(appender);

		try {
			FcResilienceConfig.getInstance();

			List<ILoggingEvent> errors = appender.list.stream()
					.filter(event -> Level.ERROR == event.getLevel())
					.toList();

			assertEquals("one error expected, got " + errors, 1, errors.size());
			String message = errors.get(0).getFormattedMessage();
			assertTrue(message, message.contains("hystrix.properties"));
			assertTrue("must name the fix, not just the symptom", message.contains("resilience.properties"));
		} finally {
			configLogger.detachAppender(appender);
		}
	}

	/** Renaming the file without touching the keys inside it is enough - that is the documented advice. */
	@Test
	public void newFileNameWithLegacyKeysInsideIt() throws IOException
	{
		write("resilience.properties", "hystrix.command.A.execution.isolation.thread.timeoutInMilliseconds=3456");

		assertEquals(3456, FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	/**
	 * Both present: the live file is used and the legacy one contributes nothing - not even the keys
	 * only it sets. Kept from when the old name was a fallback, because "I renamed it but my old value
	 * is still winning" and "half my keys came from the file I deleted" are both bug reports this
	 * forecloses.
	 */
	@Test
	public void newFileNameWinsOverTheLegacyOneAndTheyAreNotMerged() throws IOException
	{
		write("resilience.properties", "resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=4567");
		write("hystrix.properties",
				"resilience.command.A.execution.isolation.thread.timeoutInMilliseconds=9999\n"
				+ "resilience.command.B.execution.isolation.thread.timeoutInMilliseconds=8888");

		FcResilienceConfig config = FcResilienceConfig.getInstance();
		assertEquals(4567, config.forCommand("A", null).getTimeoutMs());
		assertEquals("a key only the legacy file sets must NOT leak in",
				FcResilienceConfig.DEFAULT_TIMEOUT_MS, config.forCommand("B", null).getTimeoutMs());
	}

	/**
	 * No file present. This must be built-in defaults rather than a thrown exception: the config
	 * singleton is first touched on the first request, so throwing here would turn a missing tuning file
	 * into a node that answers 500s instead of one that runs with defaults.
	 */
	@Test
	public void noFileAtAllMeansBuiltInDefaults()
	{
		assertEquals(FcResilienceConfig.DEFAULT_TIMEOUT_MS,
				FcResilienceConfig.getInstance().forCommand("A", null).getTimeoutMs());
	}

	private void write(String name, String content) throws IOException
	{
		FileWriter writer = new FileWriter(new File(conf, name));
		try {
			writer.write(content);
		} finally {
			writer.close();
		}
	}
}
