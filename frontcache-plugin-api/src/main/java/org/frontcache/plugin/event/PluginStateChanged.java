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
package org.frontcache.plugin.event;

/**
 * A plugin started, failed, was refused or stopped.
 *
 * @param plugin the plugin's id
 * @param state  {@code started}, {@code failed}, {@code refused} or {@code stopped}
 * @param reason why, for {@code failed} / {@code refused}; otherwise null
 */
public record PluginStateChanged(long time, String node, String site, String plugin, String state, String reason)
		implements FcEvent {

	public static final String TYPE = "plugin.state";

	@Override
	public String type() {
		return TYPE;
	}
}
