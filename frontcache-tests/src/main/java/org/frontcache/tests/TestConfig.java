/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests;

public class TestConfig {

	// is used to cleanup generaged fallbacks in FRONTCACHE_HOME_XXX folders 
	public static final String FRONTCACHE_TEST_PROJECT_DIR_KEY = "frontcache-tests.home";
	
	public static final String TEST_DOMAIN_FC1 = "fc1-test.org";

	public static final String TEST_DOMAIN_FC2 = "fc2-test.org";

	public static final String FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST = "http://localhost:9080/";
	
	public static final String FRONTCACHE_FILTER_TEST_BASE_URI_LOCALHOST = "http://localhost:8080/";
	
	public static final String FRONTCACHE_STANDALONE_TEST_BASE_URI_FC1 = "http://www.fc1-test.org:9080/";
	
	public static final String FRONTCACHE_FILTER_TEST_BASE_URI_FC1 = "http://www.fc1-test.org:8080/";

	public static final String FRONTCACHE_STANDALONE_TEST_BASE_URI_FC2 = "http://www.fc2-test.org:9080/";
	
	public static final String FRONTCACHE_FILTER_TEST_BASE_URI_FC2 = "http://www.fc2-test.org:8080/";
	
}
