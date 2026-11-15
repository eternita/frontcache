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
package com.example.frontcache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.decide.PluginPredicate;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.testkit.FakePluginRequest;
import org.frontcache.plugin.testkit.FakePluginResponse;
import org.frontcache.plugin.testkit.PluginContract;
import org.frontcache.plugin.testkit.RequestViews;
import org.junit.Test;

/**
 * The plugin's tests: the testkit's {@link PluginContract} (inherited - the manifest, the kinds, the life cycle,
 * every predicate under its budget, the listener, the endpoint, the configs) plus what this plugin promises.
 */
public class ExamplePluginTest extends PluginContract {

	@Override
	protected Class<? extends FrontcachePlugin> pluginClass() {
		return ExamplePlugin.class;
	}

	@Override
	protected Map<String, List<String>> predicateArguments() {
		return Map.of("path-depth", List.of(">4", ">= 12", "<2", "<=3", "=0"));
	}

	@Override
	protected List<FakePluginRequest> endpointRequests() {
		List<FakePluginRequest> requests = new ArrayList<>(super.endpointRequests());
		requests.add(FakePluginRequest.get("/").from("127.0.0.1"));
		return requests;
	}

	@Override
	protected List<String> acceptedConfigs() {
		return List.of("", "max-depth = 8", "max-depth = ${?NO_SUCH_VARIABLE_HERE}");
	}

	@Override
	protected List<String> refusedConfigs() {
		return List.of("max-depth = 0", "max-depth = lots", "max-depth = 8\nmax-dept = 9");
	}

	@Test
	public void depthCountsNonEmptySegments() {
		assertEquals(0, PathDepthPredicate.depth("/"));
		assertEquals(0, PathDepthPredicate.depth(""));
		assertEquals(3, PathDepthPredicate.depth("/a/b/c"));
		assertEquals(3, PathDepthPredicate.depth("/a/b/c/"));
		assertEquals(2, PathDepthPredicate.depth("/a//b"));
		assertEquals(-1, PathDepthPredicate.depth(null));
	}

	@Test
	public void theRuleMatchesDeepPathsOnly() throws Exception {
		PluginPredicate deep = harness.predicate("ext:path-depth:>4");
		assertTrue(deep.test(RequestViews.get("/a/b/c/d/e").build()));
		assertFalse(deep.test(RequestViews.get("/a/b/c/d").build()));
		assertFalse(deep.test(RequestViews.get("/a/b/c/d?x=/1/2/3").build()));   // the query is not the path
		assertFalse("a view with nothing behind it matches no comparison", deep.test(RequestViews.empty()));
		assertFalse(harness.predicate("ext:path-depth:<2").test(RequestViews.empty()));
	}

	@Test
	public void anArgumentItCannotReadIsRefused() {
		for (String argument : List.of("", "4", ">four", "> -1", "~4", ">33"))   // 33 is over the default max-depth
		{
			try {
				harness.predicate("ext:path-depth:" + argument);
				fail("accepted '" + argument + "'");
			} catch (PluginConfigException e) {
				assertFalse(e.reasons().isEmpty());
			}
		}
	}

	@Test
	public void maxDepthComesFromTheConfig() throws Exception {
		harness.configure("max-depth = 64");
		harness.reload();   // predicates are read after a start
		harness.predicate("ext:path-depth:>40");
	}

	@Test
	public void theEndpointShowsTheCountsToTheNodeItselfOnly() throws Exception {
		harness.deliver(new GuardMatched(1L, "fc-1", "www.example.com", "r-1", "block-wp", "rejected", 403,
				"www.example.com/wp-login.php", "guest", "203.0.113.7"));
		harness.deliver(new GuardMatched(2L, "fc-1", "www.example.com", "r-2", "block-wp", "rejected", 403,
				"www.example.com/wp-login.php", "guest", "203.0.113.8"));

		FakePluginResponse local = harness.serve(FakePluginRequest.get("/").from("127.0.0.1"));
		assertEquals(200, local.status());
		assertEquals("{\"guardMatched\":{\"rejected\":2},\"maxDepth\":32}", local.text());
		assertFalse("no address in the answer", local.text().contains("203.0.113"));

		assertEquals(404, harness.serve(FakePluginRequest.get("/").from("203.0.113.7")).status());
		assertEquals(404, harness.serve(FakePluginRequest.get("/").from(null)).status());
		assertEquals(404, harness.serve(FakePluginRequest.get("/other").from("127.0.0.1")).status());
	}
}
