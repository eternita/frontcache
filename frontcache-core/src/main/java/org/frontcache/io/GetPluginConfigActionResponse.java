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
 * {@code get-plugin-config}: a plugin's {@code conf/<config>} as it is on disk (or on the class path), comments
 * and blank lines kept - what the console's Plugin Configs screen shows and edits. Status ERROR when the node has no
 * such plugin, or the plugin has no config; {@code pluginConfig} null when the plugin has one and the file is absent.
 */
public class GetPluginConfigActionResponse extends ActionResponse {

	private String plugin;
	private String file;
	private String pluginConfig;

	public GetPluginConfigActionResponse() { // for JSON mapper
		setAction(FrontcacheAction.GET_PLUGIN_CONFIG);
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getPlugin() {
		return plugin;
	}

	public void setPlugin(String plugin) {
		this.plugin = plugin;
	}

	/** The file under {@code conf/}, as the plugin's manifest names it. */
	public String getFile() {
		return file;
	}

	public void setFile(String file) {
		this.file = file;
	}

	/** The raw content; null when the file is absent. */
	public String getPluginConfig() {
		return pluginConfig;
	}

	public void setPluginConfig(String pluginConfig) {
		this.pluginConfig = pluginConfig;
	}
}
