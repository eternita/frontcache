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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads, hashes, backs up and atomically replaces a config file under FRONTCACHE_HOME/conf.
 *
 * Separate from {@link FrontCacheIOServlet} because every step in here has a way of being silently
 * wrong, and none of them is about HTTP.
 */
public class ConfigFileStore {

	private static final Logger logger = LoggerFactory.getLogger(ConfigFileStore.class);

	/**
	 * How many timestamped backups to keep per file.
	 *
	 * Enough to undo a bad afternoon, few enough that conf/ does not become a directory of history.
	 * An unbounded pile would be its own operational problem, and the ones that matter are the recent
	 * ones.
	 */
	static final int BACKUPS_KEPT = 10;

	private static final String BACKUP_INFIX = ".backup-";

	private ConfigFileStore() { }

	/**
	 * Makes submitted text safe to compare and to write.
	 *
	 * <h2>CRLF is the one that bites</h2>
	 *
	 * <b>HTML form submission normalizes a textarea's line breaks to CRLF.</b> Every config file in
	 * this project is LF, so without this the very first Save - even with no edit at all - rewrites
	 * every line ending in the file: {@code git diff} on a version-controlled FRONTCACHE_HOME shows
	 * the whole file changed, and the next content hash fails to match text the console itself just
	 * posted.
	 *
	 * <h2>The trailing newline is deliberately left alone</h2>
	 *
	 * Tempting to add one - conf/bots.conf on the e2e standalone node ends without it - and wrong:
	 * a browser does not add a trailing newline to a textarea's value, so adding one here would make
	 * "save the file unchanged" change the file. Saving must not alter a byte the operator did not
	 * alter, both because that is the promise the button makes and because it is what lets the e2e
	 * suite assert an exact round trip.
	 */
	public static String normalize(String content)
	{
		if (null == content)
			return "";

		// \r\n first, then a lone \r - a classic-Mac paste is rare but costs nothing to handle, and
		// doing them in the other order would turn every CRLF into two newlines.
		return content.replace("\r\n", "\n").replace("\r", "\n");
	}

	/** Lowercase hex SHA-256 of the normalized text - the {@code baseHash} the console sends. */
	public static String hash(String content)
	{
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(normalize(content).getBytes(StandardCharsets.UTF_8));

			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest)
				hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));

			return hex.toString();

		} catch (Exception e) {
			// SHA-256 is required of every JVM; if it is genuinely missing, a hash nobody can match is
			// the safe answer - it refuses writes rather than accepting them unchecked.
			logger.error("Can't compute SHA-256", e);
			return "";
		}
	}

	/**
	 * The file's current content, or "" when it does not exist yet.
	 *
	 * Deliberately NOT {@code FCConfig.getConfigInputStream}: that falls back to the classpath, and
	 * what a write has to be compared against is the file on disk it is about to replace. Comparing
	 * against a bundled default would let a concurrent edit through.
	 */
	public static String readOnDisk(File file) throws IOException
	{
		if (null == file || !file.exists())
			return "";

		return normalize(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	/**
	 * Copies the current file aside as {@code <name>.backup-<yyyyMMdd_HHmmss>}, the naming
	 * {@code backup-index} already uses for the Lucene index.
	 *
	 * @return the backup's file name, or null when there was no file to back up
	 */
	public static String backup(File file) throws IOException
	{
		if (!file.exists())
			return null;

		String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		File backup = new File(file.getParentFile(), file.getName() + BACKUP_INFIX + stamp);

		Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);

		pruneBackups(file);

		return backup.getName();
	}

	/** Keeps the newest {@link #BACKUPS_KEPT} backups of one file and deletes the rest. */
	private static void pruneBackups(File file)
	{
		File[] backups = file.getParentFile().listFiles((dir, name) -> name.startsWith(file.getName() + BACKUP_INFIX));

		if (null == backups || backups.length <= BACKUPS_KEPT)
			return;

		// By name, which for this stamp format is chronological - and unlike lastModified() it cannot
		// be perturbed by a copy or a restore from an archive.
		List<File> sorted = new ArrayList<File>(Arrays.asList(backups));
		sorted.sort(Comparator.comparing(File::getName).reversed());

		for (File stale : sorted.subList(BACKUPS_KEPT, sorted.size()))
			if (!stale.delete())
				logger.warn("Can't delete stale config backup " + stale.getAbsolutePath());
	}

	/**
	 * Replaces the file's content atomically.
	 *
	 * <h2>Why not just write the file</h2>
	 *
	 * A plain write leaves a window in which the file on disk is truncated, and something reads config
	 * files at unpredictable moments - a concurrent {@code get-*-config}, a concurrent reload, the
	 * node's own startup. A truncated conf/guard-rules.conf is a node with no guard rules, which is an
	 * outage that lasts until someone reloads it again.
	 *
	 * The temp file is created in the <b>same directory</b> as the target on purpose:
	 * {@code ATOMIC_MOVE} across filesystems throws, and /tmp on a container is routinely a different
	 * one.
	 */
	public static void write(File file, String content) throws IOException
	{
		File parent = file.getParentFile();
		Path temp = Files.createTempFile(parent.toPath(), file.getName() + ".", ".tmp");

		try {
			Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
			Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

		} finally {
			// Only reached when the move did not happen; once it has, the temp path is gone.
			Files.deleteIfExists(temp);
		}
	}
}
