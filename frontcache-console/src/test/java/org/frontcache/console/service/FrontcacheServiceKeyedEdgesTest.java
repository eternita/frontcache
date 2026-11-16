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
package org.frontcache.console.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.frontcache.console.auth.EdgeScope;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

/**
 * {@code edges} (2.13.0): edges with their own API key beside {@code urls}. Read whole or refused whole, never quoting
 * a key; one URL is never in both lists. {@code EdgeKeyIsolationTest} is what proves each key reaches its own edges
 * only.
 */
public class FrontcacheServiceKeyedEdgesTest {

	private static List<EdgeScope.Edge> parse(String hocon, String... urls) {
		return FrontcacheService.parseKeyedEdges(ConfigFactory.parseString(hocon), List.of(urls));
	}

	private static IllegalStateException refused(String hocon, String... urls) {
		return assertThrows(IllegalStateException.class, () -> parse(hocon, urls));
	}

	@Test
	public void noEdgesKeyIsNoEdges() {
		assertEquals(List.of(), parse("urls = [\"https://a.example.com/\"]"));
		assertEquals(List.of(), FrontcacheService.parseKeyedEdges(null, List.of()));
	}

	@Test
	public void eachEntryKeepsItsKeyAndLabelInConfigOrder() {
		List<EdgeScope.Edge> edges = parse("""
				edges = [
				  { url = "https://origin.example.com/shop", apiKey = "k-origin", label = origin }
				  { url = "https://other.example.com/", apiKey = "k-other" }
				]
				""", "https://n1.example.com:443/");
		assertEquals(List.of(new EdgeScope.Edge("https://origin.example.com/shop", "k-origin", "origin"),
				new EdgeScope.Edge("https://other.example.com/", "k-other", null)), edges);
	}

	@Test
	public void aUrlInUrlsAndEdgesRefusesToStart() {
		IllegalStateException e = refused("edges = [ { url = \"https://n1.example.com:443\", apiKey = secret-key } ]",
				"https://n1.example.com:443/");
		assertTrue(e.getMessage(), e.getMessage().contains("in urls too"));
		assertFalse("never the key: " + e.getMessage(), e.getMessage().contains("secret-key"));
	}

	@Test
	public void aUrlTwiceInEdgesRefusesToStart() {
		IllegalStateException e = refused("""
				edges = [ { url = "https://o.example.com", apiKey = a }, { url = "https://o.example.com/", apiKey = a } ]
				""");
		assertTrue(e.getMessage(), e.getMessage().contains("twice"));
	}

	@Test
	public void anEntryWithoutUrlOrKeyOrWithAnUnknownKeyRefusesToStart() {
		assertTrue(refused("edges = [ { apiKey = a } ]").getMessage().contains("url is missing"));
		assertTrue(refused("edges = [ { url = \"https://o.example.com/\" } ]").getMessage().contains("apiKey is missing"));
		assertTrue(refused("edges = [ { url = \"https://o.example.com/\", apiKey = \"  \" } ]").getMessage().contains("apiKey is empty"));
		// a misspelt apiKey would otherwise call the edge with no key at all
		IllegalStateException e = refused("edges = [ { url = \"https://o.example.com/\", apikey = secret-key, apiKey = a } ]");
		assertTrue(e.getMessage(), e.getMessage().contains("unknown key 'apikey'"));
		assertFalse("never the key: " + e.getMessage(), e.getMessage().contains("secret-key"));
	}

	@Test
	public void edgesThatIsNotAListOfObjectsRefusesToStart() {
		refused("edges = [\"https://o.example.com/\"]");
		refused("edges = \"https://o.example.com/\"");
	}

	/** The Edges screen never puts an edges entry's URL in urls: one URL with two keys fails every request. */
	@Test
	public void theEdgesScreenDoesNotAddAnEdgesEntryToUrls() {
		FrontcacheService service = new FrontcacheService() {
			@Override
			List<EdgeScope.Edge> configuredKeyedEdges() {
				return List.of(new EdgeScope.Edge("https://origin.example.com/", "origin-key", "origin"));
			}
		};
		service.addEdge("https://origin.example.com");
		service.addEdge("https://n2.example.com/");
		assertEquals(List.of("https://n2.example.com/"), service.configuredEdgeURLs());
	}

	/** The scope: urls with the console-wide key, then every edges entry with its own. */
	@Test
	public void theConfiguredScopeCallsEachEdgeWithItsOwnKey() {
		FrontcacheService service = new FrontcacheService() {
			@Override
			List<String> configuredEdgeURLs() {
				return List.of("https://n1.example.com:443/");
			}

			@Override
			String configuredApiKey() {
				return "fleet-key";
			}

			@Override
			List<EdgeScope.Edge> configuredKeyedEdges() {
				return List.of(new EdgeScope.Edge("https://origin.example.com/", "origin-key", "origin"));
			}
		};
		EdgeScope scope = new ConfiguredEdgeScope(service).everyEdge();
		assertEquals(List.of("https://n1.example.com:443/", "https://origin.example.com/"), scope.edgeURLs());
		assertEquals("fleet-key", scope.edge("https://n1.example.com:443").apiKey());
		assertEquals("origin-key", scope.edge("https://origin.example.com").apiKey());
	}
}
