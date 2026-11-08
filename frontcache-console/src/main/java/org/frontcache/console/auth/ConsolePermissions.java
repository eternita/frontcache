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

import java.util.Set;

/**
 * The core console's permission ids, as of 2.12.0 - what {@link ConsolePermit} names and {@link ConsoleAuthorizer}
 * is asked about. Strings, not an enum, so a console plugin names its own screens' permissions without the core
 * naming them; the core owns these, so a plugin cannot leave a core endpoint unmapped.
 *
 * <b>Every POST handler carries exactly one {@link ConsolePermit}</b>, and a handler with none is refused unless it
 * is a GET (which needs {@link #VIEW}). {@code ConsolePermitTest} enumerates the handler mappings - a list kept by
 * hand is how a new endpoint ends up open to every viewer.
 */
public final class ConsolePermissions {

	/** Every GET screen, the JSON the pages poll, the realtime stream. */
	public static final String VIEW = "view";

	/** Reading what an edge cached: Cache View, the key dump. */
	public static final String CACHE_READ = "cache.read";

	/** Invalidation and purge. */
	public static final String CACHE_INVALIDATE = "cache.invalidate";

	/** Replication between edges, the L2 index backup. */
	public static final String CACHE_REPLICATE = "cache.replicate";

	/** Writing a node's config file - also needs {@code config-editing}, which switches it off for everyone. */
	public static final String CONFIG_WRITE = "config.write";

	/** Adding and removing edges - also needs {@code edge-editing}. */
	public static final String EDGES_WRITE = "edges.write";

	/**
	 * Not a permission: the handler serves a request the {@code ConsoleAuthFilter} let through without a
	 * principal - the login page and its two SSO steps, and logout. Only the filter's exact-match anonymous paths
	 * reach such a handler unauthenticated; marking any other handler with it is what the permit test refuses.
	 */
	public static final String ANONYMOUS = "anonymous";

	/** Every id above except {@link #ANONYMOUS}. */
	public static final Set<String> CORE = Set.of(VIEW, CACHE_READ, CACHE_INVALIDATE, CACHE_REPLICATE, CONFIG_WRITE,
			EDGES_WRITE);

	private ConsolePermissions() {
	}
}
