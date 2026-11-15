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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.bots.VisitorTypeEntry;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;

/**
 * The Cache View's expiry table for one entry: a row per type the edge knows - the roots, then its declared types -
 * with the stamp that type's requests actually resolve to, and where it came from.
 *
 * <b>The point is the "inherited" rows.</b> An entry stores a key only for the types its page named, and a declared
 * type it did not name reads its base's stamp ({@code VisitorTypes.ttl}). Listing only the stored keys - what this
 * screen did before 2.13.0 - makes an operator read "customer: (absent)" as "not cached for customers", the opposite
 * of the truth. A stored key the edge does not declare (a type since removed from {@code bots.conf}) is listed last,
 * as never read. From an edge older than 2.13.0 ({@code types} null) the rows are the stored keys, as before.
 *
 * Pure: no edge call, no clock but the one passed in.
 */
public final class CacheEntryExpiry {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault());

	/** One row of the table. */
	public static final class Row {
		private final String type;
		private final String base;
		private final String inheritedFrom;
		private final boolean known;
		private final boolean noStore;
		private final String expiry;
		private final String state;

		Row(String type, String base, String inheritedFrom, boolean known, boolean noStore, String expiry, String state) {
			this.type = type;
			this.base = base;
			this.inheritedFrom = inheritedFrom;
			this.known = known;
			this.noStore = noStore;
			this.expiry = expiry;
			this.state = state;
		}

		public String getType() {
			return type;
		}

		/** the root a declared type behaves like; null for a root, and for a type the edge does not know */
		public String getBase() {
			return base;
		}

		/** the root whose stamp this type reads because the entry does not name it; null when the entry names it */
		public String getInheritedFrom() {
			return inheritedFrom;
		}

		/** false for a stored key the edge does not declare - no request reads it */
		public boolean isKnown() {
			return known;
		}

		/** the type is declared no-store: it reads the entry, never writes it */
		public boolean isNoStore() {
			return noStore;
		}

		/** the stamp, as a date or a word */
		public String getExpiry() {
			return expiry;
		}

		/** fresh | expired | forever | not cached */
		public String getState() {
			return state;
		}
	}

	private CacheEntryExpiry() {
	}

	/**
	 * @param expireTimeMap the entry's stamps by type
	 * @param types         the edge's types from get-bots, or null (an edge older than 2.13.0, or unreachable)
	 * @param now           epoch millis
	 */
	public static List<Row> rows(Map<String, Long> expireTimeMap, List<VisitorTypeEntry> types, long now) {
		List<Row> rows = new ArrayList<Row>();
		if (null == expireTimeMap)
			return rows;

		Set<String> listed = new LinkedHashSet<String>();
		if (null != types)
		{
			for (VisitorTypeEntry type : types)
			{
				if (null == type.getName() || !listed.add(type.getName()))
					continue;
				Long stored = expireTimeMap.get(type.getName());
				boolean root = type.isBuiltIn() || null == type.getBase();
				String inheritedFrom = (null == stored && !root) ? type.getBase() : null;
				Long stamp = (null != stored) ? stored : (null == inheritedFrom) ? null : expireTimeMap.get(inheritedFrom);
				rows.add(row(type.getName(), root ? null : type.getBase(), inheritedFrom, true, type.isNoStore(), stamp, now));
			}
		} else {
			// what an older edge's types are: the roots, always in a map WebResponse builds
			for (String root : new String[] { FCHeaders.REQUEST_CLIENT_TYPE_BOT, FCHeaders.REQUEST_CLIENT_TYPE_GUEST })
				if (expireTimeMap.containsKey(root) && listed.add(root))
					rows.add(row(root, null, null, true, false, expireTimeMap.get(root), now));
		}

		for (Map.Entry<String, Long> stored : expireTimeMap.entrySet())
			if (listed.add(stored.getKey()))
				rows.add(row(stored.getKey(), null, null, null == types, false, stored.getValue(), now));

		return rows;
	}

	private static Row row(String type, String base, String inheritedFrom, boolean known, boolean noStore, Long stamp, long now) {
		String expiry;
		String state;
		if (null == stamp || CacheProcessor.NO_CACHE == stamp)
		{
			expiry = "-";
			state = "not cached";
		} else if (CacheProcessor.CACHE_FOREVER == stamp)
		{
			expiry = "does not expire";
			state = "forever";
		} else {
			expiry = DATE.format(Instant.ofEpochMilli(stamp));
			state = (stamp < now) ? "expired" : "fresh";
		}
		return new Row(type, base, inheritedFrom, known, noStore, expiry, state);
	}
}
