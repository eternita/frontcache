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
package org.frontcache.tests;

public class TestConfig {

	// is used to cleanup generaged fallbacks in FRONTCACHE_HOME_XXX folders
	public static final String FRONTCACHE_TEST_PROJECT_DIR_KEY = "frontcache-tests.home";

	// Both nodes are reached over loopback - no /etc/hosts entries required.
	public static final String FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST = "http://localhost:9080/";

	public static final String FRONTCACHE_FILTER_TEST_BASE_URI_LOCALHOST = "http://localhost:8080/";

	// A second spelling of the SAME node. FrontCacheCluster / FrontCacheAgentCluster key their
	// result maps by member URL, so a cluster fan-out test needs two distinct member URLs; there is
	// no second node. This used to be a second virtual hostname (www.fc2-test.org) back when a node
	// could serve several domains - see docs/archive/multi-domain-retirement-proposal.md.
	public static final String FRONTCACHE_STANDALONE_TEST_BASE_URI_LOOPBACK = "http://127.0.0.1:9080/";

	public static final String FRONTCACHE_FILTER_TEST_BASE_URI_LOOPBACK = "http://127.0.0.1:8080/";

}
