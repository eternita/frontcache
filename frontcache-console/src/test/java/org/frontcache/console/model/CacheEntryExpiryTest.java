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
package org.frontcache.console.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.bots.VisitorTypeEntry;
import org.frontcache.cache.CacheProcessor;
import org.junit.Test;

/**
 * The Cache View's expiry rows: a declared type the entry does not name is shown with the stamp it reads - its base's -
 * as inherited, never as absent ("not cached for customers" would be the opposite of the truth).
 */
public class CacheEntryExpiryTest {

	private static final long NOW = 1_800_000_000_000L;

	private static final List<VisitorTypeEntry> TYPES = List.of(
			new VisitorTypeEntry("bot", "bot", false, 0),
			new VisitorTypeEntry("guest", "guest", false, 0),
			new VisitorTypeEntry("customer", "guest", true, 0),
			new VisitorTypeEntry("bot-whitelisted", "bot", false, 0));

	@Test
	public void anUnnamedDeclaredTypeReadsItsBasesStampAsInherited() {
		Map<String, Long> map = new LinkedHashMap<String, Long>();
		map.put("bot", NOW + 60_000);
		map.put("guest", NOW - 1_000);

		List<CacheEntryExpiry.Row> rows = CacheEntryExpiry.rows(map, TYPES, NOW);

		assertEquals(4, rows.size());
		assertEquals("fresh", rows.get(0).getState());
		assertNull("a root inherits from nothing", rows.get(0).getInheritedFrom());
		assertEquals("expired", rows.get(1).getState());

		CacheEntryExpiry.Row customer = rows.get(2);
		assertEquals("customer", customer.getType());
		assertEquals("guest", customer.getInheritedFrom());
		assertEquals("its base's stamp - expired, like guest's", "expired", customer.getState());
		assertEquals(rows.get(1).getExpiry(), customer.getExpiry());
		assertTrue(customer.isNoStore());

		CacheEntryExpiry.Row whitelisted = rows.get(3);
		assertEquals("bot", whitelisted.getInheritedFrom());
		assertEquals("fresh", whitelisted.getState());
	}

	@Test
	public void aNamedDeclaredTypeShowsItsOwnStamp() {
		Map<String, Long> map = new LinkedHashMap<String, Long>();
		map.put("bot", CacheProcessor.CACHE_FOREVER);
		map.put("guest", NOW + 60_000);
		map.put("customer", CacheProcessor.NO_CACHE);

		List<CacheEntryExpiry.Row> rows = CacheEntryExpiry.rows(map, TYPES, NOW);

		assertEquals("forever", rows.get(0).getState());
		assertEquals("does not expire", rows.get(0).getExpiry());
		assertNull("named, so not inherited", rows.get(2).getInheritedFrom());
		assertEquals("customer:0 - not cached for customers, though cached for guests", "not cached", rows.get(2).getState());
	}

	/** A key the edge no longer declares is stored but no request reads it - listed last, and said so. */
	@Test
	public void aStoredKeyTheEdgeDoesNotDeclareIsNeverRead() {
		Map<String, Long> map = new LinkedHashMap<String, Long>();
		map.put("bot", NOW + 60_000);
		map.put("guest", NOW + 60_000);
		map.put("partner", NOW + 60_000);

		List<CacheEntryExpiry.Row> rows = CacheEntryExpiry.rows(map, TYPES, NOW);

		CacheEntryExpiry.Row last = rows.get(rows.size() - 1);
		assertEquals("partner", last.getType());
		assertFalse(last.isKnown());
	}

	/** An edge older than 2.13.0 reports no types: the stored keys, roots first, as before. */
	@Test
	public void withoutTheEdgesTypesTheStoredKeysAreListed() {
		Map<String, Long> map = new LinkedHashMap<String, Long>();
		map.put("guest", NOW + 60_000);
		map.put("bot", NOW + 60_000);
		map.put("customer", NOW + 30_000);

		List<CacheEntryExpiry.Row> rows = CacheEntryExpiry.rows(map, null, NOW);

		assertEquals("bot", rows.get(0).getType());
		assertEquals("guest", rows.get(1).getType());
		assertEquals("customer", rows.get(2).getType());
		assertTrue("unknowable from an older edge, so not called unknown", rows.get(2).isKnown());
	}
}
