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
package org.frontcache.tests.base;

/**
 * The chain's {@code front-cache.edge-token}. One value for both e2e nodes - they are one chain - and
 * it must match {@code conf/frontcache.properties} in both FRONTCACHE_HOME_STANDALONE and
 * FRONTCACHE_HOME_FILTER.
 */
public interface EdgeTokens {
	public static final String TEST_EDGE_TOKEN = "e2e-edge-token-0123456789abcdef0123456789abcdef";
}
