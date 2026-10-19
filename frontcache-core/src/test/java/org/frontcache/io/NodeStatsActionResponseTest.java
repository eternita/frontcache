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
package org.frontcache.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.frontcache.cache.CacheStats;
import org.frontcache.core.FcNodeStats;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link NodeStatsActionResponse} - the shape a console will poll, and the arithmetic behind the one
 * derived number in it.
 *
 * Like the other management responses this is a wire format between separately deployed things, so
 * both directions are exercised: a node serializing it and a console of another version parsing it.
 * {@code ConsoleWireFormatCompatibilityTest} covers the responses that changed shape in 2.7; this one
 * is new in 2.8 and has no legacy shape to carry, so what matters is that it round-trips and that
 * unknowns survive the trip as unknowns.
 */
public class NodeStatsActionResponseTest {

	private final ObjectMapper jsonMapper = new ObjectMapper();

	private static CacheStats stats(long l1Entries, long l2Entries) {
		return new CacheStats(l1Entries, 7L, 4096L, l2Entries, 8192L);
	}

	// ---- the derived total -------------------------------------------------------------------

	@Test
	public void cachedEntriesIsTheSumOfBothTiers()
	{
		NodeStatsActionResponse response = new NodeStatsActionResponse(stats(94L, 539_513L), FcNodeStats.current());

		assertEquals(539_607L, response.getCachedEntries());
	}

	/**
	 * The rule that keeps the total honest. A node whose L1 does not report would otherwise publish its
	 * L2 count as "items cached" - a number that is wrong, plausible, and impossible to spot.
	 */
	@Test
	public void anUntrackedTierMakesTheTotalUnknownRatherThanPartial()
	{
		NodeStatsActionResponse noL1 = new NodeStatsActionResponse(stats(CacheStats.UNKNOWN, 500L), FcNodeStats.current());
		assertEquals("not 500 - that would read as the whole cache", CacheStats.UNKNOWN, noL1.getCachedEntries());

		NodeStatsActionResponse noL2 = new NodeStatsActionResponse(stats(94L, CacheStats.UNKNOWN), FcNodeStats.current());
		assertEquals(CacheStats.UNKNOWN, noL2.getCachedEntries());
	}

	@Test
	public void anEmptyCacheTotalsZeroRatherThanUnknown()
	{
		NodeStatsActionResponse response = new NodeStatsActionResponse(stats(0L, 0L), FcNodeStats.current());

		assertEquals("an empty cache is a measurement, not a missing one", 0L, response.getCachedEntries());
	}

	/** A node with no cache processor yet still answers - with the JVM half of the response. */
	@Test
	public void everyFieldIsUnknownWhenNothingIsTracked()
	{
		NodeStatsActionResponse response =
				new NodeStatsActionResponse(CacheStats.NOT_TRACKED, FcNodeStats.NOT_TRACKED);

		assertEquals(CacheStats.UNKNOWN, response.getCachedEntries());
		assertEquals(CacheStats.UNKNOWN, response.getL1Entries());
		assertEquals(CacheStats.UNKNOWN, response.getL1ContentBytes());
		assertEquals(CacheStats.UNKNOWN, response.getL2DiskBytes());
		assertEquals(FcNodeStats.UNKNOWN, response.getHeapUsedBytes());
		assertEquals(FcNodeStats.UNKNOWN_LOAD, response.getCpuProcessLoad(), 0.0);
	}

	@Test
	public void nullsAreToleratedRatherThanThrown()
	{
		NodeStatsActionResponse response = new NodeStatsActionResponse(null, null);

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, response.getResponseStatus());
		assertEquals(CacheStats.UNKNOWN, response.getCachedEntries());
	}

	// ---- node -> console (serialization) -----------------------------------------------------

	@Test
	public void everyNumberIsOnTheWireAsATypedField() throws Exception
	{
		NodeStatsActionResponse response = new NodeStatsActionResponse(
				new CacheStats(94L, 12_043L, 7_549_747L, 539_513L, 4_402_341_888L),
				new FcNodeStats(851_443_712L, 2_147_483_648L, 0.073, 982_371_000L));

		JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

		assertEquals(FrontcacheAction.GET_NODE_STATS, json.get("action").asText());
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, json.get("responseStatus").asText());
		assertEquals(539_607L, json.get("cachedEntries").asLong());
		assertEquals(94L, json.get("l1Entries").asLong());
		assertEquals(12_043L, json.get("l1Evictions").asLong());
		assertEquals(7_549_747L, json.get("l1ContentBytes").asLong());
		assertEquals(539_513L, json.get("l2Entries").asLong());
		assertEquals("a multi-GB index must not be truncated to an int",
				4_402_341_888L, json.get("l2DiskBytes").asLong());
		assertEquals(851_443_712L, json.get("heapUsedBytes").asLong());
		assertEquals(2_147_483_648L, json.get("heapMaxBytes").asLong());
		assertEquals(0.073, json.get("cpuProcessLoad").asDouble(), 0.0001);
		assertEquals(982_371_000L, json.get("uptimeMillis").asLong());
	}

	// ---- console -> node, and version skew ---------------------------------------------------

	@Test
	public void roundTripsThroughJson() throws Exception
	{
		NodeStatsActionResponse sent = new NodeStatsActionResponse(
				new CacheStats(94L, 12_043L, 7_549_747L, 539_513L, 4_402_341_888L),
				new FcNodeStats(851_443_712L, 2_147_483_648L, 0.073, 982_371_000L));

		NodeStatsActionResponse received = jsonMapper.readValue(
				jsonMapper.writeValueAsBytes(sent), NodeStatsActionResponse.class);

		assertEquals(sent.getCachedEntries(), received.getCachedEntries());
		assertEquals(sent.getL1ContentBytes(), received.getL1ContentBytes());
		assertEquals(sent.getL2DiskBytes(), received.getL2DiskBytes());
		assertEquals(sent.getHeapMaxBytes(), received.getHeapMaxBytes());
		assertEquals(sent.getUptimeMillis(), received.getUptimeMillis());
		assertEquals(sent.getCpuProcessLoad(), received.getCpuProcessLoad(), 0.0);
	}

	@Test
	public void unknownsSurviveTheTripAsUnknowns() throws Exception
	{
		NodeStatsActionResponse received = jsonMapper.readValue(
				jsonMapper.writeValueAsBytes(new NodeStatsActionResponse(CacheStats.NOT_TRACKED, FcNodeStats.NOT_TRACKED)),
				NodeStatsActionResponse.class);

		assertEquals(CacheStats.UNKNOWN, received.getL1Entries());
		assertEquals(FcNodeStats.UNKNOWN_LOAD, received.getCpuProcessLoad(), 0.0);
	}

	/**
	 * A later node adding a field must not break an earlier console's parse. Inherited from
	 * {@link ActionResponse}, and asserted here because this response is the one about to be polled
	 * across a mixed fleet every ten seconds.
	 */
	@Test
	public void aFieldFromANewerNodeIsIgnoredRatherThanFatal() throws Exception
	{
		String fromTheFuture = "{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\","
				+ "\"cachedEntries\":10,\"l1Entries\":4,\"l2Entries\":6,"
				+ "\"offHeapBytes\":123456,\"gcPausePercent\":1.5}";

		NodeStatsActionResponse received = jsonMapper.readValue(fromTheFuture, NodeStatsActionResponse.class);

		assertEquals(10L, received.getCachedEntries());
		assertEquals(4L, received.getL1Entries());
	}

	/**
	 * A response from a node that predates the fields - what an older node would send if it knew the
	 * action at all. Absent fields have to land on UNKNOWN, not on 0.
	 */
	@Test
	public void absentFieldsDefaultToUnknownNotZero() throws Exception
	{
		NodeStatsActionResponse received = jsonMapper.readValue(
				"{\"action\":\"get-node-stats\",\"responseStatus\":\"OK\",\"l1Entries\":4}",
				NodeStatsActionResponse.class);

		assertEquals(4L, received.getL1Entries());
		assertEquals("a field nobody sent is unknown", CacheStats.UNKNOWN, received.getL1ContentBytes());
		assertEquals(FcNodeStats.UNKNOWN, received.getHeapUsedBytes());
		assertEquals(FcNodeStats.UNKNOWN_LOAD, received.getCpuProcessLoad(), 0.0);
	}

	// ---- the action is discoverable ----------------------------------------------------------

	@Test
	public void theActionIsListedInHelp()
	{
		assertTrue("an action missing from help is an action nobody finds",
				FrontcacheAction.actionsDescriptionMap.containsKey(FrontcacheAction.GET_NODE_STATS));
	}

}
