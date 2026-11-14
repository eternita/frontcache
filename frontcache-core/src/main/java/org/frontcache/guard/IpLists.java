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
package org.frontcache.guard;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.frontcache.FCConfig;
import org.frontcache.core.Cidr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The address lists under {@code conf/lists/} - the ones that change while the node runs. A {@code client-ip:@lists/...}
 * predicate reads its list through here rather than copying it at load: a plugin's {@code PluginContext.writeList}
 * replaces the list in place, and every rule naming it - in guard-rules.conf and bots.conf alike - sees the new
 * content at once, with no reload.
 *
 * <p>Two things differ from any other {@code client-ip:@file}, because a list here is somebody's moving output: a
 * missing file is an <b>empty list</b> (a WARN, not a refused rule - the plugin that writes it may not have run yet),
 * and an empty list is legal. A rule load re-reads a file whose size or modification time changed since it was read,
 * so an operator's own cron writing here is picked up by the next reload, as before.
 */
public final class IpLists {

	private static final Logger logger = LoggerFactory.getLogger(IpLists.class);

	/** The directory under {@code conf/}, with its slash: {@code @lists/banned.txt}. */
	public static final String DIRECTORY = "lists/";

	/** One list as the rules read it. */
	public static final class LiveList {

		private final String name;
		private volatile List<Cidr> blocks = List.of();
		private volatile long modified = -1;
		private volatile long size = -1;

		LiveList(String name) {
			this.name = name;
		}

		/** Whether an address is in one of the list's blocks; false for null. */
		public boolean contains(String address) {
			if (null == address)
				return false;
			for (Cidr block : blocks)
				if (block.contains(address))
					return true;
			return false;
		}

		public int size() {
			return blocks.size();
		}

		public String name() {
			return name;
		}
	}

	private static final ConcurrentHashMap<String, LiveList> LISTS = new ConcurrentHashMap<>();

	private IpLists() {
	}

	/** Whether a {@code @file} name is a live list. */
	public static boolean isLive(String name) {
		return null != name && name.startsWith(DIRECTORY);
	}

	/**
	 * The list a rule names, read from disk when it is new here or its file changed since it was read.
	 *
	 * @param name {@code lists/<file>}, already checked to be a plain path under conf/
	 */
	static LiveList get(String name, String expr) {
		LiveList list = LISTS.computeIfAbsent(name, LiveList::new);
		synchronized (list) {
			File file = FCConfig.getWritableConfigFile(name);
			if (null == file || !file.isFile()) {
				if (-1 != list.modified || list.blocks.isEmpty())
					logger.warn("conf/{} named by '{}' does not exist - an empty list until it is written", name, expr);
				return list;
			}
			if (file.lastModified() == list.modified && file.length() == list.size)
				return list;
			try {
				List<Cidr> blocks = read(file, name, expr);
				list.blocks = blocks;
				list.modified = file.lastModified();
				list.size = file.length();
				logger.info("conf/{} read: {} address block(s)", name, blocks.size());
			} catch (IOException e) {
				logger.error("Can't read conf/" + name + " named by '" + expr + "' - the list keeps what it held ("
						+ list.size() + " blocks)", e);
			}
			return list;
		}
	}

	/** {@code writeList}'s last step: the file was replaced, and this is what it now holds. */
	public static void publish(String name, List<Cidr> blocks, File file) {
		LiveList list = LISTS.computeIfAbsent(name, LiveList::new);
		synchronized (list) {
			list.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
			list.modified = file.lastModified();
			list.size = file.length();
		}
	}

	/** For tests: forgets every list. */
	static void clear() {
		LISTS.clear();
	}

	private static List<Cidr> read(File file, String name, String expr) throws IOException {
		List<Cidr> blocks = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			String line;
			int number = 0;
			while (null != (line = reader.readLine())) {
				number++;
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#"))
					continue;
				Cidr block = Cidr.parse(trimmed);
				if (null == block) {
					logger.error("Ignoring unparseable entry '" + trimmed + "' (conf/" + name + ":" + number + ") named by '"
							+ expr + "' - expected an address or address/prefix");
					continue;
				}
				blocks.add(block);
			}
		}
		return Collections.unmodifiableList(blocks);
	}
}
