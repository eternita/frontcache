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
package org.frontcache.module;

import java.util.Map;

/**
 * A module that plugs into a node from its own jar - found with {@link java.util.ServiceLoader} through
 * {@code META-INF/services/org.frontcache.module.FrontcacheModule}.
 *
 * <h2>Never on the request path</h2>
 *
 * Nothing here is called while a request is served: a module gets the node's lifecycle, management
 * actions, and meters - nothing in {@code FrontCacheEngine.init()}, the cache or include processors, the
 * guard or the resilience commands. A node with no module serves exactly what a node with one serves.
 *
 * <h2>A module cannot stop a node</h2>
 *
 * {@link FrontcacheModules} catches everything a module throws. A module that throws in {@link #start} is
 * logged and dropped until the next start; the node starts regardless.
 *
 * <h2>One instance per process</h2>
 *
 * A module is instantiated once, like {@code FCConfig} and the cache processor, and outlives an engine:
 * the {@code reload} action calls {@code stop(false)} then {@code start(...)} on the same instance. The
 * constructor must be cheap and side-effect free - it may run from a servlet's init, before any engine.
 */
public interface FrontcacheModule {

	/** A short, stable id - for logs and the {@code modules} list of {@code get-node-stats}. */
	String id();

	/** The engine has started: the cache processor exists, metrics export has not been started yet. */
	void start(ModuleContext context);

	/**
	 * @param shutdown true when the process is going away; false when {@code reload} is about to start a
	 *        replacement engine - process-wide state should survive
	 */
	void stop(boolean shutdown);

	/**
	 * The servlet or filter has told the node how it is deployed. <b>Not necessarily before
	 * {@link #start}</b>: no servlet is load-on-startup, so whichever sees the first request builds the
	 * engine. Called again only if the mode changes.
	 */
	default void onModeKnown(NodeMode mode) {
	}

	/**
	 * Management actions this module answers, by action name. Consulted <b>after</b> the core's own
	 * actions and before the ping fallback, so a name the core owns can never be shadowed. The caller has
	 * already passed {@code ApiKey.isAuthorized}; whether it may also write is on the request.
	 */
	default Map<String, ManagementAction> managementActions() {
		return Map.of();
	}

	/**
	 * Registers this module's series on the metrics exporter's registry - an
	 * {@code io.micrometer.core.instrument.MeterRegistry}, typed {@code Object} so this interface carries no
	 * Micrometer type. Called once per exporter, possibly before {@link #start}; meters must read state at
	 * scrape time rather than capture it now. Names are prefixed {@code frontcache.} by the registry.
	 */
	default void bindMeters(Object meterRegistry) {
	}
}
