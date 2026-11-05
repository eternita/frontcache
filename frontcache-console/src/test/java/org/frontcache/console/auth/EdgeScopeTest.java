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
package org.frontcache.console.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.frontcache.client.FrontCacheClient;
import org.junit.Test;

/**
 * {@link EdgeScope}: what a request may reach, and with which key. Every controller and the stream proxy go through
 * it, so these are the allowlist's rules.
 */
public class EdgeScopeTest {

	private static final EdgeScope TWO_SITES = EdgeScope.of("tenant-a", List.of(
			new EdgeScope.Edge("https://n1.a.example.com:443", "key-a"),
			new EdgeScope.Edge("https://n1.b.example.com:443/", "key-b")));

	@Test
	public void anEdgeMatchesWithOrWithoutItsTrailingSlash() {
		assertTrue(TWO_SITES.contains("https://n1.a.example.com:443/"));
		assertTrue(TWO_SITES.contains("https://n1.a.example.com:443"));
		assertTrue(TWO_SITES.contains(" https://n1.b.example.com:443 "));
	}

	@Test
	public void aClientIsBuiltFromTheConfiguredSpellingWithThatEdgesKey() {
		FrontCacheClient a = TWO_SITES.client("https://n1.a.example.com:443/");
		assertNotNull(a);
		assertEquals("the configured spelling, not the caller's", "https://n1.a.example.com:443", a.getFrontCacheURL());
		assertEquals("key-a", TWO_SITES.edge("https://n1.a.example.com:443/").apiKey());
		assertEquals("key-b", TWO_SITES.edge("https://n1.b.example.com:443").apiKey());
	}

	@Test
	public void anEdgeOutsideTheScopeGetsNoClientAndNoKey() {
		assertNull(TWO_SITES.client("https://n1.c.example.com:443/"));
		assertNull(TWO_SITES.client("http://169.254.169.254/"));
		assertNull(TWO_SITES.client(""));
		assertNull(TWO_SITES.client(null));
		assertNull(TWO_SITES.edge("https://n1.c.example.com:443/"));
		// not a prefix match: a path on a known edge is another URL
		assertFalse(TWO_SITES.contains("https://n1.a.example.com:443/fc-admin"));
	}

	@Test
	public void theOrderIsTheDisplayOrder() {
		assertEquals(List.of("https://n1.a.example.com:443", "https://n1.b.example.com:443/"), TWO_SITES.edgeURLs());
		assertEquals(2, TWO_SITES.clients().size());
		assertEquals(2, TWO_SITES.size());
	}

	/** Which key an edge is called with must never depend on the order it was listed in. */
	@Test
	public void oneEdgeWithTwoKeysIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EdgeScope.of("x", List.of(
				new EdgeScope.Edge("https://n1.example.com/", "one"),
				new EdgeScope.Edge("https://n1.example.com", "two"))));
		assertFalse("the message names no key", e.getMessage().contains("one") || e.getMessage().contains("two"));
	}

	@Test
	public void oneEdgeListedTwiceWithOneKeyIsOneEdge() {
		EdgeScope scope = EdgeScope.of("x", List.of("https://n1.example.com/", "https://n1.example.com"), "k");
		assertEquals(1, scope.size());
	}

	@Test
	public void toStringNeverCarriesAKey() {
		assertFalse(TWO_SITES.toString().contains("key-a"));
		assertTrue(TWO_SITES.toString().contains("tenant-a"));
	}

	@Test
	public void anEmptyScopeReachesNothing() {
		EdgeScope none = EdgeScope.empty("none");
		assertTrue(none.isEmpty());
		assertNull(none.client("https://n1.a.example.com:443/"));
		assertEquals(List.of(), none.clients());
	}
}
