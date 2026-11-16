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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A plugin's {@value #RESOURCE}: what it is, which class to instantiate, which API it was built against and which
 * kinds it declares. Read whole or refused whole - every reason a manifest is refused is collected, so an author
 * sees them all at once.
 *
 * <pre>
 * id = alerts
 * class = com.example.frontcache.AlertsPlugin
 * name = Frontcache Alerts
 * version = 1.0.0
 * vendor = Example
 * license = Apache-2.0
 * plugin-api = 1
 * kinds = observe, manage
 * config = alerts.conf
 * </pre>
 *
 * A plugin that serves {@code /fc-x/<id>/} ({@link PluginKind#SERVE}) may also name the cookies it reads and sets,
 * and the challenge providers it offers to the core's {@code challenge} guard action:
 *
 * <pre>
 * kinds = serve, observe
 * cookies = fc_pass
 * challenges = pow, turnstile
 * </pre>
 *
 * A {@code .properties} file has no trailing comments: everything after {@code =} is the value.
 */
public final class PluginManifest {

	/** Where a plugin's jar carries its manifest. */
	public static final String RESOURCE = "META-INF/frontcache-plugin.properties";

	/** The major version of this API. A manifest names the major it was built against in {@code plugin-api}. */
	public static final int PLUGIN_API = 1;

	/** The id: the config file's name, the {@code /fc-x/<id>/} path, a metric label. */
	public static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,30}");

	private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

	/** A cookie name a serving plugin may read and set: an RFC 6265 token, short. */
	public static final Pattern COOKIE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");

	/** A challenge provider's name, as a {@code challenge <provider>} guard action names it. */
	public static final Pattern PROVIDER = Pattern.compile("[a-z][a-z0-9-]{0,30}");

	/** A plain HOCON file name under {@code conf/}: no directory, ends in {@code .conf}. */
	private static final Pattern CONFIG_FILE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,58}\\.conf");

	private static final Set<String> KEYS = Set.of("id", "class", "name", "version", "vendor", "license",
			"plugin-api", "kinds", "config", "cookies", "challenges");

	private final String id;
	private final String entryClass;
	private final String name;
	private final String version;
	private final String vendor;
	private final String license;
	private final int pluginApi;
	private final Set<PluginKind> kinds;
	private final String config;
	private final Set<String> cookies;
	private final Set<String> challenges;
	private final String source;
	private final List<String> warnings;

	private PluginManifest(String id, String entryClass, String name, String version, String vendor,
			String license, int pluginApi, Set<PluginKind> kinds, String config, Set<String> cookies,
			Set<String> challenges, String source, List<String> warnings) {
		this.id = id;
		this.entryClass = entryClass;
		this.name = name;
		this.version = version;
		this.vendor = vendor;
		this.license = license;
		this.pluginApi = pluginApi;
		this.kinds = Collections.unmodifiableSet(kinds);
		this.config = config;
		this.cookies = Collections.unmodifiableSet(cookies);
		this.challenges = Collections.unmodifiableSet(challenges);
		this.source = source;
		this.warnings = Collections.unmodifiableList(warnings);
	}

	/**
	 * @param source where the manifest was read - a URL, for messages only
	 * @throws IllegalArgumentException naming every reason the manifest is refused
	 */
	public static PluginManifest parse(Properties properties, String source) {

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		String id = value(properties, "id");
		if (null == id)
			errors.add("'id' is missing");
		else if (!ID.matcher(id).matches())
			errors.add("'id' must match " + ID.pattern() + ": '" + id + "'");

		String entryClass = value(properties, "class");
		if (null == entryClass)
			errors.add("'class' is missing - the plugin's entry class, implementing " + FrontcachePlugin.class.getName());
		else if (!CLASS_NAME.matcher(entryClass).matches())
			errors.add("'class' is not a class name: '" + entryClass + "'");

		String version = value(properties, "version");
		if (null == version)
			errors.add("'version' is missing");

		int pluginApi = 0;
		String api = value(properties, "plugin-api");
		if (null == api)
			errors.add("'plugin-api' is missing - the API major the plugin was built against (this API is "
					+ PLUGIN_API + ")");
		else {
			try {
				pluginApi = Integer.parseInt(api);
				if (pluginApi < 1)
					errors.add("'plugin-api' must be a positive major version: '" + api + "'");
			} catch (NumberFormatException e) {
				errors.add("'plugin-api' must be a major version number: '" + api + "'");
			}
		}

		Set<PluginKind> kinds = EnumSet.noneOf(PluginKind.class);
		String kindList = value(properties, "kinds");
		if (null == kindList)
			errors.add("'kinds' is missing - one or more of " + kindNames());
		else {
			for (String k : kindList.split(","))
			{
				if (k.isBlank())
					continue;
				PluginKind kind = PluginKind.fromManifestName(k);
				if (null == kind)
					errors.add("'kinds' names an unknown kind '" + k.trim() + "' - known: " + kindNames());
				else
					kinds.add(kind);
			}
			if (kinds.isEmpty() && errors.stream().noneMatch(e -> e.startsWith("'kinds'")))
				errors.add("'kinds' is empty - one or more of " + kindNames());
		}

		String config = value(properties, "config");
		if (null != config && !CONFIG_FILE.matcher(config).matches())
			errors.add("'config' must be a plain .conf file name under conf/ (HOCON): '" + config + "'");

		Set<String> cookies = names(properties, "cookies", COOKIE_NAME, errors);
		Set<String> challenges = names(properties, "challenges", PROVIDER, errors);
		if (!kinds.contains(PluginKind.SERVE))
		{
			if (!cookies.isEmpty())
				errors.add("'cookies' needs kind 'serve' - only a plugin serving /fc-x/<id>/ reads or sets a cookie");
			if (!challenges.isEmpty())
				errors.add("'challenges' needs kind 'serve' - a challenge is a page the plugin serves");
		}

		Set<String> unknown = new TreeSet<>();
		for (String key : properties.stringPropertyNames())
			if (!KEYS.contains(key))
				unknown.add(key);
		if (!unknown.isEmpty())
			warnings.add("unknown key(s) ignored: " + String.join(", ", unknown));

		if (!errors.isEmpty())
			throw new IllegalArgumentException("Plugin manifest " + source + " is refused: " + String.join("; ", errors));

		String name = value(properties, "name");
		return new PluginManifest(id, entryClass, (null == name) ? id : name, version, value(properties, "vendor"),
				value(properties, "license"), pluginApi, kinds, config, cookies, challenges, source, warnings);
	}

	/** A comma-separated list of names, each checked against {@code pattern}; empty when the key is absent. */
	private static Set<String> names(Properties properties, String key, Pattern pattern, List<String> errors) {
		Set<String> names = new java.util.LinkedHashSet<>();
		String list = value(properties, key);
		if (null == list)
			return names;
		for (String n : list.split(","))
		{
			String name = n.trim();
			if (name.isEmpty())
				continue;
			if (!pattern.matcher(name).matches())
				errors.add("'" + key + "' names '" + name + "', which does not match " + pattern.pattern());
			else
				names.add(name);
		}
		return names;
	}

	/** The manifest a pre-plugin {@code FrontcacheModule} is given: id, class and {@code manage}, nothing else. */
	public static PluginManifest forLegacyModule(String id, String entryClass, String version, String source) {
		return new PluginManifest(id, entryClass, id, version, null, null, PLUGIN_API,
				EnumSet.of(PluginKind.MANAGE), null, Set.of(), Set.of(), source, List.of());
	}

	private static String value(Properties properties, String key) {
		String v = properties.getProperty(key);
		if (null == v)
			return null;
		v = v.trim();
		return v.isEmpty() ? null : v;
	}

	private static String kindNames() {
		List<String> names = new ArrayList<>();
		for (PluginKind kind : PluginKind.values())
			names.add(kind.manifestName());
		return String.join(", ", names);
	}

	public String id() {
		return id;
	}

	/** The entry class, implementing {@link FrontcachePlugin}. */
	public String entryClass() {
		return entryClass;
	}

	public String name() {
		return name;
	}

	public String version() {
		return version;
	}

	/** May be null. */
	public String vendor() {
		return vendor;
	}

	/** An SPDX identifier, as the plugin states it; may be null. */
	public String license() {
		return license;
	}

	public int pluginApi() {
		return pluginApi;
	}

	public Set<PluginKind> kinds() {
		return kinds;
	}

	public boolean declares(PluginKind kind) {
		return kinds.contains(kind);
	}

	/** Whether any declared kind runs on the request path. */
	public boolean isRequestPath() {
		for (PluginKind kind : kinds)
			if (kind.isRequestPath())
				return true;
		return false;
	}

	/** The plugin's config file under {@code conf/} - a plain {@code .conf} name, HOCON - or null. */
	public String config() {
		return config;
	}

	/**
	 * {@link PluginKind#SERVE}: the cookies the plugin may read on its own requests and set on its own responses - and
	 * the only names the node's evidence signer signs for it. Empty when it names none.
	 */
	public Set<String> cookies() {
		return cookies;
	}

	/**
	 * {@link PluginKind#SERVE}: the challenge providers this plugin offers - what a {@code challenge <provider>} guard
	 * rule names. Empty when it offers none.
	 */
	public Set<String> challenges() {
		return challenges;
	}

	/** Where the manifest was read. */
	public String source() {
		return source;
	}

	/** What was accepted but is worth a WARN (unknown keys). */
	public List<String> warnings() {
		return warnings;
	}

	/** The kinds as a manifest spells them, in declaration order: {@code observe, manage}. */
	public String kindList() {
		List<String> names = new ArrayList<>();
		for (PluginKind kind : kinds)
			names.add(kind.manifestName());
		return String.join(", ", names);
	}

	@Override
	public String toString() {
		return id + " " + version + " (" + kindList() + "; plugin-api " + pluginApi
				+ ((null == license) ? "" : "; " + license) + ")";
	}
}
