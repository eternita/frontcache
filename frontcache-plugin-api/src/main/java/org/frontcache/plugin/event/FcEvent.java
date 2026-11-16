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
package org.frontcache.plugin.event;

/**
 * Something that happened on a node, delivered after the fact to the {@link EventListener}s that subscribed to its
 * type. Events are immutable records built from values the node had already computed.
 *
 * <h2>What an event never carries</h2>
 *
 * Cookie values, {@code Authorization}, the API key, the warm-request token, the edge token, request bodies, or
 * the query strings of management calls. A visitor request's query string is carried (guard rules match on it and
 * an alert must say what was attacked), truncated to {@value #MAX_URI_LENGTH} characters.
 *
 * <p>A {@code clientKey} is the address the rate limiter counted, which is personal data: a sink should send
 * {@code PluginContext.hashClientKey(clientKey)} on, unless its operator chose the plain address explicitly.
 *
 * <p>New event types and new components of existing ones are additive within a {@code plugin-api} major; a
 * listener reads the accessors it knows. Plugins consume events - they never construct them for the node.
 */
public interface FcEvent {

	/** The longest uri (path + query) an event carries; longer ones are truncated. */
	int MAX_URI_LENGTH = 512;

	/** When it happened, epoch milliseconds. */
	long time();

	/** The node's id ({@code front-cache.id}). */
	String node();

	/** The site's public hostname, or null when the node has none configured. */
	String site();

	/** The event type's stable name, e.g. {@code guard.matched} - what a plugin's config refers to it by. */
	String type();
}
