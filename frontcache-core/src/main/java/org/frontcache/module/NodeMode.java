/*
 * Copyright 2015 Eternita
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

/**
 * How this node is deployed, as {@link FrontcacheModule#onModeKnown} reports it: behind
 * {@code FrontCacheServlet} (a reverse proxy) or {@code FrontCacheFilter} (inside the origin application).
 */
public enum NodeMode {
	STANDALONE, FILTER
}
