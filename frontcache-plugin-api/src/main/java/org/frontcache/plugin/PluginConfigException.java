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
package org.frontcache.plugin;

import java.util.List;

/**
 * A plugin's config is refused - whole. Thrown by {@link FrontcachePlugin#validateConfig} and
 * {@link FrontcachePlugin#configure} with <b>every</b> reason, so an operator fixes the file once rather than once
 * per mistake.
 *
 * <p>A reason is shown to an operator (the node's log, {@code get-node-stats}, the console's Save): it names the
 * key and what is wrong with it, and <b>never quotes a secret</b> - a webhook URL or a routing key is exactly what
 * a config of this kind holds.
 */
public class PluginConfigException extends Exception {

	private static final long serialVersionUID = 1L;

	private final List<String> reasons;

	/** @param reasons at least one; each one line, naming the key */
	public PluginConfigException(List<String> reasons) {
		super(String.join("; ", reasons));
		this.reasons = List.copyOf(reasons);
	}

	public PluginConfigException(String reason) {
		this(List.of(reason));
	}

	/** Every reason, in the order found. */
	public List<String> reasons() {
		return reasons;
	}
}
