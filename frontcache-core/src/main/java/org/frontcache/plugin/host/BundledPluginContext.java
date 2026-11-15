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

import org.frontcache.cache.CacheProcessor;
import org.frontcache.plugin.PluginContext;

/**
 * The context the core hands every plugin it loads from its own class path - the war's {@code WEB-INF/lib}, or a
 * filter-mode application's class path, where a plugin shares the core's class loader. It adds what only code
 * built for this exact core may have: a raw property, the cache, the warm-request token.
 *
 * <p>Not part of {@code frontcache-plugin-api}, on purpose: a plugin written against the API cannot rely on any
 * of this, and a plugin loaded from a plugin directory (a later phase) will never receive it.
 */
public interface BundledPluginContext extends PluginContext {

	/** A property of {@code frontcache.properties}, read-only; null when unset. */
	String property(String key);

	/** The node's cache processor if it exists, without creating one; may be null. */
	CacheProcessor existingCacheProcessor();

	/**
	 * The in-memory token a warm request presents in {@code x-frontcache-warm} - see
	 * {@code org.frontcache.warmer.WarmRequest}. In-process only: it must never be logged or sent anywhere but to
	 * this node's own loopback.
	 */
	String warmRequestToken();
}
