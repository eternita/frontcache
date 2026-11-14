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
package org.frontcache.examples.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.frontcache.plugin.PluginConfigException;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * {@code conf/debug.conf}, read whole or refused whole:
 *
 * <pre>
 * # who may see the page - the visitor's address as the node resolved it (front-cache.client-ip.trusted-proxies); anyone
 * # else gets the 404 a node without the plugin answers. Default: the node itself.
 * allow = ["127.0.0.1/32", "::1/128"]
 * # guard decisions kept per visitor, newest first
 * recent = 20
 * # visitors kept - the least recently seen goes first
 * visitors = 1000
 * </pre>
 */
record DebugConfig(List<AddressBlock> allow, int recent, int visitors) {

	static final List<String> DEFAULT_ALLOW = List.of("127.0.0.1/32", "::1/128");
	static final int DEFAULT_RECENT = 20;
	static final int DEFAULT_VISITORS = 1000;
	static final int MAX_RECENT = 200;
	static final int MAX_VISITORS = 100_000;

	private static final Set<String> KEYS = Set.of("allow", "recent", "visitors");

	static DebugConfig defaults() {
		List<AddressBlock> allow = new ArrayList<>();
		for (String block : DEFAULT_ALLOW)
			allow.add(AddressBlock.parse(block));
		return new DebugConfig(List.copyOf(allow), DEFAULT_RECENT, DEFAULT_VISITORS);
	}

	/** @throws PluginConfigException with every reason, at once */
	static DebugConfig parse(Config config) throws PluginConfigException {
		List<String> reasons = new ArrayList<>();
		for (String key : config.root().keySet())
			if (!KEYS.contains(key))
				reasons.add("unknown key '" + key + "' - debug.conf has " + String.join(", ", KEYS.stream().sorted().toList()));

		List<AddressBlock> allow = defaults().allow();
		if (config.hasPath("allow"))
		{
			ConfigValue value = config.getValue("allow");
			if (value.valueType() != ConfigValueType.LIST)
				reasons.add("allow must be a list of addresses or CIDR blocks, e.g. [\"127.0.0.1/32\", \"::1/128\"]");
			else
			{
				allow = new ArrayList<>();
				for (Object entry : config.getList("allow").unwrapped())
				{
					AddressBlock block = (entry instanceof String s) ? AddressBlock.parse(s) : null;
					if (null == block)
						reasons.add("allow: '" + entry + "' is not an address or a CIDR block");
					else
						allow.add(block);
				}
				allow = List.copyOf(allow);
			}
		}

		int recent = number(config, "recent", DEFAULT_RECENT, MAX_RECENT, reasons);
		int visitors = number(config, "visitors", DEFAULT_VISITORS, MAX_VISITORS, reasons);

		if (!reasons.isEmpty())
			throw new PluginConfigException(reasons);
		return new DebugConfig(allow, recent, visitors);
	}

	private static int number(Config config, String key, int fallback, int max, List<String> reasons) {
		if (!config.hasPath(key))
			return fallback;
		try {
			int n = config.getInt(key);
			if (n < 1 || n > max)
				reasons.add(key + " must be 1.." + max + ", not " + n);
			return n;
		} catch (ConfigException e) {
			reasons.add(key + " must be a number");
			return fallback;
		}
	}

	/** Whether the visitor may see the page. */
	boolean allows(String clientAddress) {
		for (AddressBlock block : allow)
			if (block.contains(clientAddress))
				return true;
		return false;
	}
}
