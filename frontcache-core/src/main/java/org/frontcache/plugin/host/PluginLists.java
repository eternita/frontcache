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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.frontcache.FCConfig;
import org.frontcache.core.Cidr;
import org.frontcache.guard.IpLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code PluginContext.writeList}: a plugin replaces {@code conf/lists/<name>.txt}, which a core
 * {@code client-ip:@lists/<name>.txt} rule reads ({@link IpLists}). The core decides; the plugin is a faster operator.
 *
 * <ul>
 * <li><b>Written whole or refused whole</b>: every entry is checked before anything is written.</li>
 * <li><b>Atomic</b>: a temporary file beside it, then a move - a rule load never reads half a list.</li>
 * <li><b>Owned</b>: the file starts with {@code # managed by plugin <id>}; one without the line (an operator's) or with
 *     another plugin's is never overwritten.</li>
 * </ul>
 */
final class PluginLists {

	private static final Logger logger = LoggerFactory.getLogger(PluginLists.class);

	static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	static final int MAX_LINES = 100_000;

	private static final Object LOCK = new Object();

	private PluginLists() {
	}

	static String marker(String plugin) {
		return "# managed by plugin " + plugin;
	}

	/** @return the number of address blocks the list now holds */
	static int write(String plugin, String name, List<String> lines) throws IOException {

		if (null == name || !NAME.matcher(name).matches() || name.contains(".."))
			throw new IllegalArgumentException("'" + name + "' is not a list name - lower case letters, digits, '.', '_'"
					+ " and '-', starting with a letter or digit, at most 64 characters (.txt is added)");
		List<String> entries = (null == lines) ? List.of() : lines;
		if (entries.size() > MAX_LINES)
			throw new IllegalArgumentException("a list holds at most " + MAX_LINES + " lines, not " + entries.size());

		List<Cidr> blocks = new ArrayList<>();
		List<String> bad = new ArrayList<>();
		int badCount = 0;
		StringBuilder content = new StringBuilder(marker(plugin)).append('\n');
		for (String line : entries) {
			String text = (null == line) ? "" : line;
			if (-1 < text.indexOf('\n') || -1 < text.indexOf('\r'))
				throw new IllegalArgumentException("an entry of a list is one line - one holds a line break");
			String trimmed = text.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				Cidr block = Cidr.parse(trimmed);
				if (null == block) {
					badCount++;
					if (bad.size() < 10)
						bad.add("'" + (trimmed.length() > 64 ? trimmed.substring(0, 64) + "..." : trimmed) + "'");
					continue;
				}
				blocks.add(block);
			}
			content.append(text).append('\n');
		}
		if (0 < badCount)
			throw new IllegalArgumentException(badCount + " entr" + (1 == badCount ? "y is" : "ies are")
					+ " not an address or CIDR block - the list keeps what it had: " + String.join(", ", bad)
					+ (badCount > bad.size() ? ", ..." : ""));

		String relative = IpLists.DIRECTORY + name + ".txt";
		File file = FCConfig.getWritableConfigFile(relative);
		if (null == file)
			throw new IOException("this node has no FRONTCACHE_HOME/conf directory to write conf/" + relative + " in");

		synchronized (LOCK) {
			File dir = file.getParentFile();
			if (!dir.isDirectory() && !dir.mkdirs())
				throw new IOException("can't create conf/" + IpLists.DIRECTORY);

			if (file.exists()) {
				String first = firstLine(file);
				if (!marker(plugin).equals(first))
					throw new IOException("conf/" + relative + " is not managed by plugin '" + plugin + "' - its first line is"
							+ " not '" + marker(plugin) + "', so it is "
							+ ((null != first && first.startsWith("# managed by plugin ")) ? "another plugin's" : "an operator's")
							+ " and is never overwritten");
			}

			Path tmp = Files.createTempFile(dir.toPath(), "." + name + ".", ".tmp");
			try {
				Files.writeString(tmp, content, StandardCharsets.UTF_8);
				try {
					Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
			IpLists.publish(relative, blocks, file);
		}

		logger.info("Plugin '{}' wrote conf/{}: {} address block(s)", plugin, relative, blocks.size());
		CoreEvents.configReloaded(relative, true, blocks.size(), List.of());
		return blocks.size();
	}

	private static String firstLine(File file) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			String line = reader.readLine();
			return (null == line) ? null : line.trim();
		}
	}
}
