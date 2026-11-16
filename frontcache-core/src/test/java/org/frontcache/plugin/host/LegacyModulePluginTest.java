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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.frontcache.io.ActionResponse;
import org.frontcache.io.PluginStats;
import org.frontcache.module.FrontcacheModule;
import org.frontcache.module.ManagementAction;
import org.frontcache.module.ManagementRequest;
import org.frontcache.module.ModuleContext;
import org.frontcache.module.NodeMode;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A 2.11-style module - a services file, no manifest - still runs, as a manage plugin, for one release. */
@SuppressWarnings("deprecation")
public class LegacyModulePluginTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@After
	public void tearDown() {
		FrontcachePlugins.install(List.of());
	}

	public static class OldModule implements FrontcacheModule {
		static volatile ModuleContext context;
		static volatile NodeMode mode;

		@Override
		public String id() {
			return "old";
		}

		@Override
		public void start(ModuleContext context) {
			OldModule.context = context;
		}

		@Override
		public void stop(boolean shutdown) {
		}

		@Override
		public void onModeKnown(NodeMode mode) {
			OldModule.mode = mode;
		}

		@Override
		public Map<String, ManagementAction> managementActions() {
			return Map.of("old-action", new ManagementAction() {
				@Override
				public String description() {
					return "an old action";
				}

				@Override
				public ActionResponse handle(ManagementRequest request) {
					ActionResponse response = new ActionResponse();
					response.setAction(request.action() + ":" + request.parameter("p"));
					return response;
				}
			});
		}
	}

	@Test
	public void aModuleWithoutAManifestRunsThroughTheAdapter() throws Exception {
		File root = folder.newFolder("old");
		File services = new File(root, "META-INF/services/" + FrontcacheModule.class.getName());
		services.getParentFile().mkdirs();
		Files.writeString(services.toPath(), OldModule.class.getName() + "\n", StandardCharsets.UTF_8);

		URLClassLoader loader = new URLClassLoader(new java.net.URL[] { root.toURI().toURL() }, getClass().getClassLoader());
		FrontcachePlugins.install(FrontcachePlugins.load(loader));
		FrontcachePlugins.modeKnown(org.frontcache.plugin.NodeMode.STANDALONE);
		FrontcachePlugins.start("www.example.com", List::of);

		PluginStats stats = FrontcachePlugins.stats("old");
		assertNotNull(stats);
		assertTrue(stats.isLegacy());
		assertEquals("started", stats.getState());
		assertEquals(List.of("manage"), stats.getKinds());
		assertEquals("www.example.com", OldModule.context.publicDomain());
		assertEquals(NodeMode.STANDALONE, OldModule.mode);

		org.frontcache.plugin.ManagementAction action = FrontcachePlugins.managementAction("old-action");
		assertEquals("an old action", action.description());
		ActionResponse response = action.handle(new org.frontcache.plugin.ManagementRequest() {
			public String action() { return "old-action"; }
			public String parameter(String name) { return "p".equals(name) ? "v" : null; }
			public InputStream body() { return InputStream.nullInputStream(); }
			public boolean isSecure() { return false; }
			public int localPort() { return 0; }
			public boolean isAuthorizedToWrite() { return false; }
			public OutputStream openRawOutput(String contentType, String fileName) { return OutputStream.nullOutputStream(); }
		});
		assertEquals("old-action:v", response.getAction());
	}
}
