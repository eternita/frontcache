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

import org.frontcache.client.FrontCacheClient;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * Management API (FrontCacheIOServlet) api-key validation.
 *
 * The management endpoint gates every action against the configured
 * front-cache.api-key (test-api-key-localhost in both test FRONTCACHE_HOME dirs).
 * A request carrying the matching api-key header is served; a request with a
 * wrong or blank API key gets an "Access denied" response.
 *
 */
public abstract class ApiKeyTests extends TestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	public abstract String getFrontCacheBaseURLLocalhost();

	@Test
	public void validApiKey() throws Exception {

		FrontCacheClient client = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);

		String response = client.removeFromCacheAll();

		// matching API key -> action is executed
		Assert.assertNotEquals("expected invalidate action to run, got: " + response, -1, response.indexOf("invalidate"));
		Assert.assertNotEquals("expected OK status, got: " + response, -1, response.indexOf("OK"));
		Assert.assertEquals("unexpected access denied, got: " + response, -1, response.indexOf("Access denied"));
		return;
	}

	@Test
	public void invalidApiKey() throws Exception {

		FrontCacheClient client = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), "wrong-api-key");

		String response = client.removeFromCacheAll();

		// API key does not match -> access denied, action not executed
		Assert.assertNotEquals("expected access denied, got: " + response, -1, response.indexOf("Access denied"));
		Assert.assertEquals("action must not run for a wrong API key, got: " + response, -1, response.indexOf("invalidate"));
		return;
	}

	@Test
	public void blankApiKey() throws Exception {

		FrontCacheClient client = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), "");

		String response = client.removeFromCacheAll();

		// blank API key -> access denied, action not executed
		Assert.assertNotEquals("expected access denied, got: " + response, -1, response.indexOf("Access denied"));
		Assert.assertEquals("action must not run for a blank API key, got: " + response, -1, response.indexOf("invalidate"));
		return;
	}

}
