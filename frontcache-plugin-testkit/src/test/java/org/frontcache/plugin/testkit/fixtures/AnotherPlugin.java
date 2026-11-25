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

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginContext;

/**
 * A second plugin with a manifest on the test class path (src/test/manifests/another), so that
 * {@code PluginHarness.manifestOf} has more than one to choose from - as a node has one per jar.
 */
public final class AnotherPlugin implements FrontcachePlugin {

	@Override
	public void start(PluginContext context) {
	}
}
