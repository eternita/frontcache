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

import java.util.ArrayList;
import java.util.List;

/**
 * {@code reload-plugin-config}: what re-reading a plugin's {@code conf/<config>} did. {@code applied} false with
 * status ERROR when the config was refused (a running plugin keeps the one it had) or there was nothing to reload;
 * {@code errors} says why.
 */
public class ReloadPluginConfigActionResponse extends ActionResponse {

	private String plugin;
	private String file;
	private boolean applied;
	private String state;
	private List<String> errors = new ArrayList<>();

	public ReloadPluginConfigActionResponse() { // for JSON mapper
		setAction(FrontcacheAction.RELOAD_PLUGIN_CONFIG);
		setResponseStatus(RESPONSE_STATUS_OK);
	}

	public String getPlugin() {
		return plugin;
	}

	public void setPlugin(String plugin) {
		this.plugin = plugin;
	}

	public String getFile() {
		return file;
	}

	public void setFile(String file) {
		this.file = file;
	}

	public boolean isApplied() {
		return applied;
	}

	public void setApplied(boolean applied) {
		this.applied = applied;
	}

	/** The plugin's state afterwards: {@code started}, {@code failed}, ... */
	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public List<String> getErrors() {
		return errors;
	}

	public void setErrors(List<String> errors) {
		this.errors = (null == errors) ? new ArrayList<>() : new ArrayList<>(errors);
	}
}
