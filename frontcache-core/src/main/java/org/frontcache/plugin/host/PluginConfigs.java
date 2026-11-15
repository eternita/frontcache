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
package org.frontcache.plugin.host;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.frontcache.FCConfig;
import org.frontcache.io.ConfigFile;
import org.frontcache.plugin.PluginConfigException;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;

/**
 * Reading a plugin's {@code conf/<config>}: HOCON, parsed and resolved here - {@code ${?ENV}} included - so a plugin
 * is handed a {@code Config} it can read without throwing {@code NotResolved}, and never opens a file.
 */
final class PluginConfigs {

	/** What {@code conf/} file names a plugin may not take: the core's own, whatever their extension. */
	static final Set<String> CORE_FILES;
	static {
		java.util.Set<String> names = new java.util.TreeSet<>();
		for (ConfigFile configFile : ConfigFile.values())
			names.add(configFile.getFileName());
		names.add("frontcache.properties");
		names.add("frontcache-console.conf");
		CORE_FILES = java.util.Collections.unmodifiableSet(names);
	}

	/** One read: the config, and whether the file was there. */
	record Read(Config config, boolean absent) {
	}

	/** Reads {@code conf/<file>} as every other conf file is read: FRONTCACHE_HOME, then the class path. */
	static final Function<String, String> FCCONFIG = PluginConfigs::fromFCConfig;

	/** Where a file's content comes from - {@link #FCCONFIG}; tests replace it. Null content: absent. */
	static volatile Function<String, String> source = FCCONFIG;

	private PluginConfigs() {
	}

	/**
	 * Reads, parses and resolves {@code conf/<fileName>}; an absent file is an empty config.
	 *
	 * @throws PluginConfigException when it does not parse or a required substitution is unset
	 */
	static Read read(String fileName) throws PluginConfigException {
		String content;
		try {
			content = source.apply(fileName);
		} catch (RuntimeException e) {
			throw new PluginConfigException("conf/" + fileName + " cannot be read: " + e.getMessage());
		}
		if (null == content)
			return new Read(ConfigFactory.empty("conf/" + fileName + " (absent)"), true);
		return new Read(parse(fileName, content), false);
	}

	/**
	 * Parses and resolves a config's content as {@link #read} would - what {@code set-config} validates.
	 *
	 * @throws PluginConfigException when it does not parse or a required substitution is unset
	 */
	static Config parse(String fileName, String content) throws PluginConfigException {
		try {
			return ConfigFactory.parseString(content, ConfigParseOptions.defaults()
							.setSyntax(ConfigSyntax.CONF)
							.setOriginDescription("conf/" + fileName)
							.setAllowMissing(false))
					.resolve(ConfigResolveOptions.defaults());
		} catch (ConfigException e) {
			List<String> reasons = new ArrayList<>();
			reasons.add(e.getMessage());
			throw new PluginConfigException(reasons);
		}
	}

	private static String fromFCConfig(String fileName) {
		// FCConfig.getConfigInputStream signals "nowhere on disk or classpath" by throwing, not by returning null
		InputStream is;
		try {
			is = FCConfig.getConfigInputStream(fileName);
		} catch (RuntimeException e) {
			return null;
		}
		if (null == is)
			return null;
		try (InputStream in = is) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}
}
