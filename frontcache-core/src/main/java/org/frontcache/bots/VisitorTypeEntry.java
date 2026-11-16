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
package org.frontcache.bots;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire/DTO view of one visitor type a node knows - a root ({@code bot}, {@code guest}) or a type {@code bots.conf}
 * declares - for {@code get-bots}' {@code visitorTypes} (2.13.0) and the console's types table.
 *
 * Flat, like {@link BotRuleEntry}: {@code base} is what the type behaves like wherever it is not named - the type
 * itself for a root - and {@code hits} the requests the rules whose verdict it is decided (dry-run rules decide
 * nothing and are not summed), a snapshot reset by a restart or a {@code reload-bots}. Unknown fields are ignored so
 * a console reads a later node's entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VisitorTypeEntry {

	private String name;
	private String base;
	private boolean noStore;
	private long hits;

	public VisitorTypeEntry() { // for JSON mapper
	}

	public VisitorTypeEntry(String name, String base, boolean noStore, long hits) {
		this.name = name;
		this.base = base;
		this.noStore = noStore;
		this.hits = hits;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	/** the root this type behaves like where nothing names it - for a root, the root itself */
	public String getBase() {
		return base;
	}

	public void setBase(String base) {
		this.base = base;
	}

	/** true for a root - {@code bot} or {@code guest}. Derived, so not on the wire */
	@JsonIgnore
	public boolean isBuiltIn() {
		return null != name && name.equals(base);
	}

	/** {@code @type <name> like <root> no-store}: its requests never write an entry */
	public boolean isNoStore() {
		return noStore;
	}

	public void setNoStore(boolean noStore) {
		this.noStore = noStore;
	}

	public long getHits() {
		return hits;
	}

	public void setHits(long hits) {
		this.hits = hits;
	}

	@Override
	public String toString() {
		return name + " like " + base + (noStore ? " no-store" : "") + " (" + hits + " hits)";
	}
}
