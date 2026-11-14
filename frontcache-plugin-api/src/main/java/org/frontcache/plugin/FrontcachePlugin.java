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

import java.util.List;
import java.util.Map;

import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.serve.PluginEndpoint;

import com.typesafe.config.Config;

/**
 * A plugin: code from its own jar that plugs into a node. Its jar carries
 * {@value PluginManifest#RESOURCE}, which names this class ({@code class = ...}) and declares what the plugin is
 * ({@link PluginManifest}); the node instantiates the class through its public no-argument constructor.
 *
 * <h2>Kinds</h2>
 *
 * What a plugin may contribute is decided by the {@code kinds} its manifest declares, and checked: a plugin that
 * hands the node an {@link EventListener} without declaring {@link PluginKind#OBSERVE}, or a management action
 * without {@link PluginKind#MANAGE}, is refused at start. Each method below names its kind.
 *
 * <h2>A plugin that fails is a plugin that is absent</h2>
 *
 * Every call into a plugin is wrapped by the node. A plugin that throws in {@link #start} is logged and dropped
 * until the next start, and the node starts regardless; what "absent" means for each kind is decided by the core,
 * never by the plugin, and is always the behaviour of a node that never had it.
 *
 * <h2>One instance per process</h2>
 *
 * A plugin is instantiated once and outlives an engine: the {@code reload} action calls {@code stop(false)} then
 * {@code start(...)} on the same instance. The constructor must be cheap and side-effect free - it may run from a
 * servlet's init, before any engine.
 */
public interface FrontcachePlugin {

	/**
	 * Checks a config <b>without applying it</b> - what the node asks before it writes {@code conf/<config>} for a
	 * caller ({@code set-config}, the console's Save), so a file this plugin would refuse is never written. Parse
	 * exactly as {@link #configure} does and keep nothing. Only for a plugin whose manifest names a {@code config}.
	 *
	 * <p>The default accepts every config that parses: a plugin that does not override it can be handed, at the
	 * next start, a file {@link #configure} refuses.
	 *
	 * @param config {@code conf/<config>} as it would be read: parsed and resolved, as {@link #configure} gets it
	 * @throws PluginConfigException with every reason the config would be refused
	 */
	default void validateConfig(Config config) throws PluginConfigException {
	}

	/**
	 * The plugin's config, {@code conf/<config>} as its manifest names it: HOCON, parsed and with {@code ${?ENV}}
	 * substitutions resolved by the node - the plugin never opens the file. An absent file is an empty config. Only
	 * for a plugin whose manifest names a {@code config}.
	 *
	 * <p>Called <b>before</b> {@link #start} on every start (the node re-reads the file each time), and on
	 * {@code reload-plugin-config} while the plugin runs, from a management thread.
	 *
	 * <p><b>Read whole or refused whole.</b> Parse into a new value and replace the running one only when all of
	 * it is accepted. Thrown before a start, the plugin is not started (the node starts, without it); thrown on a
	 * reload, the plugin must still be running on the config it had - which is why it must not apply any of a
	 * config it refuses.
	 *
	 * @throws PluginConfigException with every reason the config is refused
	 */
	default void configure(Config config) throws PluginConfigException {
	}

	/**
	 * The engine has started: the cache exists, metrics export has not been started yet. {@link #eventListeners()}
	 * and {@link #managementActions()} are read right after this returns. A plugin with a config has been
	 * {@linkplain #configure configured} just before.
	 */
	void start(PluginContext context);

	/**
	 * @param shutdown true when the process is going away; false when {@code reload} is about to start a
	 *        replacement engine - process-wide state should survive
	 */
	default void stop(boolean shutdown) {
	}

	/**
	 * The servlet or filter has told the node how it is deployed. <b>Not necessarily before {@link #start}</b>:
	 * whichever of them sees the first request builds the engine. Called again only if the mode changes.
	 */
	default void onModeKnown(NodeMode mode) {
	}

	/**
	 * {@link PluginKind#OBSERVE}: the listeners the node delivers events to, each on its own thread, through its
	 * own bounded queue. Read after every start, and again after {@link #configure} accepted a config on
	 * {@code reload-plugin-config} - a config may change what the plugin observes. A listener handed back again
	 * (the same instance, subscribing to the same types) keeps its queue; one no longer handed back is stopped.
	 */
	default List<EventListener> eventListeners() {
		return List.of();
	}

	/**
	 * {@link PluginKind#SERVE}: what answers the requests under {@code /fc-x/<id>/} - this plugin's own path, and no
	 * other. Read after every start; null (the default) for a plugin that serves nothing. A plugin that hands one over
	 * without declaring {@code serve} is refused at start.
	 */
	default PluginEndpoint endpoint() {
		return null;
	}

	/**
	 * {@link PluginKind#MANAGE}: management actions this plugin answers on {@code /frontcache-io}, by action
	 * name. Consulted <b>after</b> the core's own actions and before the ping fallback, so a name the core owns can
	 * never be shadowed. The caller has already passed the API-key gate; whether it may also write is on the
	 * request. Read once per start.
	 */
	default Map<String, ManagementAction> managementActions() {
		return Map.of();
	}

	/**
	 * {@link PluginKind#MANAGE}: registers this plugin's series on the metrics exporter's registry - an
	 * {@code io.micrometer.core.instrument.MeterRegistry}, typed {@code Object} so this API carries no Micrometer
	 * type. Called once per exporter, possibly before {@link #start}; meters must read state at scrape time rather
	 * than capture it now. Names are prefixed {@code frontcache.} by the registry.
	 */
	default void bindMeters(Object meterRegistry) {
	}
}
