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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.frontcache.FCConfig;
import org.frontcache.io.ConfigWriteAction;
import org.frontcache.io.PluginStats;
import org.frontcache.io.SetConfigActionResponse;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.event.CacheInvalidated;
import org.frontcache.plugin.event.CircuitStateChanged;
import org.frontcache.plugin.event.ConfigReloaded;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.typesafe.config.Config;

/**
 * A plugin's {@code conf/<config>}: read whole or refused whole, at every start and on {@code reload-plugin-config};
 * and written through {@code set-config}'s gates, validated by the plugin before anything reaches the disk.
 */
public class PluginConfigTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/** conf/ as the plugin config reader sees it in most tests: file name -> content; absent = no file. */
	private final Map<String, String> conf = new ConcurrentHashMap<>();

	@Before
	public void setUp() {
		PluginConfigs.source = conf::get;
		Alerts.reset();
	}

	@After
	public void tearDown() {
		FrontcachePlugins.install(List.of());
		PluginConfigs.source = PluginConfigs.FCCONFIG;
	}

	/**
	 * A plugin as an author is told to write one: parse into a new value, refuse with every reason, swap only when
	 * all of it is accepted. Its config is {@code alerts { threshold = <n> }}, n positive.
	 */
	public static class Alerts implements FrontcachePlugin {
		static volatile Integer threshold;
		static volatile Config lastConfig;
		static final AtomicInteger starts = new AtomicInteger();
		static final AtomicInteger configures = new AtomicInteger();

		static void reset() {
			threshold = null;
			lastConfig = null;
			starts.set(0);
			configures.set(0);
		}

		static int parse(Config config) throws PluginConfigException {
			List<String> reasons = new ArrayList<>();
			if (!config.hasPath("alerts.threshold"))
				reasons.add("alerts.threshold is missing");
			else if (config.getInt("alerts.threshold") <= 0)
				reasons.add("alerts.threshold must be positive");
			if (config.hasPath("alerts.unknown"))
				reasons.add("alerts.unknown is not a key");
			if (!reasons.isEmpty())
				throw new PluginConfigException(reasons);
			return config.getInt("alerts.threshold");
		}

		@Override
		public void validateConfig(Config config) throws PluginConfigException {
			parse(config);
		}

		@Override
		public void configure(Config config) throws PluginConfigException {
			configures.incrementAndGet();
			lastConfig = config;
			threshold = parse(config);
		}

		@Override
		public void start(PluginContext context) {
			starts.incrementAndGet();
		}
	}

	/** Takes any config, absent included. */
	public static class Lenient implements FrontcachePlugin {
		static volatile Config lastConfig;

		@Override
		public void configure(Config config) {
			lastConfig = config;
		}

		@Override
		public void start(PluginContext context) {
		}
	}

	/**
	 * Observes what its config names - {@code watch = [circuit, invalidated]} - as an alerts plugin observes the
	 * events its rules name. Hands back the same listener while the watched types do not change.
	 */
	public static class Watcher implements FrontcachePlugin {
		static final List<FcEvent> received = java.util.Collections.synchronizedList(new ArrayList<>());
		private volatile Set<Class<? extends FcEvent>> watched = Set.of();
		private volatile EventListener listener;

		@Override
		public void configure(Config config) throws PluginConfigException {
			Set<Class<? extends FcEvent>> types = new java.util.HashSet<>();
			if (config.hasPath("watch"))
				for (String name : config.getStringList("watch"))
					if ("circuit".equals(name))
						types.add(CircuitStateChanged.class);
					else if ("invalidated".equals(name))
						types.add(CacheInvalidated.class);
					else
						throw new PluginConfigException("watch: unknown '" + name + "'");
			if (!types.equals(watched))
			{
				watched = Set.copyOf(types);
				listener = types.isEmpty() ? null : FcEventBusTest.listener(watched, received::addAll);
			}
		}

		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<EventListener> eventListeners() {
			EventListener l = listener;
			return (null == l) ? List.of() : List.of(l);
		}
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

	private File plugin(String dir, String id, Class<?> type, String config) throws IOException {
		return plugin(dir, id, type, config, "manage");
	}

	private File plugin(String dir, String id, Class<?> type, String config, String kinds) throws IOException {
		File root = folder.newFolder(dir);
		File file = new File(root, PluginManifest.RESOURCE);
		file.getParentFile().mkdirs();
		Files.writeString(file.toPath(), "id = " + id + "\nclass = " + type.getName()
				+ "\nversion = 1.0.0\nplugin-api = 1\nkinds = " + kinds + "\n" + ((null == config) ? "" : "config = " + config + "\n"),
				StandardCharsets.UTF_8);
		return root;
	}

	private void install(File... roots) throws IOException {
		URL[] urls = new URL[roots.length];
		for (int i = 0; i < roots.length; i++)
			urls[i] = roots[i].toURI().toURL();
		FrontcachePlugins.install(FrontcachePlugins.load(new URLClassLoader(urls, getClass().getClassLoader())));
	}

	private static PluginStats stats(String id) {
		return FrontcachePlugins.stats(id);
	}

	// ---- at start ----------------------------------------------------------------------------------------------

	@Test
	public void aPluginIsConfiguredBeforeItStartsWithItsResolvedConfig() throws IOException {
		conf.put("alerts.conf", "alerts {\n  threshold = 500\n  webhook = ${?FC_TEST_UNSET_WEBHOOK_URL}\n}\n");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"));

		FrontcachePlugins.start(null, List::of);

		assertEquals(Integer.valueOf(500), Alerts.threshold);
		assertEquals(1, Alerts.starts.get());
		assertFalse("an unset ${?ENV} is resolved away - the plugin can read the config without NotResolved",
				Alerts.lastConfig.hasPath("alerts.webhook"));
		PluginStats stats = stats("alerts");
		assertEquals("started", stats.getState());
		assertEquals("alerts.conf", stats.getConfig());
		assertEquals("ok", stats.getConfigStatus());
		assertTrue(stats.getConfigErrors().isEmpty());
		assertTrue(0 < stats.getConfigLoaded());
	}

	@Test
	public void aRefusedConfigIsAPluginThatDoesNotStartAndTheOthersDo() throws IOException {
		conf.put("alerts.conf", "alerts { threshold = -1, unknown = 1 }");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"), plugin("l", "lenient", Lenient.class, null));

		FrontcachePlugins.start(null, List::of);

		assertEquals(List.of("lenient"), FrontcachePlugins.ids());
		assertEquals("never started", 0, Alerts.starts.get());
		PluginStats stats = stats("alerts");
		assertEquals("failed", stats.getState());
		assertEquals("refused", stats.getConfigStatus());
		assertEquals("every reason, at once",
				List.of("alerts.threshold must be positive", "alerts.unknown is not a key"), stats.getConfigErrors());
		assertTrue(stats.getReason(), stats.getReason().startsWith("conf/alerts.conf is refused: "));
		assertEquals(0, stats.getConfigLoaded());
	}

	@Test
	public void aConfigThatDoesNotParseOrResolveIsRefused() throws IOException {
		conf.put("alerts.conf", "alerts { threshold = ${FC_TEST_REQUIRED_BUT_UNSET} }");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"));

		FrontcachePlugins.start(null, List::of);

		PluginStats stats = stats("alerts");
		assertEquals("failed", stats.getState());
		assertEquals(0, Alerts.configures.get());
		assertTrue(stats.getConfigErrors().toString(), stats.getConfigErrors().get(0).contains("conf/alerts.conf"));
		assertTrue(stats.getConfigErrors().toString(),
				stats.getConfigErrors().get(0).contains("FC_TEST_REQUIRED_BUT_UNSET"));
	}

	@Test
	public void anAbsentFileIsAnEmptyConfig() throws IOException {
		install(plugin("l", "lenient", Lenient.class, "lenient.conf"));

		FrontcachePlugins.start(null, List::of);

		assertTrue(Lenient.lastConfig.isEmpty());
		assertEquals("started", stats("lenient").getState());
		assertEquals("absent", stats("lenient").getConfigStatus());
	}

	@Test
	public void aPluginWithoutAConfigIsNeverConfigured() throws IOException {
		install(plugin("a", "alerts", Alerts.class, null));

		FrontcachePlugins.start(null, List::of);

		assertEquals(0, Alerts.configures.get());
		assertEquals(1, Alerts.starts.get());
		assertNull(stats("alerts").getConfig());
		assertNull(stats("alerts").getConfigStatus());
		assertNull(FrontcachePlugins.configFile("alerts"));
	}

	@Test
	public void aConfigNamingACoreFileOrAnotherPluginsIsRefusedAtLoad() throws IOException {
		install(plugin("g", "guard", Lenient.class, "guard-rules.conf"),
				plugin("a", "alerts", Alerts.class, "alerts.conf"),
				plugin("b", "copycat", Lenient.class, "alerts.conf"));

		FrontcachePlugins.start(null, List::of);

		assertEquals("refused", stats("guard").getState());
		assertTrue(stats("guard").getReason(), stats("guard").getReason().contains("a file the core reads"));
		assertEquals("refused", stats("copycat").getState());
		assertTrue(stats("copycat").getReason().contains("already has the config conf/alerts.conf"));
		assertNull("a refused plugin's config is not writable", FrontcachePlugins.configFile("guard"));
	}

	// ---- reload ------------------------------------------------------------------------------------------------

	@Test
	public void aReloadAppliesANewConfigAndARefusedOneLeavesThePluginOnItsOld() throws Exception {
		conf.put("alerts.conf", "alerts.threshold = 500");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"));
		FrontcachePlugins.start(null, List::of);
		EventCollector events = EventCollector.subscribe(ConfigReloaded.class);

		conf.put("alerts.conf", "alerts.threshold = 50");
		FrontcachePlugins.ConfigReload ok = FrontcachePlugins.reloadConfig("alerts");
		assertTrue(ok.errors().toString(), ok.ok());
		assertEquals("alerts.conf", ok.file());
		assertEquals(Integer.valueOf(50), Alerts.threshold);

		conf.put("alerts.conf", "alerts.threshold = 0");
		FrontcachePlugins.ConfigReload refused = FrontcachePlugins.reloadConfig("alerts");
		assertFalse(refused.ok());
		assertEquals(List.of("alerts.threshold must be positive"), refused.errors());
		assertEquals("still running", "started", refused.state());
		assertEquals("on the config it had", Integer.valueOf(50), Alerts.threshold);
		assertEquals("refused", stats("alerts").getConfigStatus());
		assertEquals(1, Alerts.starts.get());

		List<FcEvent> received = events.await(2);
		ConfigReloaded first = (ConfigReloaded) received.get(0);
		ConfigReloaded second = (ConfigReloaded) received.get(1);
		assertEquals("alerts.conf", first.file());
		assertTrue(first.ok());
		assertEquals(-1, first.entries());
		assertFalse(second.ok());
		assertEquals(List.of("alerts.threshold must be positive"), second.reasons());
	}

	@Test
	public void aReloadReadsTheListenersAgainSoANewConfigObservesWhatItNames() throws Exception {
		Watcher.received.clear();
		conf.put("watcher.conf", "watch = [circuit]");
		install(plugin("w", "watcher", Watcher.class, "watcher.conf", "observe"));
		FrontcachePlugins.start(null, List::of);
		assertTrue(FcEventBus.wants(FcEventBus.CIRCUIT_STATE_CHANGED));
		assertFalse(FcEventBus.wants(FcEventBus.CACHE_INVALIDATED));

		conf.put("watcher.conf", "watch = [invalidated]");
		assertTrue(FrontcachePlugins.reloadConfig("watcher").ok());
		assertTrue("what the new config names is delivered", FcEventBus.wants(FcEventBus.CACHE_INVALIDATED));
		assertFalse("what it no longer names is not built", FcEventBus.wants(FcEventBus.CIRCUIT_STATE_CHANGED));
		CoreEvents.cacheInvalidated(CacheInvalidated.SCOPE_ALL, null);
		FcEventBusTest.waitFor(() -> Watcher.received.stream().anyMatch(e -> e instanceof CacheInvalidated));

		// the same types: the same listener keeps its queue and thread - one dispatcher, not two
		assertTrue(FrontcachePlugins.reloadConfig("watcher").ok());
		assertEquals(1, Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.isAlive() && "fc-plugin-watcher-events".equals(t.getName())).count());
		assertEquals(0, stats("watcher").getEventsDropped());

		conf.put("watcher.conf", "watch = []");
		assertTrue(FrontcachePlugins.reloadConfig("watcher").ok());
		assertFalse("nothing is wanted once it watches nothing", FcEventBus.wants(FcEventBus.CACHE_INVALIDATED));
		FcEventBusTest.waitFor(() -> 0 == Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.isAlive() && "fc-plugin-watcher-events".equals(t.getName())).count());

		conf.put("watcher.conf", "watch = [nonsense]");
		assertFalse("a refused config changes nothing it observes", FrontcachePlugins.reloadConfig("watcher").ok());
		assertFalse(FcEventBus.wants(FcEventBus.CACHE_INVALIDATED));
	}

	@Test
	public void aReloadStartsAPluginThatFailedOnItsConfig() throws IOException {
		conf.put("alerts.conf", "alerts.threshold = 0");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"));
		FrontcachePlugins.start(null, List::of);
		assertEquals("failed", stats("alerts").getState());

		conf.put("alerts.conf", "alerts.threshold = 7");
		FrontcachePlugins.ConfigReload reload = FrontcachePlugins.reloadConfig("alerts");

		assertTrue(reload.errors().toString(), reload.ok());
		assertEquals("started", reload.state());
		assertEquals(List.of("alerts"), FrontcachePlugins.ids());
		assertEquals(1, Alerts.starts.get());
		assertEquals(Integer.valueOf(7), Alerts.threshold);
	}

	@Test
	public void thereIsNothingToReloadForAPluginWithoutAConfigOrWhileTheNodeIsStopped() throws IOException {
		conf.put("alerts.conf", "alerts.threshold = 0");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"), plugin("l", "lenient", Lenient.class, null));

		assertFalse(FrontcachePlugins.reloadConfig("nobody").ok());
		assertEquals(List.of("no plugin 'nobody' on this node"), FrontcachePlugins.reloadConfig("nobody").errors());
		assertEquals(List.of("plugin 'lenient' has no config"), FrontcachePlugins.reloadConfig("lenient").errors());

		FrontcachePlugins.ConfigReload stopped = FrontcachePlugins.reloadConfig("alerts");
		assertFalse("not started yet - nothing runs on a config", stopped.ok());
		assertEquals(0, Alerts.configures.get());
	}

	// ---- validation and set-config ------------------------------------------------------------------------------

	@Test
	public void validationAsksThePluginAndTouchesNothing() throws IOException {
		conf.put("alerts.conf", "alerts.threshold = 500");
		install(plugin("a", "alerts", Alerts.class, "alerts.conf"));
		FrontcachePlugins.start(null, List::of);

		assertEquals(List.of(), FrontcachePlugins.validateConfig("alerts", "alerts.threshold = 3"));
		assertEquals(List.of("alerts.threshold is missing"), FrontcachePlugins.validateConfig("alerts", ""));
		assertTrue(FrontcachePlugins.validateConfig("alerts", "alerts {").get(0).contains("conf/alerts.conf"));
		assertEquals("validation applies nothing", Integer.valueOf(500), Alerts.threshold);
		assertEquals(1, Alerts.configures.get());
	}

	@Test
	public void setConfigWritesAPluginsConfigThroughTheSameGates() throws Exception {
		String previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		File confDir = folder.newFolder("home", "conf");
		Files.writeString(new File(confDir, "frontcache.properties").toPath(), "front-cache.default-domain=test.example.com\n");
		Files.writeString(new File(confDir, "alerts.conf").toPath(), "alerts.threshold = 500\n");
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, confDir.getParent());
		PluginConfigs.source = PluginConfigs.FCCONFIG;
		try {
			install(plugin("a", "alerts", Alerts.class, "alerts.conf"));
			FrontcachePlugins.start(null, List::of);
			assertEquals(Integer.valueOf(500), Alerts.threshold);

			SetConfigActionResponse refused = ConfigWriteAction.perform("plugin:alerts", "alerts.threshold = -5\n", null,
					true, true);
			assertEquals("ERROR", refused.getResponseStatus());
			assertEquals(List.of("alerts.threshold must be positive"), refused.getValidationErrors());
			assertFalse(refused.isWritten());
			assertEquals("alerts.threshold = 500\n", Files.readString(new File(confDir, "alerts.conf").toPath()));

			SetConfigActionResponse written = ConfigWriteAction.perform("plugin:alerts", "alerts.threshold = 25\r\n",
					null, true, true);
			assertEquals(written.getErrorDescription(), "OK", written.getResponseStatus());
			assertEquals("alerts.conf", written.getFile());
			assertTrue(written.isWritten());
			assertTrue(written.isApplied());
			assertEquals("alerts.threshold = 25\n", Files.readString(new File(confDir, "alerts.conf").toPath()));
			assertEquals("applied: the plugin runs on it", Integer.valueOf(25), Alerts.threshold);

			assertEquals("ERROR", ConfigWriteAction.perform("plugin:alerts", "alerts.threshold = 1", null, true, false)
					.getResponseStatus());
			assertTrue("a plugin id is no path", ConfigWriteAction.perform("plugin:../frontcache", "x = 1", null, true,
					true).getErrorDescription().contains("unknown 'file'"));
			assertTrue(ConfigWriteAction.perform("plugin:nobody", "x = 1", null, true, true).getErrorDescription()
					.contains("unknown 'file'"));
		} finally {
			if (null == previousHome)
				System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
			else
				System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);
		}
	}
}
