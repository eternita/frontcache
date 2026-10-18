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
package org.frontcache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 *
 * Multi-domain config is retired - a node configured for it must not start.
 *
 * FCConfig is a JVM-global singleton with no property removal, so each test blanks the keys it set
 * in @After. A blank value counts as absent, which is also the documented way for an operator to
 * neutralize a key.
 *
 */
public class FrontCacheEngineRetiredDomainConfigTest {

	private static final String DOMAINS_KEY = "front-cache.domains";
	private static final String ORIGIN_HOST_KEY = "front-cache.domain.fc1-test_org.origin-host";
	// deliberately still "site-key": this is a RETIRED per-domain override, the literal string an
	// operator's pre-retirement file contains. front-cache.site-key was renamed to front-cache.api-key,
	// but front-cache.domain.<domain>.site-key never was - it is only ever detected, never read.
	private static final String SITE_KEY_KEY = "front-cache.domain.fc1-test_org.site-key";

	@After
	public void tearDown() {
		FCConfig.setProperty(DOMAINS_KEY, "");
		FCConfig.setProperty(ORIGIN_HOST_KEY, "");
		FCConfig.setProperty(SITE_KEY_KEY, "");
	}

	@Test
	public void singleDomainConfigIsAccepted() {
		// the pinned test fixture home (src/test/resources/fallback-home) declares no domain keys
		assertEquals("no retired keys expected in the fixture home", 0, FrontCacheEngine.findRetiredDomainKeys().size());
	}

	@Test
	public void blankValuesCountAsAbsent() {
		FCConfig.setProperty(DOMAINS_KEY, "   ");
		FCConfig.setProperty(ORIGIN_HOST_KEY, "");

		assertEquals(0, FrontCacheEngine.findRetiredDomainKeys().size());
	}

	@Test
	public void domainListIsRejected() {
		FCConfig.setProperty(DOMAINS_KEY, "fc1-test.org,fc2-test.org");

		List<String> keys = FrontCacheEngine.findRetiredDomainKeys();

		assertEquals(1, keys.size());
		// the value is echoed back so the log names the domains, not just the key
		assertEquals("front-cache.domains=fc1-test.org,fc2-test.org", keys.get(0));
	}

	@Test
	public void perDomainOverrideAloneIsRejected() {
		// without front-cache.domains these were silently ignored - report them anyway
		FCConfig.setProperty(ORIGIN_HOST_KEY, "origin.fc1-test.org");

		List<String> keys = FrontCacheEngine.findRetiredDomainKeys();

		assertEquals(1, keys.size());
		assertEquals(ORIGIN_HOST_KEY, keys.get(0));
	}

	@Test
	public void allRetiredKeysAreReportedDomainListFirst() {
		FCConfig.setProperty(DOMAINS_KEY, "fc1-test.org");
		FCConfig.setProperty(SITE_KEY_KEY, "test-site-key-1");
		FCConfig.setProperty(ORIGIN_HOST_KEY, "origin.fc1-test.org");

		List<String> keys = FrontCacheEngine.findRetiredDomainKeys();

		assertEquals(3, keys.size());
		assertEquals("front-cache.domains=fc1-test.org", keys.get(0));
		// overrides sorted, so the message is stable across runs
		assertEquals(ORIGIN_HOST_KEY, keys.get(1));
		assertEquals(SITE_KEY_KEY, keys.get(2));
	}

	@Test
	public void noLeftoverPerDomainConfigDirsInTheFixtureHome() {
		// the fixture home has conf/{bots,dynamic-urls,fallbacks}.conf at the top level and no
		// subdirectories - which is the shape every node now has
		assertEquals(0, FrontCacheEngine.findRetiredDomainConfigDirs().size());
	}

	@Test
	public void leftoverPerDomainConfigDirIsReported() throws Exception {
		File confDir = new File(System.getProperty("frontcache.home"), "conf");
		File domainDir = new File(confDir, "www.example.com");
		File botsConf = new File(domainDir, "bots.conf");
		File strayDir = new File(confDir, "notes");

		try {
			// a bare subdirectory is nobody's business
			assertTrue(strayDir.mkdirs() || strayDir.isDirectory());
			assertEquals("a subdirectory with no per-domain config must be ignored",
					0, FrontCacheEngine.findRetiredDomainConfigDirs().size());

			// one holding a file that USED to be layered per domain would now be read by nothing
			assertTrue(domainDir.mkdirs() || domainDir.isDirectory());
			Files.write(botsConf.toPath(), "Googlebot\n".getBytes(StandardCharsets.UTF_8));

			List<String> retired = FrontCacheEngine.findRetiredDomainConfigDirs();

			assertEquals(retired.toString(), 1, retired.size());
			assertEquals("conf/www.example.com/bots.conf", retired.get(0));
		} finally {
			botsConf.delete();
			domainDir.delete();
			strayDir.delete();
		}
	}

	@Test
	public void messageNamesTheOffendingKeysAndTheFix() {
		String message = FrontCacheEngine.retiredDomainConfigMessage(
				java.util.Arrays.asList("front-cache.domains=fc1-test.org", ORIGIN_HOST_KEY));

		assertTrue(message, message.contains("no longer supported"));
		// every offending key is named
		assertTrue(message, message.contains("front-cache.domains=fc1-test.org"));
		assertTrue(message, message.contains(ORIGIN_HOST_KEY));
		// and so is the way out
		assertTrue(message, message.contains("one Frontcache node per site"));
		assertTrue(message, message.contains("front-cache.default-domain"));
		assertTrue(message, message.contains("front-cache.origin-host"));
		assertTrue(message, message.contains("docs/archive/multi-domain-retirement-proposal.md"));
	}

}
