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
package org.frontcache.console.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.frontcache.io.ActionResponse;
import org.frontcache.io.ConfigFile;
import org.frontcache.io.SetConfigActionResponse;
import org.frontcache.console.auth.EdgeScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Every console method that takes an edge URL from a request sends the fleet's API key to it, so an
 * edge that is not one of this console's must never get a client - see
 * {@link org.frontcache.console.auth.EdgeScope#client}.
 *
 * The unknown edge here is a real listening socket, so "refused" is asserted as "nothing connected",
 * not merely as "an exception came back": a method that built the client, sent the request and then
 * failed would pass a test that only checked the exception.
 */
public class FrontcacheServiceConfiguredEdgeTest {

	private static final String EDGE = "http://localhost:9080";

	private ServerSocket attacker;
	private String attackerURL;
	private FrontcacheService service;

	@Before
	public void setUp() throws IOException {
		attacker = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		attacker.setSoTimeout(300);
		attackerURL = "http://127.0.0.1:" + attacker.getLocalPort() + "/";

		service = new FrontcacheService();
		service.addEdge(EDGE);
	}

	@After
	public void tearDown() throws IOException {
		attacker.close();
	}

	private void assertNothingConnected() throws IOException {
		try (Socket connected = attacker.accept()) {
			throw new AssertionError("the console connected to an edge it does not have: " + connected);
		} catch (SocketTimeoutException expected) {
			// nobody called
		}
	}

	@Test
	public void anUnknownEdgeGetsNoClient() throws IOException {
		assertNull(edges(service).client(attackerURL));
		assertNull(edges(service).client(""));
		assertNull(edges(service).client(null));

		assertNotNull(edges(service).client(EDGE + "/"));
		assertEquals("built from the configured spelling", EDGE, edges(service).client(EDGE + "/").getFrontCacheURL());
	}

	/** The Edges page adds edges at runtime; the allowlist is the current list, not the startup one. */
	@Test
	public void anEdgeAddedAtRuntimeIsAccepted() {
		assertNull(edges(service).client(attackerURL));
		service.addEdge(attackerURL);
		assertNotNull(edges(service).client(attackerURL));
		service.removeEdge(attackerURL);
		assertNull(edges(service).client(attackerURL));
	}

	@Test
	public void cacheViewRefusesAnUnknownEdge() throws IOException {
		assertThrows(UnknownEdgeException.class, () -> service.getFromCache(edges(service), attackerURL, "http://site/page"));
		assertNothingConnected();
	}

	@Test
	public void invalidationRefusesAnUnknownEdge() throws IOException {
		assertThrows(UnknownEdgeException.class, () -> service.invalidateEdge(edges(service), attackerURL, "*"));
		assertThrows(UnknownEdgeException.class, () -> service.purgeEdge(edges(service), attackerURL));
		assertNothingConnected();
	}

	@Test
	public void cacheOtherRefusesAnUnknownEdge() throws IOException {
		assertThrows(UnknownEdgeException.class, () -> service.dumpKeys(edges(service), attackerURL));
		assertThrows(UnknownEdgeException.class, () -> service.backupEdge(edges(service), attackerURL));
		assertNothingConnected();
	}

	/** Either end of a replication receives the key - the source is read, the target written. */
	@Test
	public void replicationRefusesAnUnknownSourceOrTarget() throws IOException {
		assertThrows(UnknownEdgeException.class, () -> service.startReplication(edges(service), EDGE, attackerURL));
		assertThrows(UnknownEdgeException.class, () -> service.startReplication(edges(service), attackerURL, EDGE));
		assertThrows("an unselected edge", UnknownEdgeException.class, () -> service.startReplication(edges(service), EDGE, ""));
		assertNull("nothing started", service.getReplicationStatus(edges(service)));
		assertNothingConnected();
	}

	/** Two spellings of one edge are one edge: replicating onto itself would skip every key and report success. */
	@Test
	public void replicationRefusesTheSameEdgeSpelledTwoWays() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.startReplication(edges(service), EDGE, EDGE + "/"));
		assertFalse(e instanceof UnknownEdgeException);
		assertNull(service.getReplicationStatus(edges(service)));
	}

	/** Config editing refuses per edge rather than throwing: the screen shows one result line per edge. */
	@Test
	public void configSaveRefusesAnUnknownEdge() throws IOException {
		FrontcacheService editing = spy(service);
		doReturn(true).when(editing).isConfigEditingEnabled();

		SetConfigActionResponse response = editing.saveConfigFile(edges(editing), attackerURL, ConfigFile.BOTS, "Googlebot\n", null);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		assertFalse(response.isWritten());
		assertTrue(response.getErrorDescription(), response.getErrorDescription().contains("not one of this console's edges"));
		assertNothingConnected();
	}

	// ---- the canonical form itself -------------------------------------------------------------

	@Test
	public void canonicalEdgeURL() {
		assertEquals("http://a/", FrontcacheService.canonicalEdgeURL("http://a"));
		assertEquals("http://a/", FrontcacheService.canonicalEdgeURL("http://a/"));
		assertEquals("http://a/", FrontcacheService.canonicalEdgeURL(" http://a "));
		assertEquals("http://a/b/", FrontcacheService.canonicalEdgeURL("http://a/b"));
		assertEquals("", FrontcacheService.canonicalEdgeURL("   "));
		assertNull(FrontcacheService.canonicalEdgeURL(null));
	}

	/** The console's configured edges, as a request on a console with no plugin sees them. */
	private static EdgeScope edges(FrontcacheService service) {
		return new ConfiguredEdgeScope(service).everyEdge();
	}
}
