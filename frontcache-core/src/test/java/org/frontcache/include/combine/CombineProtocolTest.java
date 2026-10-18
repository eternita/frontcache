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
package org.frontcache.include.combine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The combine wire contract's encoding half (docs/archive/combine-reduce-proposal.md section 5).
 *
 * What these are really pinning is the property the whole encoding exists for: a member's query
 * string travels as a VALUE, so nothing inside it can become part of the combined request's own
 * query grammar. Every round-trip case below is a character that would have broken a
 * concatenation-based scheme.
 */
public class CombineProtocolTest {

	private static String roundTrip(String query) {
		return CombineProtocol.decodePart(CombineProtocol.encodePart(query));
	}

	@Test
	public void aPlainQueryRoundTrips() {
		assertEquals("id=1&lang=en", roundTrip("id=1&lang=en"));
	}

	@Test
	public void separatorsInsideAValueRoundTrip() {
		// & and = inside a VALUE are exactly what a concatenated scheme cannot express
		assertEquals("q=a&b=c&sort=x", roundTrip("q=a&b=c&sort=x"));
		assertEquals("filter=a=b", roundTrip("filter=a=b"));
		assertEquals("pipe=1|2|3", roundTrip("pipe=1|2|3"));
	}

	@Test
	public void percentPlusAndSpaceRoundTrip() {
		assertEquals("q=100%", roundTrip("q=100%"));
		assertEquals("q=a+b", roundTrip("q=a+b"));
		assertEquals("q=a b", roundTrip("q=a b"));
	}

	@Test
	public void nonAsciiRoundTrips() {
		assertEquals("q=монета", roundTrip("q=монета"));
		assertEquals("q=éèê", roundTrip("q=éèê"));
	}

	@Test
	public void anEmptyQueryRoundTripsToEmptyNotNull() {
		assertEquals("", roundTrip(""));
		assertEquals("", CombineProtocol.encodePart(null));
		assertEquals("", CombineProtocol.decodePart(null));
	}

	@Test
	public void theCombinedQueryIsVersionedCountedAndIndexed() {

		String query = CombineProtocol.buildCombinedQuery(Arrays.asList("id=1&lang=en", "id=3&lang=en"));

		assertEquals("?fc-combine=1&fc-parts=2&fc-part-0=id%3D1%26lang%3Den&fc-part-1=id%3D3%26lang%3Den", query);
	}

	/**
	 * A one-member batch is still explicitly a combined request. Without {@code fc-combine} the
	 * origin could not tell it from an ordinary single-fragment request and would answer with the
	 * fragment rather than an envelope - which is the whole reason the marker is a parameter of its
	 * own rather than an inference from the shape of the query.
	 */
	@Test
	public void aOneMemberBatchIsStillMarkedCombined() {

		String query = CombineProtocol.buildCombinedQuery(Collections.singletonList("id=1"));

		assertEquals("?fc-combine=1&fc-parts=1&fc-part-0=id%3D1", query);
	}

	@Test
	public void aMemberWithNoQueryIsSentAsAnEmptyPart() {

		String query = CombineProtocol.buildCombinedQuery(Arrays.asList("", "id=2"));

		assertEquals("?fc-combine=1&fc-parts=2&fc-part-0=&fc-part-1=id%3D2", query);
	}

	@Test
	public void reservedParametersAreDetected() {
		assertTrue(CombineProtocol.hasReservedParam("fc-combine=1"));
		assertTrue(CombineProtocol.hasReservedParam("id=1&fc-parts=2"));
		assertTrue(CombineProtocol.hasReservedParam("id=1&fc-part-0=x"));
		assertTrue(CombineProtocol.hasReservedParam("id=1&fc-anything"));
		assertTrue("names are case-insensitive on the wire", CombineProtocol.hasReservedParam("FC-Combine=1"));
	}

	@Test
	public void nonReservedParametersAreNotMistakenForReservedOnes() {
		assertFalse(CombineProtocol.hasReservedParam(null));
		assertFalse(CombineProtocol.hasReservedParam(""));
		assertFalse(CombineProtocol.hasReservedParam("id=1&lang=en"));
		assertFalse("the prefix must be at the start of a NAME", CombineProtocol.hasReservedParam("id=fc-combine"));
		assertFalse(CombineProtocol.hasReservedParam("fcombine=1"));
		assertFalse(CombineProtocol.hasReservedParam("prefix-fc-combine=1"));
	}

	@Test
	public void urlIsSplitIntoEndpointQueryAndPath() {

		String url = "http://www.example.com:8080/fc/search-item.htm?id=1&lang=en";

		assertEquals("http://www.example.com:8080/fc/search-item.htm", CombineProtocol.endpointOf(url));
		assertEquals("id=1&lang=en", CombineProtocol.queryOf(url));
		assertEquals("/fc/search-item.htm", CombineProtocol.pathOf(url));
	}

	@Test
	public void urlWithoutQueryHasAnEmptyQueryNotNull() {

		String url = "http://www.example.com/fc/a.htm";

		assertEquals(url, CombineProtocol.endpointOf(url));
		assertEquals("", CombineProtocol.queryOf(url));
		assertEquals("/fc/a.htm", CombineProtocol.pathOf(url));
	}

	/**
	 * The path is what {@code dynamic-urls.conf} is matched against, so a host with no path at all
	 * must still yield something a pattern can be run over rather than an empty string.
	 */
	@Test
	public void urlWithNoPathYieldsRoot() {
		assertEquals("/", CombineProtocol.pathOf("http://www.example.com"));
		assertEquals("/", CombineProtocol.pathOf("http://www.example.com?a=1"));
	}

	/**
	 * The endpoint is always part of the group key, so one group name reused across two paths splits
	 * into two batches rather than producing a request no single endpoint can answer.
	 */
	@Test
	public void theGroupKeyCombinesEndpointAndGroupName() {

		String a = CombineProtocol.groupKey("http://h/fc/a.htm?id=1", "cell");
		String b = CombineProtocol.groupKey("http://h/fc/a.htm?id=2", "cell");
		String differentPath = CombineProtocol.groupKey("http://h/fc/b.htm?id=1", "cell");
		String differentGroup = CombineProtocol.groupKey("http://h/fc/a.htm?id=1", "row");

		assertEquals("members of one group share a key regardless of their query", a, b);
		assertFalse("same group name on another path is another batch", a.equals(differentPath));
		assertFalse("another group name on the same path is another batch", a.equals(differentGroup));
	}

	/**
	 * The edge's encoder and the origin's parser are two halves of one contract; this is the seam
	 * between them, tested across it rather than on either side alone.
	 *
	 * {@code parseCombinedQuery} below stands in for the servlet container: split the combined query
	 * on {@code &}, decode each value. That the member queries survive that intact - separators,
	 * spaces, non-ASCII and all - is the property the encoding exists for.
	 */
	@Test
	public void whatTheEdgeEncodesIsWhatTheOriginParses() {

		List<String> members = Arrays.asList("q=a&b=c&sort=x", "q=a b&lang=é", "", "flag&empty=");

		List<String> received = parseCombinedQuery(CombineProtocol.buildCombinedQuery(members));

		assertEquals(members, received);
	}

	@Test
	public void repeatedParametersInOneMemberSurvive() {

		Map<String, String[]> params = CombineRequest.parseQueryString("tag=a&tag=b&tag=c");

		assertEquals(Arrays.asList("a", "b", "c"), Arrays.asList(params.get("tag")));
	}

	/**
	 * A servlet container, in eight lines: split the combined query into parameters and hand back the
	 * decoded {@code fc-part-i} values in index order.
	 */
	private static List<String> parseCombinedQuery(String combinedQuery) {

		Map<String, String[]> params = CombineRequest.parseQueryString(combinedQuery.substring(1)); // drop '?'

		assertEquals("1", CombineRequest.param(params, CombineProtocol.PARAM_COMBINE));

		int count = Integer.parseInt(CombineRequest.param(params, CombineProtocol.PARAM_PARTS));

		List<String> parts = new java.util.ArrayList<String>(count);
		for (int i = 0; i < count; i++)
			parts.add(CombineRequest.param(params, CombineProtocol.PARAM_PART + i));

		return parts;
	}
}
