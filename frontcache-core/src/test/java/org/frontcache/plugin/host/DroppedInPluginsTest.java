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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.frontcache.FCConfig;
import org.frontcache.io.PluginStats;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.ManagementRequest;
import org.frontcache.plugin.NodeMode;
import org.frontcache.plugin.PluginManifest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Dropped-in plugins: {@code <plugin directory>/<id>/*.jar}, enabled by {@code front-cache.plugins}, optionally pinned,
 * each in a class loader that shows it the plugin API and nothing of the engine or of another plugin. The jars are
 * compiled here, so what is tested is a real jar in a real directory.
 */
public class DroppedInPluginsTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private File plugins;

	@Before
	public void setUp() throws IOException {
		FCConfig.getProperty("front-cache.id");
		plugins = folder.newFolder("plugins");
		System.setProperty(PluginDirectory.DIR_PROPERTY, plugins.getAbsolutePath());
	}

	@After
	public void tearDown() {
		FrontcachePlugins.install(List.of());
		System.clearProperty(PluginDirectory.DIR_PROPERTY);
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "");
		for (String id : List.of("alpha", "beta", "gamma"))
			FCConfig.setProperty(String.format(PluginDirectory.PIN_PROPERTY, id), "");
	}

	// ---- jars --------------------------------------------------------------------------------------------------

	/**
	 * A plugin {@code com.example.<id>.Plugin} answering {@code <id>-probe}: its library's version, its context's class,
	 * and whether a class named by the {@code class} parameter is visible to it. Each carries its own
	 * {@code com.example.lib.Version}.
	 */
	private File jar(String id, String manifestId, String libraryVersion) throws IOException {
		File src = folder.newFolder("src-" + id + "-" + System.nanoTime());
		write(src, "com/example/" + id + "/Plugin.java", "package com.example." + id + ";\n"
				+ "import java.util.Map;\n"
				+ "import org.frontcache.io.ActionResponse;\n"
				+ "import org.frontcache.plugin.*;\n"
				+ "public class Plugin implements FrontcachePlugin {\n"
				+ "  private volatile PluginContext context;\n"
				+ "  public void start(PluginContext context) { this.context = context; }\n"
				+ "  public Map<String, ManagementAction> managementActions() {\n"
				+ "    return Map.of(\"" + id + "-probe\", new ManagementAction() {\n"
				+ "      public String description() { return \"probe\"; }\n"
				+ "      public ActionResponse handle(ManagementRequest request) {\n"
				+ "        String name = request.parameter(\"class\");\n"
				+ "        String seen = \"-\";\n"
				+ "        if (null != name) {\n"
				+ "          try { Class.forName(name, true, Plugin.class.getClassLoader()); seen = \"visible\"; }\n"
				+ "          catch (Throwable t) { seen = \"invisible\"; }\n"
				+ "        }\n"
				+ "        ActionResponse response = new ActionResponse();\n"
				+ "        response.setAction(com.example.lib.Version.value() + \"|\" + context.getClass().getName() + \"|\" + seen\n"
				+ "            + \"|\" + (null != context.scheduler()));\n"
				+ "        return response;\n"
				+ "      }\n"
				+ "    });\n"
				+ "  }\n"
				+ "}\n");
		write(src, "com/example/lib/Version.java", "package com.example.lib;\n"
				+ "public class Version { public static String value() { return \"" + libraryVersion + "\"; } }\n");

		File classes = new File(src, "classes");
		classes.mkdirs();
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull("a JDK's compiler", javac);
		int status = javac.run(null, null, null, "-d", classes.getAbsolutePath(), "-classpath",
				System.getProperty("java.class.path"), new File(src, "com/example/" + id + "/Plugin.java").getAbsolutePath(),
				new File(src, "com/example/lib/Version.java").getAbsolutePath());
		assertEquals(0, status);

		File dir = new File(plugins, id);
		dir.mkdirs();
		File jar = new File(dir, id + "-1.0.0.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
			out.putNextEntry(new JarEntry(PluginManifest.RESOURCE));
			out.write(("id = " + manifestId + "\nclass = com.example." + id + ".Plugin\nversion = 1.0.0\nplugin-api = 1\n"
					+ "kinds = manage\nlicense = Apache-2.0\n").getBytes(StandardCharsets.UTF_8));
			for (String path : List.of("com/example/" + id + "/Plugin.class", "com/example/" + id + "/Plugin$1.class",
					"com/example/lib/Version.class")) {
				out.putNextEntry(new JarEntry(path));
				out.write(Files.readAllBytes(new File(classes, path).toPath()));
			}
		}
		return jar;
	}

	private static void write(File root, String path, String content) throws IOException {
		File file = new File(root, path);
		file.getParentFile().mkdirs();
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
	}

	private static void load() {
		FrontcachePlugins.install(FrontcachePlugins.loadAll(FrontcachePlugin.class.getClassLoader()));
	}

	private static String probe(String id, String className) throws IOException {
		ManagementAction action = FrontcachePlugins.managementAction(id + "-probe");
		assertNotNull("plugin '" + id + "' answers its probe", action);
		return action.handle(new ManagementRequest() {
			@Override
			public String action() {
				return id + "-probe";
			}

			@Override
			public String parameter(String name) {
				return "class".equals(name) ? className : null;
			}

			@Override
			public InputStream body() {
				return InputStream.nullInputStream();
			}

			@Override
			public boolean isSecure() {
				return false;
			}

			@Override
			public int localPort() {
				return 0;
			}

			@Override
			public boolean isAuthorizedToWrite() {
				return false;
			}

			@Override
			public OutputStream openRawOutput(String contentType, String fileName) {
				throw new UnsupportedOperationException();
			}
		}).getAction();
	}

	private static PluginStats stats(String id) {
		PluginStats stats = FrontcachePlugins.stats(id);
		assertNotNull("no plugin " + id + " in " + FrontcachePlugins.loadedIds(), stats);
		return stats;
	}

	// ---- tests -------------------------------------------------------------------------------------------------

	@Test
	public void anEnabledPluginIsLoadedFromItsDirectoryAndSeesOnlyTheApi() throws IOException {
		jar("alpha", "alpha", "1");
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "alpha");
		load();
		FrontcachePlugins.start(null, List::of);

		PluginStats stats = stats("alpha");
		assertEquals("started", stats.getState());
		assertEquals(PluginStats.SOURCE_DROPPED_IN, stats.getSource());
		assertEquals("plugins/alpha", stats.getDirectory());
		assertEquals(1, stats.getJars().size());
		assertTrue(stats.getJars().get(0).matches("alpha-1\\.0\\.0\\.jar [0-9a-f]{64}"));
		assertFalse(stats.isPinned());

		// its context is the API's - never the core's BundledPluginContext; it has a scheduler
		String report = probe("alpha", null);
		assertEquals("1|" + DroppedInPluginContext.class.getName() + "|-|true", report);

		// the API and its libraries, and the JDK: visible
		for (String visible : List.of("org.frontcache.plugin.PluginContext", "org.frontcache.plugin.decide.RequestView",
				"org.frontcache.io.ActionResponse", "com.typesafe.config.Config", "org.slf4j.LoggerFactory",
				"com.fasterxml.jackson.annotation.JsonIgnoreProperties", "java.net.http.HttpClient",
				"com.sun.net.httpserver.HttpServer", "java.sql.Connection"))
			assertTrue(visible, probe("alpha", visible).endsWith("|visible|true"));
		// the engine, the core's side of the plugin packages, a library the API does not share: not
		for (String invisible : List.of("org.frontcache.FrontCacheEngine", "org.frontcache.core.RequestContext",
				"org.frontcache.plugin.host.FrontcachePlugins", "org.frontcache.plugin.host.BundledPluginContext",
				"org.frontcache.io.PluginStats", "com.fasterxml.jackson.databind.ObjectMapper",
				"org.frontcache.plugin.host.DroppedInPluginsTest"))
			assertTrue(invisible, probe("alpha", invisible).endsWith("|invisible|true"));
	}

	@Test
	public void twoPluginsCarryTwoVersionsOfOneLibraryAndNeitherSeesTheOther() throws IOException {
		jar("alpha", "alpha", "1.0");
		jar("beta", "beta", "2.0");
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "alpha, beta");
		load();
		FrontcachePlugins.start(null, List::of);

		assertTrue(probe("alpha", null).startsWith("1.0|"));
		assertTrue(probe("beta", null).startsWith("2.0|"));
		assertTrue(probe("alpha", "com.example.beta.Plugin").endsWith("|invisible|true"));
		assertTrue(probe("beta", "com.example.alpha.Plugin").endsWith("|invisible|true"));
	}

	@Test
	public void aDirectoryNotListedIsReportedAndNotRead() throws IOException {
		jar("alpha", "alpha", "1");
		new File(plugins, "alerts/data").mkdirs(); // a bundled plugin's data directory: no jar, not a plugin's
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "");
		load();
		FrontcachePlugins.start(null, List::of);

		PluginStats stats = stats("alpha");
		assertEquals(PluginStats.STATE_NOT_ENABLED, stats.getState());
		assertEquals(PluginStats.SOURCE_DROPPED_IN, stats.getSource());
		assertNull(stats.getVersion());
		assertNull(FrontcachePlugins.managementAction("alpha-probe"));
		assertNull(FrontcachePlugins.stats("alerts"));
	}

	@Test
	public void aListedPluginWithoutADirectoryOrAJarIsRefused() throws IOException {
		new File(plugins, "beta").mkdirs();
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "alpha, beta, Not_An_Id");
		load();
		FrontcachePlugins.start(null, List::of);

		assertEquals("refused", stats("alpha").getState());
		assertTrue(stats("alpha").getReason(), stats("alpha").getReason().contains("no directory plugins/alpha"));
		assertEquals("refused", stats("beta").getState());
		assertTrue(stats("beta").getReason(), stats("beta").getReason().contains("holds no jar"));
		assertTrue(FrontcachePlugins.ids().isEmpty());
	}

	@Test
	public void aPinnedPluginLoadsOnlyWithTheJarsItWasPinnedTo() throws IOException {
		File alpha = jar("alpha", "alpha", "1");
		jar("beta", "beta", "1");
		jar("gamma", "gamma", "1");
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "alpha, beta, gamma");
		FCConfig.setProperty(String.format(PluginDirectory.PIN_PROPERTY, "alpha"),
				PluginDirectory.sha256(alpha).toUpperCase());
		FCConfig.setProperty(String.format(PluginDirectory.PIN_PROPERTY, "beta"), "0".repeat(64));
		FCConfig.setProperty(String.format(PluginDirectory.PIN_PROPERTY, "gamma"), "not-a-digest");
		load();
		FrontcachePlugins.start(null, List::of);

		assertEquals("started", stats("alpha").getState());
		assertTrue(stats("alpha").isPinned());
		assertEquals("refused", stats("beta").getState());
		assertTrue(stats("beta").getReason(), stats("beta").getReason().contains("differ from front-cache.plugins.beta.sha256")
				&& stats("beta").getReason().contains("beta-1.0.0.jar is "));
		assertEquals("refused", stats("gamma").getState());
		assertTrue(stats("gamma").getReason(), stats("gamma").getReason().contains("not a SHA-256"));
	}

	@Test
	public void aManifestThatDoesNotNameItsDirectoryIsRefused() throws IOException {
		jar("alpha", "beta", "1");
		FCConfig.setProperty(PluginDirectory.ENABLED_PROPERTY, "alpha");
		load();
		FrontcachePlugins.start(null, List::of);

		assertEquals("refused", stats("alpha").getState());
		assertTrue(stats("alpha").getReason(), stats("alpha").getReason().contains("must be named by it"));
	}

	@Test
	public void filterModeReadsADirectoryOnlyWhenOneIsNamed() {
		assertEquals(plugins, PluginDirectory.directory(NodeMode.FILTER));
		System.clearProperty(PluginDirectory.DIR_PROPERTY);
		if (null == System.getenv(PluginDirectory.DIR_ENV)) {
			assertNull(PluginDirectory.directory(NodeMode.FILTER));
			File home = new File(System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY));
			assertEquals(new File(home, "plugins"), PluginDirectory.directory(NodeMode.STANDALONE));
			assertEquals("unknown is a server: a filter announces itself when its application starts",
					new File(home, "plugins"), PluginDirectory.directory(null));
		}
	}

	@Test
	public void classVisibilityIsTheApiAndItsLibraries() {
		List<String> visible = new ArrayList<>();
		for (String name : List.of("org.frontcache.plugin.FrontcachePlugin", "org.frontcache.plugin.event.GuardMatched",
				"org.frontcache.plugin.serve.PluginEndpoint", "org.frontcache.plugin.decide.PluginPredicate",
				"org.frontcache.io.ActionResponse", "io.micrometer.core.instrument.MeterRegistry"))
			if (PluginClassLoader.visible(name))
				visible.add(name);
		assertEquals(6, visible.size());
		for (String name : List.of("org.frontcache.plugin.host.PluginEndpoints", "org.frontcache.io.ActionResponseX",
				"org.frontcache.io.GetNodeStatsActionResponse", "org.frontcache.plugin.Evil.Nested.X",
				"org.frontcache.module.FrontcacheModule", "jakarta.servlet.http.HttpServletRequest"))
			assertFalse(name, PluginClassLoader.visible(name));
		assertTrue(Map.of().isEmpty());
	}
}
