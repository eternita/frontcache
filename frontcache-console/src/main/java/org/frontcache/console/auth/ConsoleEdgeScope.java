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
package org.frontcache.console.auth;

/**
 * Which edges a principal may see, and with which API key each is called, as of 2.12.0. A console plugin provides
 * one as a bean of its {@code rootComponents()}; without one, every principal sees the console's configured
 * {@code urls}, each called with its one {@code apiKey} ({@code org.frontcache.console.service.ConfiguredEdgeScope}).
 *
 * <b>This is the only way a console request reaches an edge.</b> Controllers, the realtime stream proxy and plugin
 * beans take an {@link EdgeScope} from the request and never see a console-wide edge list - an edge a request names
 * that is not in its scope is refused exactly as one that does not exist.
 */
public interface ConsoleEdgeScope {

	/** The edges this principal may see. Never null; empty when it may see none. */
	EdgeScope scopeFor(ConsolePrincipal who);

	/**
	 * Every edge this console knows, for work no request asked for - a plugin's background poll. Never for a
	 * request: a request's edges are {@link #scopeFor}'s.
	 */
	EdgeScope everyEdge();

	/**
	 * Whether the Edges screen may add and remove edges in this scope model. Only the configured list can be edited
	 * in place; a plugin's model is the plugin's.
	 */
	default boolean edgeEditing() {
		return false;
	}
}
