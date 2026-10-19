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

/**
 * Wire/DTO view of a {@link BotRule} - what the management API (get-bots) returns and the console
 * renders, alongside the flat {@code botKeywords} an older console knows how to read.
 *
 * Deliberately flat and JSON-friendly: the live rule holds compiled patterns and a counter, none of
 * which travel. {@code hits} is a snapshot taken when the response was built; counters are
 * in-memory and reset when the node restarts or the rules are reloaded.
 */
public class BotRuleEntry {

	private String name;
	private String condition;
	private String clientType;
	private boolean dryRun;
	private long hits;

	public BotRuleEntry() { // for JSON mapper
	}

	public BotRuleEntry(String name, String condition, String clientType, boolean dryRun, long hits) {
		this.name = name;
		this.condition = condition;
		this.clientType = clientType;
		this.dryRun = dryRun;
		this.hits = hits;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getCondition() {
		return condition;
	}

	public void setCondition(String condition) {
		this.condition = condition;
	}

	public String getClientType() {
		return clientType;
	}

	public void setClientType(String clientType) {
		this.clientType = clientType;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
	}

	public long getHits() {
		return hits;
	}

	public void setHits(long hits) {
		this.hits = hits;
	}

	@Override
	public String toString() {
		return name + " | " + condition + " | " + clientType + (dryRun ? " | dry-run" : "") + " (" + hits + " hits)";
	}

}
