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

/**
 * The raw ./conf/bots.conf of one node, for the console's Bot Configs screen.
 *
 * The companion of {@link GetBotsActionResponse}, and the same split as guard's: that one returns
 * what the node PARSED - the rules in evaluation order, with hit counts - while this one returns the
 * file as it sits on disk, comments and all. An operator reads the parsed view to see what is
 * deciding, and this one to see what they wrote, including the lines that were skipped.
 */
public class GetBotsConfigActionResponse extends ActionResponse {

	// raw content of ./conf/bots.conf (comments and empty lines preserved)
	private String botsConfig;

	public GetBotsConfigActionResponse() { // for JSON mapper
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getBotsConfig() {
		return botsConfig;
	}

	public void setBotsConfig(String botsConfig) {
		this.botsConfig = botsConfig;
	}

}
