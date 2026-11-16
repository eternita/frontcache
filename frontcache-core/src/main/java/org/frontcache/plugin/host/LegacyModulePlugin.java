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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.io.ActionResponse;
import org.frontcache.module.FrontcacheModule;
import org.frontcache.module.ModuleContext;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.ManagementRequest;
import org.frontcache.plugin.NodeMode;
import org.frontcache.plugin.PluginContext;

/**
 * Runs a pre-2.13 {@link FrontcacheModule} (found through its {@code META-INF/services} file, no manifest) as a
 * {@code manage} plugin. Kept for one release so a module moves onto {@link FrontcachePlugin} without a flag day.
 */
@SuppressWarnings("deprecation")
final class LegacyModulePlugin implements FrontcachePlugin {

	final FrontcacheModule module;

	LegacyModulePlugin(FrontcacheModule module) {
		this.module = module;
	}

	@Override
	public void start(PluginContext context) {
		BundledPluginContext bundled = (BundledPluginContext) context;
		module.start(new ModuleContext() {
			@Override
			public String publicDomain() {
				return bundled.publicDomain();
			}

			@Override
			public File frontcacheHome() {
				return bundled.frontcacheHome();
			}

			@Override
			public String property(String key) {
				return bundled.property(key);
			}

			@Override
			public CacheProcessor existingCacheProcessor() {
				return bundled.existingCacheProcessor();
			}

			@Override
			public String warmRequestToken() {
				return bundled.warmRequestToken();
			}
		});
	}

	@Override
	public void stop(boolean shutdown) {
		module.stop(shutdown);
	}

	@Override
	public void onModeKnown(NodeMode mode) {
		module.onModeKnown(org.frontcache.module.NodeMode.valueOf(mode.name()));
	}

	@Override
	public Map<String, ManagementAction> managementActions() {
		Map<String, ManagementAction> actions = new LinkedHashMap<>();
		for (Map.Entry<String, org.frontcache.module.ManagementAction> e : module.managementActions().entrySet())
		{
			org.frontcache.module.ManagementAction legacy = e.getValue();
			actions.put(e.getKey(), new ManagementAction() {
				@Override
				public String description() {
					return legacy.description();
				}

				@Override
				public ActionResponse handle(ManagementRequest request) throws IOException {
					return legacy.handle(legacyRequest(request));
				}
			});
		}
		return actions;
	}

	@Override
	public void bindMeters(Object meterRegistry) {
		module.bindMeters(meterRegistry);
	}

	private static org.frontcache.module.ManagementRequest legacyRequest(ManagementRequest request) {
		return new org.frontcache.module.ManagementRequest() {
			@Override
			public String action() {
				return request.action();
			}

			@Override
			public String parameter(String name) {
				return request.parameter(name);
			}

			@Override
			public InputStream body() throws IOException {
				return request.body();
			}

			@Override
			public boolean isSecure() {
				return request.isSecure();
			}

			@Override
			public int localPort() {
				return request.localPort();
			}

			@Override
			public boolean isAuthorizedToWrite() {
				return request.isAuthorizedToWrite();
			}

			@Override
			public OutputStream openRawOutput(String contentType, String fileName) throws IOException {
				return request.openRawOutput(contentType, fileName);
			}
		};
	}
}
