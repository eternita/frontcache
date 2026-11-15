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
package org.frontcache.plugin.testkit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code PluginContext.writeList} with the rules its javadoc states - the testkit's copy, so a plugin's test meets
 * every refusal the node would give it: the name, the entries (written whole or refused whole), the marker, the
 * limit, the atomic replace.
 */
public final class ManagedLists {

	/** A list's name, {@code .txt} added. */
	public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	/** The most lines one list may have. */
	public static final int MAX_LINES = 100_000;

	/** What a list a plugin manages starts with, followed by the plugin's id. */
	public static final String MARKER = "# managed by plugin ";

	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
	private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");
	private static final Pattern PREFIX = Pattern.compile("\\d{1,3}");
	private static final int REPORTED = 10;

	private ManagedLists() {
	}

	/** {@code conf/lists/<name>.txt} under {@code home}. */
	public static File file(File home, String name) {
		return new File(new File(new File(home, "conf"), "lists"), name + ".txt");
	}

	/**
	 * @return how many address blocks the list now holds
	 * @throws IllegalArgumentException naming the entries that are not addresses, or a name that is not one
	 * @throws IOException when the file cannot be written, belongs to someone else, or there is no home
	 */
	public static synchronized int write(File home, String pluginId, String name, List<String> lines)
			throws IOException {

		if (null == name || !NAME.matcher(name).matches())
			throw new IllegalArgumentException("list name '" + name + "' must match " + NAME.pattern()
					+ " (.txt is added)");
		if (null == lines)
			throw new IllegalArgumentException("lines is null");
		if (lines.size() > MAX_LINES)
			throw new IllegalArgumentException("list '" + name + "' has " + lines.size() + " lines - at most "
					+ MAX_LINES);

		int blocks = 0;
		List<String> bad = new ArrayList<>();
		int badCount = 0;
		for (int i = 0; i < lines.size(); i++)
		{
			String line = lines.get(i);
			String problem = null;
			if (null == line)
				problem = "line " + (i + 1) + ": null";
			else if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)
				problem = "line " + (i + 1) + ": a line break inside an entry";
			else
			{
				String entry = line.strip();
				if (entry.isEmpty() || entry.startsWith("#"))
					continue;
				if (isBlock(entry))
					blocks++;
				else
					problem = "'" + entry + "'";
			}
			if (null != problem)
			{
				badCount++;
				if (bad.size() < REPORTED)
					bad.add(problem);
			}
		}
		if (0 < badCount)
			throw new IllegalArgumentException("list '" + name + "' is refused whole: " + badCount
					+ ((1 == badCount) ? " entry is" : " entries are") + " not an address or a CIDR block: "
					+ String.join(", ", bad) + ((badCount > bad.size()) ? ", ..." : ""));

		if (null == home)
			throw new IOException("the node has no FRONTCACHE_HOME - list '" + name + "' cannot be written");

		Path file = file(home, name).toPath();
		Path dir = file.getParent();
		Files.createDirectories(dir);

		String marker = MARKER + pluginId;
		if (Files.exists(file))
		{
			String first;
			try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				first = reader.readLine();
			}
			first = (null == first) ? "" : first.strip();
			if (!marker.equals(first))
			{
				if (first.startsWith(MARKER))
					throw new IOException("conf/lists/" + name + ".txt is managed by plugin '"
							+ first.substring(MARKER.length()).strip() + "' - never overwritten by another");
				throw new IOException("conf/lists/" + name + ".txt was not written by a plugin (its first line is not '"
						+ MARKER + "<id>') - an operator's list is never overwritten");
			}
		}

		StringBuilder text = new StringBuilder(marker).append('\n');
		for (String line : lines)
			text.append(line).append('\n');

		Path temp = Files.createTempFile(dir, "." + name + ".", ".tmp");
		try {
			Files.writeString(temp, text, StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
		return blocks;
	}

	/**
	 * Whether the text is one address or one block - an IPv4 dotted quad or an IPv6 literal, optionally
	 * {@code /<prefix>} within the family's bits. Never a host name, a port, brackets or a zone: parsed as a literal,
	 * never looked up.
	 */
	public static boolean isBlock(String text) {
		if (null == text || text.isEmpty())
			return false;
		String address = text;
		int bits = -1;
		int slash = text.indexOf('/');
		if (slash >= 0)
		{
			String prefix = text.substring(slash + 1);
			if (!PREFIX.matcher(prefix).matches())
				return false;
			bits = Integer.parseInt(prefix);
			address = text.substring(0, slash);
		}
		boolean v6 = address.indexOf(':') >= 0;
		if (v6 ? !IPV6.matcher(address).matches() : !IPV4.matcher(address).matches())
			return false;
		InetAddress parsed;
		try {
			parsed = InetAddress.ofLiteral(address);
		} catch (IllegalArgumentException e) {
			return false;
		}
		// the core's Cidr: the family's bits are the parsed address's (an IPv4-mapped IPv6 literal is IPv4)
		return bits <= parsed.getAddress().length * 8;
	}
}
