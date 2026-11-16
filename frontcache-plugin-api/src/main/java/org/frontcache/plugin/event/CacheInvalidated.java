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
 * Cache entries were removed by the management API, after the removal.
 *
 * @param scope  {@code filter} (the entries whose key contains {@code filter}), {@code all} (every entry on the node,
 *               replicated ones included) or {@code purge} (the expired ones)
 * @param filter the filter as the caller sent it; null for {@code all} and {@code purge}
 */
public record CacheInvalidated(long time, String node, String site, String scope, String filter) implements FcEvent {

	public static final String TYPE = "cache.invalidated";

	public static final String SCOPE_FILTER = "filter";
	public static final String SCOPE_ALL = "all";
	public static final String SCOPE_PURGE = "purge";

	@Override
	public String type() {
		return TYPE;
	}
}
