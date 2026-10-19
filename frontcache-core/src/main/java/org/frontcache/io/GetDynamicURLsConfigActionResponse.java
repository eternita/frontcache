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
 * The raw ./conf/dynamic-urls.conf of one node, for the console's Dynamic URLs screen.
 *
 * The companion of {@link GetDynamicURLsActionResponse}, which returns the patterns the node parsed
 * out of the file. Same split as guard's and bots': the parsed shape says what is in effect, this
 * one says what the operator wrote - including the comments explaining why a pattern is there, and
 * the ones that were commented out.
 */
public class GetDynamicURLsConfigActionResponse extends ActionResponse {

	// raw content of ./conf/dynamic-urls.conf (comments and empty lines preserved)
	private String dynamicURLsConfig;

	public GetDynamicURLsConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getDynamicURLsConfig() {
		return dynamicURLsConfig;
	}

	public void setDynamicURLsConfig(String dynamicURLsConfig) {
		this.dynamicURLsConfig = dynamicURLsConfig;
	}

}
