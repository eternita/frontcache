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
package org.frontcache.console.model;

import java.util.List;

/**
 * One edge's plugins, for the Plugins screen (as of 2.13.0) - what its {@code get-node-stats} reported.
 *
 * Three answers the screen says differently: the edge did not answer ({@link #isAvailable()} false); it answered
 * without plugin information ({@link #isReported()} false - it predates {@code get-node-stats}, so the console fell
 * back to {@code get-cache-state}); and it answered with a list, which is empty both on a node without plugins and
 * on one that predates them - the wire does not tell the two apart, so neither does the screen.
 *
 * A class with getters, not a record: JSP EL reads JavaBeans accessors (see {@link EdgeConfigFile}).
 */
public class EdgePlugins {

	private final String name;
	private final String url;
	private final boolean available;
	private final boolean reported;
	private final List<PluginView> plugins;

	public EdgePlugins(String name, String url, boolean available, boolean reported, List<PluginView> plugins) {
		this.name = name;
		this.url = url;
		this.available = available;
		this.reported = reported;
		this.plugins = (null == plugins) ? List.of() : List.copyOf(plugins);
	}

	/** A tab for an edge that did not answer, so it is visible as unreachable rather than absent. */
	public static EdgePlugins unreachable(String name, String url) {
		return new EdgePlugins(name, url, false, false, List.of());
	}

	/** Display name, which may repeat across edges - the URL is the identity. */
	public String getName() {
		return name;
	}

	public String getUrl() {
		return url;
	}

	/** False when the edge did not answer at all. */
	public boolean isAvailable() {
		return available;
	}

	/** Whether it answered {@code get-node-stats} itself - false for a node that predates it. */
	public boolean isReported() {
		return reported;
	}

	/** Every plugin it found, started or not, in the node's order; empty when it reported none. */
	public List<PluginView> getPlugins() {
		return plugins;
	}
}
