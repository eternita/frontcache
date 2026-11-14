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
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.serve.EvidenceSigner;

/**
 * The context a dropped-in plugin gets: {@link PluginContext} and nothing more. A wrapper rather than the core's own
 * context, so a cast to {@link BundledPluginContext} - the warm token, a raw property, the cache - fails: a plugin from
 * a directory never receives them (and its class loader cannot even name that interface).
 */
final class DroppedInPluginContext implements PluginContext {

	private final PluginContext delegate;

	DroppedInPluginContext(PluginContext delegate) {
		this.delegate = delegate;
	}

	@Override
	public PluginManifest manifest() {
		return delegate.manifest();
	}

	@Override
	public String publicDomain() {
		return delegate.publicDomain();
	}

	@Override
	public String nodeId() {
		return delegate.nodeId();
	}

	@Override
	public String coreVersion() {
		return delegate.coreVersion();
	}

	@Override
	public File frontcacheHome() {
		return delegate.frontcacheHome();
	}

	@Override
	public File dataDirectory() {
		return delegate.dataDirectory();
	}

	@Override
	public String hashClientKey(String clientKey) {
		return delegate.hashClientKey(clientKey);
	}

	@Override
	public EvidenceSigner evidenceSigner() {
		return delegate.evidenceSigner();
	}

	@Override
	public ScheduledExecutorService scheduler() {
		return delegate.scheduler();
	}

	@Override
	public int writeList(String name, List<String> lines) throws IOException {
		return delegate.writeList(name, lines);
	}
}
