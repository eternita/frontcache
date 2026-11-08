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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.io.ConfigFile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * As of 2.12.0 every FrontcacheService method that reaches an edge takes the request's {@link EdgeScope}: an edge
 * outside it - here, another scope's, a real listening socket - is refused before any client exists, exactly as an
 * unknown one, and whatever the service keeps between requests is kept per scope.
 */
public class FrontcacheServiceScopeTest {

	private ServerSocket othersEdge;
	private String othersEdgeURL;

	private EdgeScope mine;
	private EdgeScope theirs;

	private FrontcacheService service;

	@Before
	public void setUp() throws IOException {
		othersEdge = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		othersEdge.setSoTimeout(300);
		othersEdgeURL = "http://127.0.0.1:" + othersEdge.getLocalPort() + "/";

		mine = EdgeScope.of("mine", List.of("http://127.0.0.1:1/", "http://127.0.0.1:2/"), "my-key");
		theirs = EdgeScope.of("theirs", List.of(othersEdgeURL), "their-key");

		service = new FrontcacheService();
	}

	@After
	public void tearDown() throws IOException {
		othersEdge.close();
	}

	private void assertNothingConnected() throws IOException {
		try (Socket connected = othersEdge.accept()) {
			fail("something connected to the other scope's edge");
		} catch (SocketTimeoutException expected) {
			// nothing did
		}
	}

	@Test
	public void everyActionOnAnotherScopesEdgeIsRefusedBeforeAnyConnection() throws IOException {
		assertThrows(UnknownEdgeException.class, () -> service.getFromCache(mine, othersEdgeURL, "http://site/"));
		assertThrows(UnknownEdgeException.class, () -> service.invalidateEdge(mine, othersEdgeURL, "*"));
		assertThrows(UnknownEdgeException.class, () -> service.purgeEdge(mine, othersEdgeURL));
		assertThrows(UnknownEdgeException.class, () -> service.dumpKeys(mine, othersEdgeURL));
		assertThrows(UnknownEdgeException.class, () -> service.backupEdge(mine, othersEdgeURL));
		assertThrows(UnknownEdgeException.class, () -> service.startReplication(mine, "http://127.0.0.1:1/", othersEdgeURL));
		assertThrows(UnknownEdgeException.class, () -> service.startReplication(mine, othersEdgeURL, "http://127.0.0.1:1/"));

		assertNothingConnected();
	}

	@Test
	public void theRefusalIsTheUnknownEdgeOne() {
		UnknownEdgeException outside = assertThrows(UnknownEdgeException.class, () -> service.purgeEdge(mine, othersEdgeURL));
		assertEquals(UnknownEdgeException.messageFor(othersEdgeURL), outside.getMessage());
	}

	@Test
	public void aFanOutReachesOnlyTheScope() throws IOException {
		List<FrontCacheStatus> edges = service.getEdgeList(mine);
		assertEquals(2, edges.size());
		assertTrue(edges.stream().noneMatch(e -> e.getUrl().contains(String.valueOf(othersEdge.getLocalPort()))));

		service.getConfigFiles(mine, ConfigFile.BOTS);
		service.getClusterStatusList(mine);
		assertNothingConnected();
	}

	@Test
	public void writingAConfigToAnotherScopesEdgeIsRefused() throws IOException {
		// refused for the edge, or for config editing being off on an unauthenticated console - never sent
		assertTrue(service.saveConfigFile(mine, othersEdgeURL, ConfigFile.BOTS, "x\n", null).isWritten() == false);
		assertTrue(service.saveConfigFileToAllEdges(mine, ConfigFile.BOTS, "x\n", null, null).keySet().stream()
				.noneMatch(url -> url.contains(String.valueOf(othersEdge.getLocalPort()))));
		assertNothingConnected();
	}

	/** One scope's replication is another's to neither see nor cancel. */
	@Test
	public void aReplicationIsItsScopesOnly() {
		service.startReplication(mine, "http://127.0.0.1:1/", "http://127.0.0.1:2/");

		assertNull(service.getReplicationStatus(theirs));
		service.cancelReplication(theirs);
	}
}
