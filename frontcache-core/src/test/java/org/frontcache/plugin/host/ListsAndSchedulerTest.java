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
package org.frontcache.plugin.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.FCConfig;
import org.frontcache.guard.GuardRuleEngine;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginManifest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * E6's two additions: {@code writeList} - a plugin changing what a core {@code client-ip:@lists/...} rule decides, at
 * once and never half-written - and the shared scheduler, whose tasks survive throwing and end with the plugin.
 */
public class ListsAndSchedulerTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private String previousHome;
	private File conf;

	/** A manage plugin that keeps its context; with a periodic task that throws every other run. */
	public static class Banner implements FrontcachePlugin {
		static volatile PluginContext context;
		static final AtomicInteger runs = new AtomicInteger();
		static volatile CountDownLatch ranTwice = new CountDownLatch(4);

		@Override
		public void start(PluginContext c) {
			context = c;
			c.scheduler().scheduleWithFixedDelay(() -> {
				ranTwice.countDown();
				if (0 == runs.incrementAndGet() % 2)
					throw new IllegalStateException("every other run");
			}, 0, 10, TimeUnit.MILLISECONDS);
		}
	}

	/** Observes only - may not write a list. */
	public static class Reader implements FrontcachePlugin {
		static volatile PluginContext context;

		@Override
		public void start(PluginContext c) {
			context = c;
		}
	}

	@Before
	public void setUp() throws IOException {
		FCConfig.getProperty("front-cache.id");
		File home = folder.newFolder("home");
		conf = new File(home, "conf");
		conf.mkdirs();
		previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, home.getAbsolutePath());
		FCConfig.setProperty("front-cache.guard-rules.enabled", "true");
		org.frontcache.guard.IpListsAccess.clear();
		Banner.runs.set(0);
		Banner.ranTwice = new CountDownLatch(4);
	}

	@After
	public void tearDown() {
		FrontcachePlugins.install(List.of());
		org.frontcache.guard.IpListsAccess.clear();
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);
	}

	private void installPlugins(String... idClassKinds) throws IOException {
		URL[] urls = new URL[idClassKinds.length / 3];
		for (int i = 0; i < idClassKinds.length; i += 3) {
			File root = folder.newFolder("plugin-" + idClassKinds[i]);
			File file = new File(root, PluginManifest.RESOURCE);
			file.getParentFile().mkdirs();
			Files.writeString(file.toPath(), "id = " + idClassKinds[i] + "\nclass = " + idClassKinds[i + 1]
					+ "\nversion = 1.0.0\nplugin-api = 1\nkinds = " + idClassKinds[i + 2] + "\n", StandardCharsets.UTF_8);
			urls[i / 3] = root.toURI().toURL();
		}
		FrontcachePlugins.install(FrontcachePlugins.load(new URLClassLoader(urls, getClass().getClassLoader())));
		FrontcachePlugins.start(null, List::of);
	}

	private void rules(String... lines) throws IOException {
		Files.writeString(new File(conf, "guard-rules.conf").toPath(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
	}

	private static boolean rejected(GuardRuleEngine engine, String address) {
		return engine.evaluate(new ServletFakes.Request().uri("/").from(address).context()).isTerminal();
	}

	@Test
	public void aWrittenListReachesTheRuleAtOnceWithoutAReload() throws IOException {
		rules("banned | client-ip:@lists/banned.txt | reject:403");
		installPlugins("banner", Banner.class.getName(), "manage");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		assertEquals("the list does not exist yet: the rule loads, matching nobody", 1,
				engine.getRules().stream().filter(r -> "banned".equals(r.getName())).count());
		assertFalse(rejected(engine, "198.51.100.9"));

		assertEquals(2, Banner.context.writeList("banned", List.of("# from the honeypot", "198.51.100.9", "", "2001:db8::/32")));
		assertTrue(rejected(engine, "198.51.100.9"));
		assertFalse(rejected(engine, "198.51.100.10"));

		String file = Files.readString(new File(conf, "lists/banned.txt").toPath());
		assertTrue(file, file.startsWith("# managed by plugin banner\n# from the honeypot\n198.51.100.9\n"));

		assertEquals("an empty list is a list", 0, Banner.context.writeList("banned", List.of()));
		assertFalse(rejected(engine, "198.51.100.9"));

		// a reload of the rules reads the same list
		Banner.context.writeList("banned", List.of("198.51.100.0/24"));
		engine.reload();
		assertTrue(rejected(engine, "198.51.100.77"));
	}

	@Test
	public void aListIsWrittenWholeOrRefusedWhole() throws IOException {
		rules("banned | client-ip:@lists/banned.txt | reject:403");
		installPlugins("banner", Banner.class.getName(), "manage");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		Banner.context.writeList("banned", List.of("198.51.100.9"));

		try {
			Banner.context.writeList("banned", List.of("203.0.113.1", "not-an-address", "300.1.1.1/8"));
			fail("refused");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith("2 entries are not an address")
					&& e.getMessage().contains("'not-an-address'"));
		}
		assertTrue("the list keeps what it had", rejected(engine, "198.51.100.9"));
		assertFalse(rejected(engine, "203.0.113.1"));

		for (String name : new String[] { "Banned", "../x", "a/b", "", ".hidden", "a".repeat(65) })
			try {
				Banner.context.writeList(name, List.of());
				fail("not a name: " + name);
			} catch (IllegalArgumentException expected) {
			}
		try {
			Banner.context.writeList("x", List.of("198.51.100.9\n10.0.0.0/8"));
			fail("one entry, one line");
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void aListThePluginDoesNotOwnIsNeverOverwritten() throws IOException {
		installPlugins("banner", Banner.class.getName(), "manage", "reader", Reader.class.getName(), "observe");
		new File(conf, "lists").mkdirs();
		Files.writeString(new File(conf, "lists/operator.txt").toPath(), "10.0.0.0/8\n");
		Files.writeString(new File(conf, "lists/other.txt").toPath(), "# managed by plugin feeder\n10.0.0.0/8\n");

		for (String name : new String[] { "operator", "other" })
			try {
				Banner.context.writeList(name, List.of("198.51.100.9"));
				fail("not banner's: " + name);
			} catch (IOException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("never overwritten"));
			}
		assertEquals("10.0.0.0/8\n", Files.readString(new File(conf, "lists/operator.txt").toPath()));

		try {
			Reader.context.writeList("mine", List.of());
			fail("only a manage plugin writes a list");
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("'manage'"));
		}
	}

	@Test
	public void aThrowingTaskKeepsItsScheduleAndTheTasksEndWithThePlugin() throws Exception {
		installPlugins("banner", Banner.class.getName(), "manage");
		assertTrue("runs again after throwing", Banner.ranTwice.await(10, TimeUnit.SECONDS));
		assertTrue(FrontcachePlugins.stats("banner").getErrors() >= 1);

		ScheduledExecutorService scheduler = Banner.context.scheduler();
		FrontcachePlugins.stop(false);
		assertTrue(scheduler.isShutdown());
		int after = Banner.runs.get();
		Thread.sleep(100);
		assertTrue("cancelled with the plugin", Banner.runs.get() <= after + 1);
		try {
			scheduler.schedule(() -> { }, 0, TimeUnit.SECONDS);
			fail("a stopped plugin's scheduler takes no task");
		} catch (java.util.concurrent.RejectedExecutionException expected) {
		}

		// the next start gets a scheduler of its own
		FrontcachePlugins.start(null, List::of);
		assertFalse(Banner.context.scheduler().isShutdown());
	}
}
