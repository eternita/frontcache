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
 * The config files the management API will write, and the only names {@code set-config} accepts.
 *
 * <h2>This enum IS the allowlist</h2>
 *
 * A caller sends a logical id - {@code bots}, {@code guard-rules} - and never a path or a file name.
 * {@link #byId} maps it to the file name held here; anything unrecognized is refused. That is the
 * whole path-traversal defence, and it is a closed set rather than a check on a caller-supplied
 * string, because a check is a thing that can be written slightly wrong. There is no reason for a
 * caller to name a file, so it cannot.
 *
 * One file per Configs screen in the console, and deliberately no more:
 * conf/frontcache.properties is excluded because it holds front-cache.api-key (a console editing it
 * could lock itself out of the node it is editing), because FCConfig has no live re-read at all, and
 * because a typo in it is a node that does not serve.
 */
public enum ConfigFile {

	RESILIENCE("resilience", "resilience.properties"),
	GUARD_RULES("guard-rules", "guard-rules.conf"),
	BOTS("bots", "bots.conf"),
	DYNAMIC_URLS("dynamic-urls", "dynamic-urls.conf"),
	FALLBACKS("fallbacks", "fallbacks.conf");

	private final String id;
	private final String fileName;

	ConfigFile(String id, String fileName) {
		this.id = id;
		this.fileName = fileName;
	}

	/** The id a caller sends, and the one the console's screens are keyed by. */
	public String getId() {
		return id;
	}

	/** The name under FRONTCACHE_HOME/conf - never composed from caller input. */
	public String getFileName() {
		return fileName;
	}

	/** @return the file, or null when the id is not one of ours */
	public static ConfigFile byId(String id)
	{
		if (null == id)
			return null;

		for (ConfigFile configFile : values())
			if (configFile.id.equals(id.trim()))
				return configFile;

		return null;
	}

	/** The accepted ids, for an error message that tells the caller what it should have sent. */
	public static String ids()
	{
		StringBuilder sb = new StringBuilder();
		for (ConfigFile configFile : values())
		{
			if (0 < sb.length())
				sb.append('|');
			sb.append(configFile.id);
		}
		return sb.toString();
	}
}
