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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.frontcache.FCConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The {@code set-config} action: the gates that refuse a write, the allowlist, the conflict check and
 * what ends up on disk.
 *
 * Runs against a temporary FRONTCACHE_HOME rather than a live node - {@code frontcache.home} is read
 * live by {@code FCConfig.getWritableConfigFile}, so pointing it at a fixture is enough, and the apply
 * step is replaced by a recording seam so no engine has to exist.
 */
public class ConfigWriteActionTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private String previousHome;
	private File confDir;

	/** Records what would have been applied, instead of reloading a node that is not running. */
	private final List<ConfigFile> applied = new ArrayList<ConfigFile>();

	private final ConfigWriteAction.Applier recordingApplier = new ConfigWriteAction.Applier() {
		@Override
		public List<String> apply(ConfigFile configFile) {
			applied.add(configFile);
			return Collections.emptyList();
		}
	};

	private final ConfigWriteAction.Applier failingApplier = new ConfigWriteAction.Applier() {
		@Override
		public List<String> apply(ConfigFile configFile) {
			throw new IllegalStateException("no engine here");
		}
	};

	@Before
	public void setUp() throws Exception
	{
		previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);

		confDir = folder.newFolder("conf");

		// A frontcache.properties has to exist in the fixture BEFORE frontcache.home points at it:
		// FCConfig's static initializer throws if it cannot find one, and whether this test class is
		// the one that triggers that initializer depends on which other classes ran first. Without
		// this the suite passes or fails on test ORDER, which is the worst kind of red.
		Files.write(new File(confDir, "frontcache.properties").toPath(),
				"front-cache.default-domain=test.example.com\n".getBytes(StandardCharsets.UTF_8));

		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, folder.getRoot().getAbsolutePath());

		FCConfig.setProperty(ConfigWriteAction.CONFIG_WRITE_PROPERTY, "true");
	}

	@After
	public void tearDown()
	{
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);

		FCConfig.setProperty(ConfigWriteAction.CONFIG_WRITE_PROPERTY, "true");
	}

	private SetConfigActionResponse write(String fileId, String content)
	{
		return ConfigWriteAction.perform(fileId, content, null, true, true, recordingApplier);
	}

	private String onDisk(String fileName) throws Exception
	{
		return new String(Files.readAllBytes(new File(confDir, fileName).toPath()), StandardCharsets.UTF_8);
	}

	// ---- the gates ---------------------------------------------------------------------------

	/**
	 * An un-keyed node is readable and invalidatable, and that is deliberate. It must not also be
	 * remotely configurable: the un-keyed state is usually accidental (an upgrade that left the
	 * pre-2.9.0 front-cache.site-key spelling in the file), and this is the rule that keeps that
	 * mistake from being an open door.
	 */
	@Test
	public void anUnkeyedNodeRefusesToWrite() throws Exception
	{
		SetConfigActionResponse response =
				ConfigWriteAction.perform("bots", "Googlebot\n", null, true, false, recordingApplier);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
		assertTrue(response.getErrorDescription().contains("api-key"));
		assertFalse(new File(confDir, "bots.conf").exists());
	}

	@Test
	public void configWriteFalseRefusesToWrite() throws Exception
	{
		FCConfig.setProperty(ConfigWriteAction.CONFIG_WRITE_PROPERTY, "false");

		SetConfigActionResponse response = write("bots", "Googlebot\n");

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
		assertFalse(new File(confDir, "bots.conf").exists());
	}

	// ---- the allowlist ----------------------------------------------------------------------

	/**
	 * The {@code file} parameter is a logical id and never a path. These are the attempts that would
	 * matter if it were one.
	 */
	@Test
	public void onlyTheFiveKnownIdsAreAccepted()
	{
		for (String rejected : new String[] {
				"../../etc/passwd",
				"/etc/passwd",
				"frontcache.properties",
				"conf/bots.conf",
				"bots.conf",
				"fc-logback.xml",
				"",
				null })
		{
			SetConfigActionResponse response = write(rejected, "x\n");
			assertEquals("should have refused '" + rejected + "'",
					ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
			assertFalse(response.isWritten());
		}

		for (ConfigFile accepted : ConfigFile.values())
			assertNotNull(ConfigFile.byId(accepted.getId()));
	}

	@Test
	public void missingContentIsRefused()
	{
		SetConfigActionResponse response =
				ConfigWriteAction.perform("bots", null, null, true, true, recordingApplier);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
	}

	// ---- writing ----------------------------------------------------------------------------

	@Test
	public void aValidFileIsWrittenAndApplied() throws Exception
	{
		SetConfigActionResponse response = write("bots", "Googlebot\nbingbot\n");

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		assertTrue(response.isWritten());
		assertTrue(response.isApplied());
		assertEquals("bots.conf", response.getFile());
		assertEquals("Googlebot\nbingbot\n", onDisk("bots.conf"));
		assertEquals(Collections.singletonList(ConfigFile.BOTS), applied);
	}

	/** The CRLF a browser form submission produces must not reach the file. */
	@Test
	public void submittedCrlfIsWrittenAsLf() throws Exception
	{
		write("bots", "Googlebot\r\nbingbot\r\n");

		assertEquals("Googlebot\nbingbot\n", onDisk("bots.conf"));
	}

	/**
	 * The file being created is normal - a node with no conf/guard-rules.conf has never used guard
	 * rules - but the operator has to be told, because if a bundled default of that name was in play
	 * this new file now shadows it.
	 */
	@Test
	public void creatingTheFileIsReportedAsAWarning() throws Exception
	{
		SetConfigActionResponse response = write("bots", "Googlebot\n");

		assertTrue(response.isWritten());
		assertTrue("expected a 'created' warning, got " + response.getWarnings(),
				response.getWarnings().stream().anyMatch(w -> w.contains("did not exist and was created")));
	}

	@Test
	public void thePreviousContentIsKeptAsABackup() throws Exception
	{
		Files.write(new File(confDir, "bots.conf").toPath(), "Googlebot\n".getBytes(StandardCharsets.UTF_8));

		SetConfigActionResponse response = write("bots", "Googlebot\nbingbot\n");

		assertNotNull(response.getBackupFile());
		assertEquals("Googlebot\n", new String(
				Files.readAllBytes(new File(confDir, response.getBackupFile()).toPath()), StandardCharsets.UTF_8));
	}

	/**
	 * Saving unchanged content writes nothing but still applies.
	 *
	 * That is what makes Save the way to re-apply a file edited on the box by hand, and it is also why
	 * the round trip in the e2e suite can assert byte equality: nothing is rewritten.
	 */
	@Test
	public void identicalContentIsNotRewrittenButIsStillApplied() throws Exception
	{
		Files.write(new File(confDir, "bots.conf").toPath(), "Googlebot\n".getBytes(StandardCharsets.UTF_8));

		SetConfigActionResponse response = write("bots", "Googlebot\n");

		assertFalse(response.isWritten());
		assertTrue(response.isApplied());
		assertEquals(Collections.singletonList(ConfigFile.BOTS), applied);
		assertTrue(response.getWarnings().stream().anyMatch(w -> w.contains("identical")));
	}

	// ---- validation refuses the write --------------------------------------------------------

	@Test
	public void aBrokenLineRefusesTheWholeWrite() throws Exception
	{
		Files.write(new File(confDir, "bots.conf").toPath(), "Googlebot\n".getBytes(StandardCharsets.UTF_8));

		SetConfigActionResponse response = write("bots", "Googlebot\nbroken | ua~x | browser\n");

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
		assertEquals(1, response.getValidationErrors().size());
		assertTrue(response.getValidationErrors().get(0).startsWith("line 2:"));

		// and the file on disk is untouched, which is the point of validating first
		assertEquals("Googlebot\n", onDisk("bots.conf"));
	}

	// ---- optimistic concurrency --------------------------------------------------------------

	@Test
	public void aMatchingBaseHashIsAccepted() throws Exception
	{
		Files.write(new File(confDir, "bots.conf").toPath(), "Googlebot\n".getBytes(StandardCharsets.UTF_8));

		SetConfigActionResponse response = ConfigWriteAction.perform("bots", "Googlebot\nbingbot\n",
				ConfigFileStore.hash("Googlebot\n"), true, true, recordingApplier);

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		assertTrue(response.isWritten());
	}

	/**
	 * Someone else changed the file since the console rendered it. Nothing is written, and the current
	 * content comes back with the refusal - so the operator can see what they were about to overwrite
	 * rather than only being told they could not.
	 */
	@Test
	public void aStaleBaseHashRefusesAndReturnsWhatIsOnDisk() throws Exception
	{
		Files.write(new File(confDir, "bots.conf").toPath(),
				"changed-by-someone-else\n".getBytes(StandardCharsets.UTF_8));

		SetConfigActionResponse response = ConfigWriteAction.perform("bots", "Googlebot\nbingbot\n",
				ConfigFileStore.hash("Googlebot\n"), true, true, recordingApplier);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
		assertEquals("changed-by-someone-else\n", response.getCurrentContent());
		assertEquals("changed-by-someone-else\n", onDisk("bots.conf"));
		assertTrue(applied.isEmpty());
	}

	/** No hash means the check could not be made, not that it passed - and the response says so. */
	@Test
	public void anAbsentBaseHashSkipsTheCheckAndWarns() throws Exception
	{
		Files.write(new File(confDir, "bots.conf").toPath(), "whatever\n".getBytes(StandardCharsets.UTF_8));

		SetConfigActionResponse response = write("bots", "Googlebot\n");

		assertTrue(response.isWritten());
		assertTrue("expected an unchecked-save warning, got " + response.getWarnings(),
				response.getWarnings().stream().anyMatch(w -> w.contains("without a concurrency check")));
	}

	// ---- apply -------------------------------------------------------------------------------

	@Test
	public void applyFalseWritesWithoutApplying() throws Exception
	{
		SetConfigActionResponse response =
				ConfigWriteAction.perform("bots", "Googlebot\n", null, false, true, recordingApplier);

		assertTrue(response.isWritten());
		assertFalse(response.isApplied());
		assertTrue(applied.isEmpty());
		assertEquals("Googlebot\n", onDisk("bots.conf"));
	}

	/**
	 * A failed apply is NOT a failed save.
	 *
	 * The file is on disk; reporting an error would send the operator looking for a change that is
	 * already there. OK with applied=false is a state the response models on purpose.
	 */
	@Test
	public void aFailedApplyKeepsTheWriteAndSaysWhatHappened() throws Exception
	{
		SetConfigActionResponse response =
				ConfigWriteAction.perform("bots", "Googlebot\n", null, true, true, failingApplier);

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		assertTrue(response.isWritten());
		assertFalse(response.isApplied());
		assertEquals("Googlebot\n", onDisk("bots.conf"));
		assertTrue(response.getWarnings().stream().anyMatch(w -> w.contains("applying it failed")));
	}
}
