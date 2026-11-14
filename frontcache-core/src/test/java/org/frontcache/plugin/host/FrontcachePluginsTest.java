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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.io.ActionResponse;
import org.frontcache.io.PluginStats;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.ManagementRequest;
import org.frontcache.plugin.NodeMode;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.event.ConfigReloaded;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.PluginStateChanged;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Finding plugins by their manifests, and "a plugin that fails is a plugin that is absent": every way a plugin can
 * be wrong leaves the node starting, with the plugin reported and not run.
 */
public class FrontcachePluginsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@After
	public void tearDown() {
		FrontcachePlugins.install(List.of());
	}

	// ---- plugins the manifests below name ----------------------------------------------------------------------

	public static class Recording implements FrontcachePlugin {
		static final AtomicInteger starts = new AtomicInteger();
		static final AtomicInteger stops = new AtomicInteger();
		static volatile PluginContext lastContext;
		static volatile NodeMode lastMode;

		@Override
		public void start(PluginContext context) {
			starts.incrementAndGet();
			lastContext = context;
		}

		@Override
		public void stop(boolean shutdown) {
			stops.incrementAndGet();
		}

		@Override
		public void onModeKnown(NodeMode mode) {
			lastMode = mode;
		}

		@Override
		public Map<String, ManagementAction> managementActions() {
			return Map.of("recording-hello", action("says hello"), "shared-name", action("recording's"));
		}
	}

	public static class Throwing implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
			throw new IllegalStateException("broken on purpose");
		}
	}

	/** Hands over a listener without declaring observe. */
	public static class UndeclaredObserver implements FrontcachePlugin {
		static final AtomicInteger stops = new AtomicInteger();

		@Override
		public void start(PluginContext context) {
		}

		@Override
		public void stop(boolean shutdown) {
			stops.incrementAndGet();
		}

		@Override
		public List<EventListener> eventListeners() {
			return List.of(FcEventBusTest.listener(Set.of(ConfigReloaded.class), b -> { }));
		}
	}

	public static class Observer implements FrontcachePlugin {
		static final List<FcEvent> received = Collections.synchronizedList(new ArrayList<>());
		static volatile CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<EventListener> eventListeners() {
			return List.of(FcEventBusTest.listener(Set.of(ConfigReloaded.class, PluginStateChanged.class), batch -> {
				received.addAll(batch);
				for (FcEvent e : batch)
					if (e instanceof ConfigReloaded)
						latch.countDown();
			}));
		}

		@Override
		public Map<String, ManagementAction> managementActions() {
			return Map.of("shared-name", action("observer's"));
		}
	}

	public static class NotAPlugin {
	}

	static ManagementAction action(String description) {
		return new ManagementAction() {
			@Override
			public String description() {
				return description;
			}

			@Override
			public ActionResponse handle(ManagementRequest request) {
				ActionResponse response = new ActionResponse();
				response.setAction(request.action());
				response.setResponseStatus(ActionResponse.RESPONSE_STATUS_OK);
				return response;
			}
		};
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

	private File plugin(String dir, String manifest) throws IOException {
		File root = folder.newFolder(dir);
		File file = new File(root, PluginManifest.RESOURCE);
		file.getParentFile().mkdirs();
		Files.writeString(file.toPath(), manifest, StandardCharsets.UTF_8);
		return root;
	}

	private static String manifest(String id, Class<?> type, String kinds) {
		return "id = " + id + "\nclass = " + type.getName() + "\nversion = 1.0.0\nplugin-api = 1\nkinds = " + kinds
				+ "\nlicense = Apache-2.0\n";
	}

	private void install(File... roots) throws IOException {
		URL[] urls = new URL[roots.length];
		for (int i = 0; i < roots.length; i++)
			urls[i] = roots[i].toURI().toURL();
		URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
		FrontcachePlugins.install(FrontcachePlugins.load(loader));
	}

	private static PluginStats stats(String id) {
		PluginStats stats = FrontcachePlugins.stats(id);
		assertNotNull("no plugin " + id + " in " + FrontcachePlugins.loadedIds(), stats);
		return stats;
	}

	// ---- tests -------------------------------------------------------------------------------------------------

	@Test
	public void aPluginIsFoundByItsManifestAndStarted() throws IOException {
		Recording.starts.set(0);
		install(plugin("rec", manifest("recording", Recording.class, "manage")));

		FrontcachePlugins.start("www.example.com", List::of);

		assertEquals(1, Recording.starts.get());
		assertEquals(List.of("recording"), FrontcachePlugins.ids());
		PluginStats stats = stats("recording");
		assertEquals("started", stats.getState());
		assertEquals(List.of("manage"), stats.getKinds());
		assertEquals("Apache-2.0", stats.getLicense());
		assertEquals("www.example.com", Recording.lastContext.publicDomain());
		assertEquals("recording", Recording.lastContext.manifest().id());
		assertTrue("a bundled plugin gets the core's richer context",
				Recording.lastContext instanceof BundledPluginContext);
		assertNotNull(FrontcachePlugins.managementAction("recording-hello"));
		assertEquals("says hello", FrontcachePlugins.managementActionDescriptions().get("recording-hello"));
	}

	@Test
	public void reloadStopsAndStartsTheSameInstance() throws IOException {
		Recording.starts.set(0);
		Recording.stops.set(0);
		install(plugin("rec", manifest("recording", Recording.class, "manage")));

		FrontcachePlugins.start(null, List::of);
		PluginContext first = Recording.lastContext;
		FrontcachePlugins.stop(false);
		assertEquals("stopped", stats("recording").getState());
		assertNull("a stopped plugin answers no action", FrontcachePlugins.managementAction("recording-hello"));
		FrontcachePlugins.start(null, List::of);

		assertEquals(2, Recording.starts.get());
		assertEquals(1, Recording.stops.get());
		assertTrue("each start gets its own context", first != Recording.lastContext);
		assertEquals("started", stats("recording").getState());
	}

	@Test
	public void aPluginThatThrowsInStartIsDroppedAndTheOthersStart() throws IOException {
		Recording.starts.set(0);
		install(plugin("bad", manifest("throwing", Throwing.class, "manage")),
				plugin("rec", manifest("recording", Recording.class, "manage")));

		FrontcachePlugins.start(null, List::of);

		assertEquals(List.of("recording"), FrontcachePlugins.ids());
		PluginStats bad = stats("throwing");
		assertEquals("failed", bad.getState());
		assertTrue(bad.getReason(), bad.getReason().contains("broken on purpose"));
		assertEquals(1, bad.getErrors());
	}

	@Test
	public void aContributionOfAnUndeclaredKindIsRefusedAndThePluginStopped() throws IOException {
		UndeclaredObserver.stops.set(0);
		install(plugin("sneaky", manifest("sneaky", UndeclaredObserver.class, "manage")));

		FrontcachePlugins.start(null, List::of);

		PluginStats stats = stats("sneaky");
		assertEquals("refused", stats.getState());
		assertTrue(stats.getReason(), stats.getReason().contains("'observe'"));
		assertEquals(1, UndeclaredObserver.stops.get());
		assertTrue(FrontcachePlugins.ids().isEmpty());
		for (long bit : FcEventBus.BITS.values())
			assertTrue("its listener was never subscribed", !FcEventBus.wants(bit));
	}

	@Test
	public void manifestsThatCannotBeHonouredAreRefusedAtLoadAndTheNodeStarts() throws IOException {
		install(plugin("serve", manifest("server", Recording.class, "decide")),
				plugin("api2", manifest("future", Recording.class, "manage").replace("plugin-api = 1", "plugin-api = 2")),
				plugin("dup", manifest("server", Recording.class, "manage")),
				plugin("notaplugin", manifest("pojo", NotAPlugin.class, "manage")),
				plugin("missing", manifest("ghost", Recording.class, "manage").replace(Recording.class.getName(),
						"com.example.DoesNotExist")),
				plugin("broken", "id = broken\nkinds = teleport\n"));

		FrontcachePlugins.start(null, List::of);

		assertTrue(FrontcachePlugins.ids().isEmpty());
		assertTrue(stats("server").getReason().contains("does not offer"));
		assertTrue(stats("future").getReason().contains("plugin-api 2"));
		assertTrue(stats("pojo").getReason().contains("does not implement"));
		assertTrue(stats("ghost").getReason().contains("cannot instantiate"));
		assertTrue(stats("broken").getReason().contains("unknown kind 'teleport'"));
		long duplicates = FrontcachePlugins.stats().stream()
				.filter(s -> null != s.getReason() && s.getReason().contains("already has the id 'server'")).count();
		assertEquals(1, duplicates);
		for (PluginStats s : FrontcachePlugins.stats())
			assertEquals(s.getId(), "refused", s.getState());
	}

	@Test
	public void theFirstPluginToNameAnActionKeepsIt() throws IOException {
		install(plugin("rec", manifest("recording", Recording.class, "manage")),
				plugin("obs", manifest("observer", Observer.class, "observe, manage")));

		FrontcachePlugins.start(null, List::of);

		assertEquals("recording's", FrontcachePlugins.managementActionDescriptions().get("shared-name"));
	}

	@Test
	public void anObservingPluginReceivesEventsOnItsOwnThread() throws Exception {
		Observer.received.clear();
		Observer.latch = new CountDownLatch(1);
		install(plugin("obs", manifest("observer", Observer.class, "observe, manage")));

		FrontcachePlugins.start(null, List::of);
		assertTrue(FcEventBus.wants(FcEventBus.CONFIG_RELOADED));
		CoreEvents.configReloaded("guard-rules.conf", true, 3, List.of());

		assertTrue(Observer.latch.await(5, TimeUnit.SECONDS));
		FcEventBusTest.waitFor(() -> 1 <= stats("observer").getEventsDelivered());
		assertTrue("it saw its own start", Observer.received.stream().anyMatch(e -> e instanceof PluginStateChanged p
				&& "observer".equals(p.plugin()) && "started".equals(p.state())));

		FrontcachePlugins.stop(false);
		assertTrue("nothing is wanted once the plugins stop", !FcEventBus.wants(FcEventBus.CONFIG_RELOADED));
	}

	@Test
	public void theModeReachesLoadedPluginsBeforeAnyStart() throws IOException {
		Recording.lastMode = null;
		install(plugin("rec", manifest("recording", Recording.class, "manage")));

		FrontcachePlugins.modeKnown(NodeMode.FILTER);

		assertEquals(NodeMode.FILTER, Recording.lastMode);
	}

	@Test
	public void clientKeysHashStablyAndIrreversibly() {
		String a = CorePluginContext.hash("203.0.113.7");
		assertEquals(a, CorePluginContext.hash("203.0.113.7"));
		assertTrue(!a.equals(CorePluginContext.hash("203.0.113.8")));
		assertEquals(32, a.length());
		assertTrue(!a.contains("203"));
		assertNull(CorePluginContext.hash(null));
	}
}
