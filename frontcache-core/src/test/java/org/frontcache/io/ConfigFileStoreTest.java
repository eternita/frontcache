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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The file mechanics behind {@code set-config}: normalization, hashing, backups and the atomic
 * replace. Every one of these is silent when it goes wrong, which is why they are tested apart from
 * the action.
 */
public class ConfigFileStoreTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	// ---- normalization ----------------------------------------------------------------------

	/**
	 * The regression this method exists for.
	 *
	 * HTML form submission normalizes a textarea's line breaks to CRLF, and every config file in this
	 * project is LF. Without normalization the first Save - even with no edit - rewrites every line
	 * ending in the file, and the next hash comparison fails against text the console itself posted.
	 */
	@Test
	public void crlfIsNormalizedToLf()
	{
		assertEquals("a\nb\nc\n", ConfigFileStore.normalize("a\r\nb\r\nc\r\n"));
	}

	/** A lone CR must not become two newlines, which is what normalizing in the other order does. */
	@Test
	public void loneCarriageReturnBecomesOneNewline()
	{
		assertEquals("a\nb", ConfigFileStore.normalize("a\rb"));
		assertEquals("a\nb", ConfigFileStore.normalize("a\r\nb"));
	}

	/**
	 * A trailing newline is deliberately NOT added.
	 *
	 * conf/bots.conf on the e2e standalone node ends without one, and a browser does not add one to a
	 * textarea's value - so adding it here would make "save the file unchanged" change the file. Saving
	 * must not alter a byte the operator did not alter.
	 */
	@Test
	public void aMissingTrailingNewlineIsLeftMissing()
	{
		assertEquals("Googlebot", ConfigFileStore.normalize("Googlebot"));
		assertEquals("Googlebot\n", ConfigFileStore.normalize("Googlebot\n"));
	}

	@Test
	public void nullContentNormalizesToEmpty()
	{
		assertEquals("", ConfigFileStore.normalize(null));
	}

	// ---- hashing ----------------------------------------------------------------------------

	/**
	 * The hash is taken of the NORMALIZED text on both sides, so a browser's CRLF round trip does not
	 * read as a phantom conflict.
	 */
	@Test
	public void hashIgnoresLineEndingStyle()
	{
		assertEquals(ConfigFileStore.hash("a\nb\n"), ConfigFileStore.hash("a\r\nb\r\n"));
	}

	@Test
	public void hashIsSha256Hex()
	{
		// sha256 of the empty string
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
				ConfigFileStore.hash(""));
	}

	@Test
	public void differentContentHashesDifferently()
	{
		assertFalse(ConfigFileStore.hash("a").equals(ConfigFileStore.hash("b")));
	}

	// ---- reading -----------------------------------------------------------------------------

	/**
	 * A file that is not there reads as empty, not as an exception: creating conf/guard-rules.conf is
	 * the normal first step for a node that has never used guard rules.
	 */
	@Test
	public void readingAnAbsentFileGivesEmpty() throws Exception
	{
		assertEquals("", ConfigFileStore.readOnDisk(new File(folder.getRoot(), "nope.conf")));
	}

	@Test
	public void readingNormalizesToo() throws Exception
	{
		File file = folder.newFile("x.conf");
		Files.write(file.toPath(), "a\r\nb".getBytes(StandardCharsets.UTF_8));

		assertEquals("a\nb", ConfigFileStore.readOnDisk(file));
	}

	// ---- writing -----------------------------------------------------------------------------

	@Test
	public void writeReplacesTheContent() throws Exception
	{
		File file = folder.newFile("x.conf");
		Files.write(file.toPath(), "old".getBytes(StandardCharsets.UTF_8));

		ConfigFileStore.write(file, "new");

		assertEquals("new", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void writeCreatesTheFileWhenItIsAbsent() throws Exception
	{
		File file = new File(folder.getRoot(), "fresh.conf");

		ConfigFileStore.write(file, "hello");

		assertTrue(file.exists());
		assertEquals("hello", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	/**
	 * The temp file must not survive.
	 *
	 * It is created in the target's own directory - ATOMIC_MOVE across filesystems throws, and /tmp in
	 * a container is routinely a different one - so a leaked one would sit in conf/ next to the real
	 * config files, where anything globbing the directory would find it.
	 */
	@Test
	public void writeLeavesNoTempFileBehind() throws Exception
	{
		File file = new File(folder.getRoot(), "x.conf");

		ConfigFileStore.write(file, "content");

		for (File left : folder.getRoot().listFiles())
			assertFalse("temp file left behind: " + left.getName(), left.getName().endsWith(".tmp"));
	}

	@Test
	public void writeHandlesUtf8() throws Exception
	{
		File file = new File(folder.getRoot(), "x.conf");

		ConfigFileStore.write(file, "# ботам сюда\nYandexBot\n");

		assertEquals("# ботам сюда\nYandexBot\n",
				new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	// ---- backups -----------------------------------------------------------------------------

	@Test
	public void backupHoldsThePreviousContent() throws Exception
	{
		File file = folder.newFile("bots.conf");
		Files.write(file.toPath(), "previous".getBytes(StandardCharsets.UTF_8));

		String backupName = ConfigFileStore.backup(file);
		ConfigFileStore.write(file, "current");

		assertNotNull(backupName);
		assertTrue("expected bots.conf.backup-<stamp>, got " + backupName,
				backupName.startsWith("bots.conf.backup-"));
		assertEquals("previous", new String(
				Files.readAllBytes(new File(folder.getRoot(), backupName).toPath()), StandardCharsets.UTF_8));
	}

	/** Nothing on disk means nothing to back up - and that is not an error, it is a first save. */
	@Test
	public void backupOfAnAbsentFileIsNull() throws Exception
	{
		assertNull(ConfigFileStore.backup(new File(folder.getRoot(), "nope.conf")));
	}

	/**
	 * conf/ must not become a directory of history. The newest are kept because those are the ones an
	 * operator undoing a change reaches for.
	 */
	@Test
	public void onlyTheNewestBackupsAreKept() throws Exception
	{
		File file = folder.newFile("bots.conf");

		// Written directly rather than by calling backup() in a loop: the name carries a
		// whole-second timestamp, so a loop inside one second produces one name, not many.
		for (int i = 1; i <= 15; i++)
			Files.write(new File(folder.getRoot(), String.format("bots.conf.backup-20250101_%06d", i)).toPath(),
					"old".getBytes(StandardCharsets.UTF_8));

		// One real backup, stamped today, triggers the prune
		ConfigFileStore.backup(file);

		int backups = 0;
		for (File left : folder.getRoot().listFiles())
			if (left.getName().startsWith("bots.conf.backup-"))
				backups++;

		assertEquals(ConfigFileStore.BACKUPS_KEPT, backups);

		// Pruned by name, which for this stamp format is chronological - so the highest survive
		assertTrue(new File(folder.getRoot(), "bots.conf.backup-20250101_000015").exists());
		assertFalse(new File(folder.getRoot(), "bots.conf.backup-20250101_000001").exists());
	}
}
