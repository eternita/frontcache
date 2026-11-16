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
package org.frontcache.plugin.testkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.plugin.PluginManifest;
import org.junit.After;
import org.junit.Test;

public class TestPluginContextTest {

	private static final PluginManifest MANAGE = PluginHarness.manifest(
			"id = feeder\nclass = x.Feeder\nversion = 1\nplugin-api = 1\nkinds = manage\n");
	private static final PluginManifest OBSERVE = PluginHarness.manifest(
			"id = watcher\nclass = x.Watcher\nversion = 1\nplugin-api = 1\nkinds = observe\n");

	private final List<TestPluginContext> contexts = new ArrayList<>();

	private TestPluginContext context(PluginManifest manifest) {
		TestPluginContext c = new TestPluginContext(manifest);
		contexts.add(c);
		return c;
	}

	@After
	public void delete() {
		for (TestPluginContext c : contexts)
			c.delete();
	}

	@Test
	public void theHomeHasConfAndThePluginsDataDirectory() {
		TestPluginContext c = context(MANAGE);
		assertTrue(new File(c.frontcacheHome(), "conf").isDirectory());
		assertEquals(new File(c.frontcacheHome(), "plugins/feeder/data"), c.dataDirectory());
		assertTrue(c.dataDirectory().isDirectory());
		File home = c.frontcacheHome();
		c.delete();
		assertTrue("a home the context made is removed", !home.exists());
	}

	@Test
	public void theClientKeyHashIsStableKeyedAndNotTheKey() {
		TestPluginContext a = context(MANAGE);
		TestPluginContext b = context(MANAGE);
		String h = a.hashClientKey("203.0.113.7");
		assertEquals(32, h.length());
		assertEquals(h, a.hashClientKey("203.0.113.7"));
		assertNotEquals(h, a.hashClientKey("203.0.113.8"));
		assertNotEquals("another node, another key", h, b.hashClientKey("203.0.113.7"));
		assertNull(a.hashClientKey(null));
	}

	@Test
	public void writeListWritesTheMarkerAndCountsTheBlocks() throws IOException {
		TestPluginContext c = context(MANAGE);
		int blocks = c.writeList("googlebot", List.of("# from the vendor", "66.249.64.0/19", "", "2001:4860:4801::/48",
				"  203.0.113.7  ", "::ffff:192.0.2.1"));
		assertEquals(4, blocks);
		List<String> lines = c.readList("googlebot");
		assertEquals("# managed by plugin feeder", lines.get(0));
		assertEquals("# from the vendor", lines.get(1));
		assertEquals("  203.0.113.7  ", lines.get(5));

		assertEquals(1, c.writeList("googlebot", List.of("192.0.2.0/24")));
		assertEquals(List.of("# managed by plugin feeder", "192.0.2.0/24"), c.readList("googlebot"));
		assertEquals("no temporary file is left", 1, c.listFile("googlebot").getParentFile().list().length);
	}

	@Test
	public void aListWithOneBadEntryIsRefusedWholeNamingIt() throws IOException {
		TestPluginContext c = context(MANAGE);
		c.writeList("bots", List.of("192.0.2.0/24"));
		for (String bad : new String[] { "googlebot.com", "10.0.0.0/33", "2001:db8::/129", "10.0.0", "1", "[::1]",
				"10.0.0.1:80", "fe80::1%eth0", "10.0.0.0/8 # x", "10.0.0.0/-1", "256.1.1.1" })
		{
			try {
				c.writeList("bots", List.of("203.0.113.0/24", bad));
				fail("accepted " + bad);
			} catch (IllegalArgumentException e) {
				assertTrue(e.getMessage(), e.getMessage().contains(bad));
			}
			assertEquals("kept", List.of("# managed by plugin feeder", "192.0.2.0/24"), c.readList("bots"));
		}
		try {
			c.writeList("bots", List.of("10.0.0.0/8\n# injected"));
			fail();
		} catch (IllegalArgumentException expected) {
		}
		try {
			c.writeList("bots", Collections.singletonList(null));
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void aNameIsChecked() throws IOException {
		TestPluginContext c = context(MANAGE);
		for (String bad : new String[] { "", "Googlebot", "../x", "a/b", ".hidden", "-x", "x".repeat(65), null })
		{
			try {
				c.writeList(bad, List.of("192.0.2.0/24"));
				fail("accepted name " + bad);
			} catch (IllegalArgumentException expected) {
			}
		}
		c.writeList("a.b_c-1", List.of("192.0.2.0/24"));
		c.writeList("x".repeat(64), List.of("192.0.2.0/24"));
	}

	@Test
	public void atMostAHundredThousandLines() throws IOException {
		TestPluginContext c = context(MANAGE);
		List<String> lines = new ArrayList<>(Collections.nCopies(100_000, "192.0.2.1"));
		assertEquals(100_000, c.writeList("big", lines));
		lines.add("192.0.2.2");
		try {
			c.writeList("big", lines);
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void anOperatorsListAndAnotherPluginsAreNeverOverwritten() throws IOException {
		TestPluginContext c = context(MANAGE);
		c.writeConfFile("lists/mine.txt", "# hand written\n10.0.0.0/8\n");
		c.writeConfFile("lists/theirs.txt", "# managed by plugin other\n10.0.0.0/8\n");
		for (String name : new String[] { "mine", "theirs" })
		{
			try {
				c.writeList(name, List.of("192.0.2.0/24"));
				fail("overwrote " + name);
			} catch (IOException e) {
				assertTrue(c.readList(name).contains("10.0.0.0/8"));
			}
		}
	}

	@Test
	public void writeListIsAManagePluginsOnly() throws IOException {
		try {
			context(OBSERVE).writeList("x", List.of("192.0.2.0/24"));
			fail();
		} catch (UnsupportedOperationException expected) {
		}
	}

	@Test
	public void aThrowingPeriodicTaskIsLoggedAndKeepsItsSchedule() throws Exception {
		TestPluginContext c = context(MANAGE);
		PluginScheduler s = c.newScheduler();
		AtomicInteger runs = new AtomicInteger();
		s.scheduleAtFixedRate(() -> {
			runs.incrementAndGet();
			throw new IllegalStateException("boom");
		}, 0, 5, TimeUnit.MILLISECONDS);
		long deadline = System.currentTimeMillis() + 5_000;
		while (runs.get() < 3 && System.currentTimeMillis() < deadline)
			Thread.sleep(5);
		assertTrue("it kept running after throwing: " + runs.get(), runs.get() >= 3);
		assertTrue(s.taskErrors() >= 3);
		assertEquals(1, s.activeTasks());

		c.cancelScheduledTasks();
		assertEquals(0, s.activeTasks());
		try {
			s.schedule(() -> { }, 1, TimeUnit.SECONDS);
			fail("a stopped start's scheduler takes new tasks");
		} catch (RejectedExecutionException expected) {
		}
		PluginScheduler next = c.newScheduler();
		next.schedule(() -> { }, 0, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
		next.shutdown();
		assertTrue(next.isShutdown());
	}
}
