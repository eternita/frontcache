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
package org.frontcache.plugin.testkit.fixtures;

import java.util.List;
import java.util.Map;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.testkit.PluginContract;

/** Every {@link PluginContract} test over {@link WellBehavedPlugin} - all of them pass. */
public class WellBehavedContract extends PluginContract {

	@Override
	protected Class<? extends FrontcachePlugin> pluginClass() {
		return WellBehavedPlugin.class;
	}

	@Override
	protected Map<String, List<String>> predicateArguments() {
		return Map.of("uri-longer", List.of("0", "12", " 200 "));
	}

	@Override
	protected List<String> acceptedConfigs() {
		return List.of("", "max-length = 64", "max-length = ${?NO_SUCH_VARIABLE_HERE}");
	}

	@Override
	protected List<String> refusedConfigs() {
		return List.of("max-length = 0", "max-length = long", "max-length = 8\nmax-lenght = 9");
	}
}
