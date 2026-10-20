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

/**
 * The outcome of {@code reload-dynamic-urls}: how many patterns are now active.
 *
 * The count matters more here than for the other reloads. An unparseable pattern is skipped with a
 * log line rather than failing the load, so a file of ten patterns that reports two is the only
 * signal at the console that eight of them are not doing anything.
 */
public class ReloadDynamicUrlsActionResponse extends ActionResponse {

	private int patternCount = 0;

	public ReloadDynamicUrlsActionResponse() { // for JSON mapper
		setAction("Frontcache dynamic URL patterns have been reloaded");
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public int getPatternCount() {
		return patternCount;
	}

	public void setPatternCount(int patternCount) {
		this.patternCount = patternCount;
	}

}
