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
package org.frontcache.plugin.serve;

import java.io.IOException;

/**
 * E2 Serve: answers the requests under the path a plugin owns, {@code /fc-x/<id>/} - and no other. Returned by
 * {@code FrontcachePlugin.endpoint()}; the plugin's manifest must declare kind {@code serve}.
 *
 * <h2>What the node does around it</h2>
 *
 * The node routes {@code /fc-x/} in its engine, after the guard rules (so a {@code rate:} rule protects a plugin's
 * path like any other) and before the cache: a request under the prefix is never cached, never forwarded to the
 * origin and never a cache key. Every response carries {@code Cache-Control: no-store}. A path no started plugin owns
 * answers {@code 404} - what a node without the plugin answers.
 *
 * <h2>A plugin that fails is a plugin that is absent - here, for its own path only</h2>
 *
 * The response is buffered and written by the node once {@link #handle} returns, so an endpoint that throws answers
 * {@code 503}, and nothing it began to write reaches the visitor. Requests beyond the plugin's concurrency cap
 * ({@code front-cache.plugins.<id>.max-concurrent}, default 32) answer {@code 503} with {@code Retry-After} without
 * reaching it, so a slow page cannot take the container's threads.
 *
 * <p>Runs on the request thread of its own requests: it may block them, never anyone else's.
 */
@FunctionalInterface
public interface PluginEndpoint {

	/**
	 * @param request  a read-only view of the request - never the servlet request
	 * @param response what the node writes back once this returns
	 * @throws IOException when the request cannot be read (a body over the limit answers {@code 413})
	 */
	void handle(PluginRequest request, PluginResponse response) throws IOException;
}
