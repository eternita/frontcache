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
package org.frontcache.plugin;

import java.util.Locale;

/**
 * The extension kinds, as a manifest's {@code kinds} names them. Each has its own contract, cost and failure mode;
 * the request-path ones ({@link #isRequestPath()}) are badged as such wherever a node shows its plugins.
 */
public enum PluginKind {

	/** E1: receives events after the fact, batched, on its own thread. Fails as: events dropped, counted. */
	OBSERVE(false),

	/** E2: answers requests under a path it owns, {@code /fc-x/<id>/}. Fails as: its path answers 503. */
	SERVE(false),

	/**
	 * E3: contributes named guard predicates ({@code ext:<name>:...}). Fails as: the rule does not match; a plugin over
	 * its budget is switched off for a while. Open to a community or an operator's own plugin.
	 */
	DECIDE(true),

	/** E4: rewrites a response. Reserved - not designed, never offered by this node. */
	TRANSFORM(true),

	/** E5: swaps a core implementation. Not offered through the plugin API by this node yet. */
	REPLACE(true),

	/** E6: lifecycle, background work, management actions, meters. Fails as: dropped until the next start. */
	MANAGE(false);

	private final boolean requestPath;

	PluginKind(boolean requestPath) {
		this.requestPath = requestPath;
	}

	/** Whether a plugin of this kind runs code that decides or changes a visitor's request. */
	public boolean isRequestPath() {
		return requestPath;
	}

	/** The manifest's spelling: {@code observe}, {@code serve}, ... */
	public String manifestName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** @return the kind a manifest names, or null when it names none */
	public static PluginKind fromManifestName(String name) {
		if (null == name)
			return null;
		for (PluginKind kind : values())
			if (kind.manifestName().equals(name.trim()))
				return kind;
		return null;
	}
}
