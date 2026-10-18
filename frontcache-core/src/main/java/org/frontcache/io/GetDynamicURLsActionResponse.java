/*
 * Copyright 2015 Eternita
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

/**
 * URL patterns that are never cached, from ./conf/dynamic-urls.conf.
 *
 * A flat set as of 2.7; see {@link GetBotsActionResponse} and {@link LegacyDomainKeyedField}.
 */
public class GetDynamicURLsActionResponse extends ActionResponse {

	private Set<String> dynamicUrlPatterns;

	public GetDynamicURLsActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public Set<String> getDynamicUrlPatterns() {
		return dynamicUrlPatterns;
	}

	public void setDynamicUrlPatterns(Set<String> dynamicUrlPatterns) {
		this.dynamicUrlPatterns = dynamicUrlPatterns;
	}

	/**
	 * @deprecated pre-2.7 shape of {@link #getDynamicUrlPatterns()} - a single-entry map keyed by domain.
	 */
	@Deprecated
	public Map<String, Set<String>> getDynamicURLs() {
		return LegacyDomainKeyedField.wrap(dynamicUrlPatterns);
	}

	/** @deprecated pre-2.7 shape of {@link #setDynamicUrlPatterns(Set)} */
	@Deprecated
	public void setDynamicURLs(Map<String, Set<String>> dynamicURLs) {
		if (null == dynamicUrlPatterns)
			dynamicUrlPatterns = LegacyDomainKeyedField.flatten(dynamicURLs);
	}

}
