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
package org.frontcache.tests.standalone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.frontcache.tests.TestConfig;
import org.frontcache.tests.base.ApiKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A honeypot on a Community node: the standalone home's {@code honeypot-e2e | uri~^/common/honeypot/ | reject:404 Not
 * Found | trap}. The node answers the trap itself - a one-line 404, {@code no-store}, the origin never asked - and the
 * guard log names the address the node resolved, {@code honeypot-e2e#127.0.0.1}, not the {@code X-Forwarded-For} the
 * client wrote: this node trusts no proxy, so the forged header lands in the log's best-effort client-IP column and
 * nowhere a ban would be taken from.
 */
public class StandaloneHoneypotTests {

	private static final String STANDALONE = TestConfig.FRONTCACHE_STANDALONE_TEST_BASE_URI_LOCALHOST;
	private static final String FORGED = "192.0.2.66";

	private CloseableHttpClient client;

	@Before
	public void setUp() {
		client = HttpClients.createDefault();
	}

	@After
	public void tearDown() throws Exception {
		client.close();
	}

	@Test
	public void theTrapAnswersItselfAndLogsTheResolvedAddressNotTheForgedOne() throws Exception {
		long before = hits("honeypot-e2e");
		String marker = UUID.randomUUID().toString();

		HttpGet trap = new HttpGet(STANDALONE + "common/honeypot/" + marker + ".jsp");
		trap.addHeader("X-Forwarded-For", FORGED);
		try (ClassicHttpResponse response = client.executeOpen(null, trap, null)) {
			assertEquals(404, response.getCode());
			assertEquals("the node's one line, not the origin's 404 page", "Not Found",
					EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
			Header cacheControl = response.getFirstHeader("Cache-Control");
			assertNotNull(cacheControl);
			assertTrue(cacheControl.getValue(), cacheControl.getValue().startsWith("no-store"));
		}
		assertEquals(before + 1, hits("honeypot-e2e"));

		String line = awaitGuardLogLine(marker);
		assertTrue(line, line.contains("\"honeypot-e2e#127.0.0.1\" 404"));
		assertTrue("the forged header is only in the best-effort column: " + line, line.contains("\"" + FORGED + "\""));
		assertFalse(line, line.contains("#" + FORGED));
	}

	/** The guard log is written by an async appender: the line arrives shortly after the response. */
	private static String awaitGuardLogLine(String marker) throws Exception {
		String home = System.getProperty("frontcache-tests.home");
		assertNotNull("frontcache-tests.home is not set", home);
		File log = new File(home, "FRONTCACHE_HOME_STANDALONE/logs/frontcache-failed-requests.log");
		long deadline = System.currentTimeMillis() + 10_000;
		while (true)
		{
			if (log.isFile())
				for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8))
					if (line.contains(marker))
						return line;
			assertTrue("no guard log line for " + marker + " in " + log, System.currentTimeMillis() < deadline);
			Thread.sleep(100);
		}
	}

	private long hits(String rule) throws Exception {
		HttpGet get = new HttpGet(STANDALONE + "frontcache-io?action=get-guard-rules");
		get.addHeader("Authorization", "Bearer " + ApiKeys.TEST_API_KEY_LOCALHOST);
		String body;
		try (ClassicHttpResponse response = client.executeOpen(null, get, null)) {
			body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
		}
		int at = body.indexOf("\"name\":\"" + rule + "\"");
		assertTrue("no rule " + rule + " in " + body, -1 < at);
		int hitsAt = body.indexOf("\"hits\":", at);
		assertTrue("no hits for " + rule + " in " + body, -1 < hitsAt && hitsAt < body.indexOf('}', at));
		int from = hitsAt + "\"hits\":".length();
		int to = from;
		while (to < body.length() && Character.isDigit(body.charAt(to)))
			to++;
		return Long.parseLong(body.substring(from, to));
	}
}
