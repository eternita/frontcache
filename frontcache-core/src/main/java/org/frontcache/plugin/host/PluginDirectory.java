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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.frontcache.FCConfig;
import org.frontcache.plugin.NodeMode;
import org.frontcache.plugin.PluginManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dropped-in plugins: {@code <plugin directory>/<id>/*.jar}, each loaded by a {@link PluginClassLoader} of its own.
 *
 * <h2>Where</h2>
 *
 * {@value #DIR_PROPERTY} (a system property) or {@code $}{@value #DIR_ENV} when set - in any mode; otherwise
 * {@code FRONTCACHE_HOME/plugins} in server mode and <b>none in filter mode</b>: a host application may not want
 * Frontcache loading jars from a directory, so it says so explicitly. A filter is initialised when its application starts,
 * before any request, so a node whose mode is still unknown when the plugins are found is a server.
 *
 * <h2>Enabled explicitly</h2>
 *
 * A directory is loaded only when {@value #ENABLED_PROPERTY} lists it; one holding a jar that is not listed is reported
 * ({@code not-enabled}) and nothing of it is read - a jar landing in a directory never changes behaviour by itself. A
 * directory holding no jar is not a plugin's (a bundled plugin's {@code data/} lives beside them) and is ignored.
 *
 * <h2>Pinned, optionally</h2>
 *
 * {@code front-cache.plugins.<id>.sha256} - the SHA-256 of each of its jars, comma-separated (one, for a shaded jar) -
 * refuses the plugin when the set of its jars' digests differs.
 */
final class PluginDirectory {

	private static final Logger logger = LoggerFactory.getLogger(PluginDirectory.class);

	/** A system property naming the plugin directory - in any mode. */
	static final String DIR_PROPERTY = "frontcache.plugins.dir";

	/** An environment variable naming the plugin directory - in any mode. */
	static final String DIR_ENV = "FRONTCACHE_PLUGINS";

	/** {@code front-cache.plugins = geoip, debug}: the dropped-in plugins to load. */
	static final String ENABLED_PROPERTY = "front-cache.plugins";

	/** {@code front-cache.plugins.<id>.sha256}. */
	static final String PIN_PROPERTY = "front-cache.plugins.%s.sha256";

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private PluginDirectory() {
	}

	/** The plugin directory a node in this mode reads, or null when it reads none. */
	static File directory(NodeMode mode) {
		String explicit = System.getProperty(DIR_PROPERTY);
		if (null == explicit || explicit.isBlank())
			explicit = System.getenv(DIR_ENV);
		if (null != explicit && !explicit.isBlank())
			return new File(explicit.trim());
		if (NodeMode.FILTER == mode)
			return null;
		String home = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		if (null == home || home.isBlank())
			home = System.getenv(FCConfig.FRONT_CACHE_HOME_ENVIRONMENT_KEY);
		return (null == home || home.isBlank()) ? null : new File(home.trim(), "plugins");
	}

	/** Whether the directory came from the default rather than an explicit setting. */
	static boolean isDefault() {
		String explicit = System.getProperty(DIR_PROPERTY);
		if (null == explicit || explicit.isBlank())
			explicit = System.getenv(DIR_ENV);
		return null == explicit || explicit.isBlank();
	}

	/** {@value #ENABLED_PROPERTY}, in order; a name that is not an id is a WARN and skipped. */
	static Set<String> enabled() {
		Set<String> ids = new LinkedHashSet<>();
		String value = FCConfig.getProperty(ENABLED_PROPERTY);
		if (null == value)
			return ids;
		for (String part : value.split("[,\\s]+")) {
			String id = part.trim();
			if (id.isEmpty())
				continue;
			if (PluginManifest.ID.matcher(id).matches())
				ids.add(id);
			else
				logger.warn("{} names '{}', which is not a plugin id ({}) - ignored", ENABLED_PROPERTY, id,
						PluginManifest.ID.pattern());
		}
		return ids;
	}

	/**
	 * Finds the dropped-in plugins.
	 *
	 * @param dir     the plugin directory; null for none
	 * @param core    the core's class loader - every plugin loader's API parent
	 * @param taken   the slots found so far (the bundled plugins): their ids and configs are taken
	 * @param enabled {@value #ENABLED_PROPERTY}
	 */
	static List<FrontcachePlugins.Slot> scan(File dir, ClassLoader core, List<FrontcachePlugins.Slot> taken, Set<String> enabled) {

		Set<String> ids = new java.util.HashSet<>();
		Set<String> configs = new java.util.HashSet<>();
		for (FrontcachePlugins.Slot slot : taken) {
			ids.add(slot.id);
			if (null != slot.manifest && null != slot.manifest.config())
				configs.add(slot.manifest.config());
		}

		List<FrontcachePlugins.Slot> slots = new ArrayList<>();
		Set<String> wanted = new LinkedHashSet<>(enabled);
		for (String id : enabled)
			if (ids.contains(id)) {
				logger.info("{} lists '{}', a bundled plugin - a bundled plugin is always loaded and needs no listing",
						ENABLED_PROPERTY, id);
				wanted.remove(id);
			}

		File[] children = (null == dir) ? null : dir.listFiles(File::isDirectory);
		if (null == children)
			children = new File[0];
		Arrays.sort(children, Comparator.comparing(File::getName));

		for (File child : children) {
			String id = child.getName();
			File[] jars = jars(child);
			if (0 == jars.length) {
				if (wanted.contains(id)) {
					slots.add(refused(id, child, "front-cache.plugins lists it, but " + where(child) + " holds no jar"));
					wanted.remove(id);
				}
				continue; // not a plugin's directory - a bundled plugin's data/ lives here too
			}
			if (!PluginManifest.ID.matcher(id).matches()) {
				logger.warn("Plugin directory {} is not named by a plugin id ({}) - not loaded", where(child),
						PluginManifest.ID.pattern());
				continue;
			}
			if (ids.contains(id)) {
				logger.warn("Plugin directory {} has the id of a bundled plugin, which wins - not loaded", where(child));
				continue;
			}
			if (!wanted.remove(id)) {
				FrontcachePlugins.Slot slot = new FrontcachePlugins.Slot(id, null, null, false,
						FrontcachePlugins.State.NOT_ENABLED, "found in " + where(child) + "; not listed in " + ENABLED_PROPERTY);
				slot.droppedIn(where(child), digests(jars), false);
				slots.add(slot);
				logger.info("Plugin '{}' found in {}, not enabled - add it to {} to load it", id, where(child), ENABLED_PROPERTY);
				continue;
			}
			FrontcachePlugins.Slot slot = load(id, child, jars, core, ids, configs);
			slots.add(slot);
			if (null != slot.manifest && !slot.refused()) {
				ids.add(slot.id);
				if (null != slot.manifest.config())
					configs.add(slot.manifest.config());
			}
		}

		for (String id : wanted)
			slots.add(refused(id, (null == dir) ? null : new File(dir, id), (null == dir)
					? "front-cache.plugins lists it, but this node reads no plugin directory (in filter mode, set -D"
							+ DIR_PROPERTY + " or $" + DIR_ENV + ")"
					: "front-cache.plugins lists it, but there is no directory " + where(new File(dir, id))));

		return slots;
	}

	private static FrontcachePlugins.Slot load(String id, File dir, File[] jars, ClassLoader core, Set<String> ids,
			Set<String> configs) {

		List<String> digests = digests(jars);
		String pin = FCConfig.getProperty(String.format(PIN_PROPERTY, id));
		boolean pinned = null != pin && !pin.isBlank();
		if (pinned) {
			String mismatch = checkPin(String.format(PIN_PROPERTY, id), pin, jars, digests);
			if (null != mismatch) {
				FrontcachePlugins.Slot slot = refused(id, dir, mismatch);
				slot.droppedIn(where(dir), digests, false);
				return slot;
			}
		}

		URL[] urls = new URL[jars.length];
		try {
			for (int i = 0; i < jars.length; i++)
				urls[i] = jars[i].toURI().toURL();
		} catch (MalformedURLException e) {
			return refused(id, dir, "a jar's path is not a URL: " + e.getMessage());
		}

		PluginClassLoader loader = new PluginClassLoader(id, urls, core);
		List<URL> manifests;
		try {
			manifests = Collections.list(loader.findResources(PluginManifest.RESOURCE));
		} catch (IOException e) {
			close(loader);
			return refused(id, dir, "can't list its jars: " + e);
		}

		String refusal = null;
		if (manifests.isEmpty())
			refusal = "its jars carry no " + PluginManifest.RESOURCE;
		else if (1 < manifests.size())
			refusal = "its jars carry " + manifests.size() + " manifests (" + PluginManifest.RESOURCE
					+ ") - one plugin per directory";
		FrontcachePlugins.Slot slot = (null != refusal) ? refused(id, dir, refusal)
				: FrontcachePlugins.fromManifest(manifests.get(0), loader, ids, configs);

		if (!slot.refused() && !id.equals(slot.id))
			slot = refused(id, dir, "its manifest's id is '" + slot.id + "' - the directory must be named by it");
		slot.droppedIn(where(dir), digests, pinned);
		if (slot.refused())
			close(loader);
		return slot;
	}

	/** @return why the jars do not match the pin; null when they do */
	private static String checkPin(String property, String pin, File[] jars, List<String> digests) {
		Set<String> pinnedDigests = new TreeSet<>();
		for (String part : pin.split("[,\\s]+")) {
			String d = part.trim().toLowerCase(Locale.ROOT);
			if (d.isEmpty())
				continue;
			if (!SHA256.matcher(d).matches())
				return property + " holds '" + part.trim() + "', which is not a SHA-256 (64 hex digits)";
			pinnedDigests.add(d);
		}
		List<String> unpinned = new ArrayList<>();
		Set<String> actual = new TreeSet<>();
		for (int i = 0; i < jars.length; i++) {
			String digest = digests.get(i).substring(digests.get(i).lastIndexOf(' ') + 1);
			actual.add(digest);
			if (!pinnedDigests.contains(digest))
				unpinned.add(jars[i].getName() + " is " + digest);
		}
		if (unpinned.isEmpty() && actual.equals(pinnedDigests))
			return null;
		Set<String> missing = new TreeSet<>(pinnedDigests);
		missing.removeAll(actual);
		return "its jars differ from " + property + (unpinned.isEmpty() ? "" : ": " + String.join(", ", unpinned))
				+ (missing.isEmpty() ? "" : (unpinned.isEmpty() ? ": " : "; ") + "no jar has " + String.join(", ", missing));
	}

	private static FrontcachePlugins.Slot refused(String id, File dir, String reason) {
		FrontcachePlugins.Slot slot = new FrontcachePlugins.Slot(id, null, null, false, FrontcachePlugins.State.REFUSED, reason);
		if (null != dir)
			slot.droppedIn(where(dir), List.of(), false);
		return slot;
	}

	/** The top-level {@code *.jar} files of a plugin's directory, by name. */
	static File[] jars(File dir) {
		File[] jars = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
		if (null == jars)
			return new File[0];
		Arrays.sort(jars, Comparator.comparing(File::getName));
		return jars;
	}

	/** Each jar as {@code <file name> <sha256>}. */
	static List<String> digests(File[] jars) {
		List<String> digests = new ArrayList<>();
		for (File jar : jars)
			digests.add(jar.getName() + " " + sha256(jar));
		return digests;
	}

	static String sha256(File file) {
		try (InputStream in = Files.newInputStream(file.toPath())) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			int n;
			while (-1 != (n = in.read(buffer)))
				digest.update(buffer, 0, n);
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			return "unreadable";
		}
	}

	/** {@code plugins/<id>} - the directory's parent's name and its own, for a message or a stat. */
	static String where(File dir) {
		File parent = dir.getParentFile();
		return ((null == parent) ? "" : parent.getName() + "/") + dir.getName();
	}

	private static void close(PluginClassLoader loader) {
		try {
			loader.close();
		} catch (IOException e) {
			// nothing to do
		}
	}
}
