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
package org.frontcache.io;

import java.util.Map;
import java.util.Set;

import org.frontcache.resilience.fr.FallbackConfigEntry;

/**
 * URL-pattern to fallback-file mappings from ./conf/fallbacks.conf.
 *
 * A flat set as of 2.7; see {@link GetBotsActionResponse} and {@link LegacyDomainKeyedField}.
 */
public class GetFallbackConfigActionResponse extends ActionResponse {

	private Set<FallbackConfigEntry> fallbackEntries;

	public GetFallbackConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Set<FallbackConfigEntry> getFallbackEntries() {
		return fallbackEntries;
	}

	public void setFallbackEntries(Set<FallbackConfigEntry> fallbackEntries) {
		this.fallbackEntries = fallbackEntries;
	}

	/**
	 * @deprecated pre-2.7 shape of {@link #getFallbackEntries()} - a single-entry map keyed by domain.
	 */
	@Deprecated
	public Map<String, Set<FallbackConfigEntry>> getFallbackConfigs() {
		return LegacyDomainKeyedField.wrap(fallbackEntries);
	}

	/** @deprecated pre-2.7 shape of {@link #setFallbackEntries(Set)} */
	@Deprecated
	public void setFallbackConfigs(Map<String, Set<FallbackConfigEntry>> fallbackConfigs) {
		if (null == fallbackEntries)
			fallbackEntries = LegacyDomainKeyedField.flatten(fallbackConfigs);
	}

}
