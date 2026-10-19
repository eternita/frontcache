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
package org.frontcache.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.frontcache.cache.CacheStats;
import org.frontcache.core.FcNodeStats;
import org.frontcache.io.NodeStatsActionResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A 2.8 console asks for {@code get-node-stats}; a 2.7 node has never heard of it, and the management
 * servlet answers an unknown action with a PING rather than an error. Without the retry the "Edges
 * status" panel would show a row of dashes for a node that is perfectly healthy, with nothing in any
 * log to say why.
 *
 * This is the one place the feature can regress a mixed fleet, so it is driven against a
 * {@link StubNode} that answers like each node version in turn - the interesting node is the OLD one,
 * which no e2e run can produce.
 */
public class NodeStatsActionFallbackTest {

	private StubNode node;

	@Before
	public void startStub() throws IOException
	{
		node = new StubNode();
		node.start();
	}

	@After
	public void stopStub()
	{
		node.stop();
	}

	private FrontCacheClient client()
	{
		return new FrontCacheClient(node.url(), "test-api-key");
	}

	/** A 2.8 node answers the current action, so there must be exactly one request. */
	@Test
	public void currentNodeIsAskedOnce() throws Exception
	{
		node.answerWith("{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\","
				+ "\"cachedEntries\":539607,\"l1Entries\":94,\"l1ContentBytes\":7549747,"
				+ "\"l2Entries\":539513,\"l2DiskBytes\":4402341888,"
				+ "\"heapUsedBytes\":851443712,\"heapMaxBytes\":2147483648,"
				+ "\"cpuProcessLoad\":0.073,\"uptimeMillis\":982371000}");

		NodeStatsActionResponse stats = client().getNodeStatsActionResponse();

		assertEquals(539_607L, stats.getCachedEntries());
		assertEquals(7_549_747L, stats.getL1ContentBytes());
		assertEquals(4_402_341_888L, stats.getL2DiskBytes());
		assertEquals(0.073, stats.getCpuProcessLoad(), 0.0001);
		assertEquals(Collections.singletonList("get-node-stats"), node.actionsSeen());
	}

	/**
	 * A 2.7 node: a ping to the new action, then the old action's display map. The entry count survives
	 * the trip; everything the old node cannot report stays UNKNOWN so the console shows "not reported"
	 * rather than a confident zero.
	 */
	@Test
	public void olderNodeFallsBackToCacheState() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"action\":\"cache status\",\"responseStatus\":\"OK\",\"cacheStatus\":"
						+ "{\"impl\":\"org.frontcache.cache.impl.L1L2CacheProcessor\",\"cached-entries\":\"539607\"}}");

		NodeStatsActionResponse stats = client().getNodeStatsActionResponse();

		assertEquals("the one number an older node does have", 539_607L, stats.getCachedEntries());
		assertEquals("but not the per-tier breakdown", CacheStats.UNKNOWN, stats.getL1Entries());
		assertEquals(CacheStats.UNKNOWN, stats.getL1ContentBytes());
		assertEquals(CacheStats.UNKNOWN, stats.getL2DiskBytes());
		assertEquals("nor anything about its JVM", FcNodeStats.UNKNOWN, stats.getHeapUsedBytes());
		assertEquals(FcNodeStats.UNKNOWN_LOAD, stats.getCpuProcessLoad(), 0.0);

		assertEquals("the new action first, then the fallback",
				Arrays.asList("get-node-stats", "get-cache-state"), node.actionsSeen());
	}

	/**
	 * Which action actually answered is recorded on the response. A console that wants to say "this
	 * node predates 2.8" - or simply to stop drawing empty rows for it - has no other way to tell that
	 * apart from a node whose numbers happen to be untracked.
	 */
	@Test
	public void theFallbackSaysWhichActionAnsweredIt() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"action\":\"cache status\",\"responseStatus\":\"OK\",\"cacheStatus\":{\"cached-entries\":\"1\"}}");

		assertEquals("get-cache-state", client().getNodeStatsActionResponse().getAction());
	}

	/**
	 * A node that knows the action but failed answers ERROR, not a ping. Retrying that is a wasted
	 * round trip against a node that already gave its answer.
	 */
	@Test
	public void aRealErrorIsNotRetried() throws Exception
	{
		node.answerWith("{\"action\":\"get-node-stats\",\"responseStatus\":\"ERROR\","
				+ "\"errorDescription\":\"something went wrong\"}");

		NodeStatsActionResponse stats = client().getNodeStatsActionResponse();

		assertEquals("ERROR", stats.getResponseStatus());
		assertEquals(Collections.singletonList("get-node-stats"), node.actionsSeen());
	}

	/** An older node that cannot answer the fallback either leaves nothing to render. */
	@Test
	public void anOlderNodeThatCannotAnswerTheFallbackYieldsNull() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}");

		assertNull(client().getNodeStatsActionResponse());
		assertEquals(Arrays.asList("get-node-stats", "get-cache-state"), node.actionsSeen());
	}

	/** A malformed entry count must not fail the whole reading - the rest of the row is still useful. */
	@Test
	public void anUnparseableEntryCountLeavesTheTotalUnknown() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"action\":\"cache status\",\"responseStatus\":\"OK\",\"cacheStatus\":{\"cached-entries\":\"lots\"}}");

		NodeStatsActionResponse stats = client().getNodeStatsActionResponse();

		assertEquals(CacheStats.UNKNOWN, stats.getCachedEntries());
	}

	/** An unreachable edge is null, not an exception out of a poll loop. */
	@Test
	public void anUnreachableEdgeIsNull() throws Exception
	{
		node.stop();

		assertNull(new FrontCacheClient(node.url(), "test-api-key").getNodeStatsActionResponse());
	}

	/** The fallback costs one extra round trip and no more, however many times it is called. */
	@Test
	public void theFallbackIsNotCachedIntoAPermanentDoubleRequest() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"action\":\"cache status\",\"responseStatus\":\"OK\",\"cacheStatus\":{\"cached-entries\":\"5\"}}",
				"{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\",\"cachedEntries\":6}");

		FrontCacheClient client = client();
		assertEquals(5L, client.getNodeStatsActionResponse().getCachedEntries());
		assertEquals("an upgraded node is asked the new action again, not the legacy one",
				6L, client.getNodeStatsActionResponse().getCachedEntries());

		assertTrue(node.actionsSeen().toString(),
				Arrays.asList("get-node-stats", "get-cache-state", "get-node-stats").equals(node.actionsSeen()));
	}

}
