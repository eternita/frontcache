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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for the {@code put-to-cache} IO action response model and action constant.
 */
public class PutToCacheActionResponseTest {

	private final ObjectMapper jsonMapper = new ObjectMapper();

	@Test
	public void putToCacheActionConstantExists() {
		assertEquals("put-to-cache", FrontcacheAction.PUT_TO_CACHE);
	}

	@Test
	public void putToCacheActionInDescriptionMap() {
		Map<String, String> desc = FrontcacheAction.actionsDescriptionMap;
		assertTrue("PUT_TO_CACHE must be in actionsDescriptionMap",
				desc.containsKey(FrontcacheAction.PUT_TO_CACHE));
		assertNotNull("description must not be null",
				desc.get(FrontcacheAction.PUT_TO_CACHE));
	}

	@Test
	public void responseSerializesToJson() throws Exception {
		PutToCacheActionResponse resp = new PutToCacheActionResponse("/en/welcome.htm");

		String json = jsonMapper.writeValueAsString(resp);

		assertTrue(json.contains("\"action\":\"put to cache\""));
		assertTrue(json.contains("\"responseStatus\":\"OK\""));
		assertTrue(json.contains("\"key\":\"/en/welcome.htm\""));
	}

	@Test
	public void responseDeserializesFromJson() throws Exception {
		String json = "{\"action\":\"put to cache\",\"responseStatus\":\"OK\","
				+ "\"key\":\"/en/welcome.htm\"}";

		PutToCacheActionResponse resp = jsonMapper.readValue(json.getBytes(), PutToCacheActionResponse.class);

		assertEquals("put to cache", resp.getAction());
		assertEquals(ActionResponse.RESPONSE_STATUS_OK, resp.getResponseStatus());
		assertEquals("/en/welcome.htm", resp.getKey());
	}

	/**
	 * An older node still sends the retired `domain` field. Index replication has a new node reading
	 * an older node's response mid-upgrade, and Jackson fails on unknown properties by default - so
	 * without ActionResponse's @JsonIgnoreProperties every replicated entry would report failure.
	 */
	@Test
	public void unknownFieldFromAnOlderNodeIsIgnored() throws Exception {
		String json = "{\"action\":\"put to cache\",\"responseStatus\":\"OK\","
				+ "\"key\":\"/en/welcome.htm\",\"domain\":\"www.example.com\"}";

		PutToCacheActionResponse resp = jsonMapper.readValue(json.getBytes(), PutToCacheActionResponse.class);

		assertEquals(ActionResponse.RESPONSE_STATUS_OK, resp.getResponseStatus());
		assertEquals("/en/welcome.htm", resp.getKey());
	}

	@Test
	public void noArgConstructorForJackson() throws Exception {
		// Jackson needs a no-arg constructor to deserialize
		PutToCacheActionResponse resp = jsonMapper.readValue(
				"{\"action\":\"put to cache\",\"responseStatus\":\"OK\"}".getBytes(),
				PutToCacheActionResponse.class);

		assertNotNull(resp);
		assertNull(resp.getKey());
	}

	@Test
	public void errorResponseRoundTrip() throws Exception {
		PutToCacheActionResponse resp = new PutToCacheActionResponse("/missing.htm");
		resp.setResponseStatus(ActionResponse.RESPONSE_STATUS_ERROR);
		resp.setErrorDescription("Entry not found");

		String json = jsonMapper.writeValueAsString(resp);
		PutToCacheActionResponse back = jsonMapper.readValue(json.getBytes(), PutToCacheActionResponse.class);

		assertEquals(ActionResponse.RESPONSE_STATUS_ERROR, back.getResponseStatus());
		assertEquals("Entry not found", back.getErrorDescription());
		assertEquals("/missing.htm", back.getKey());
	}
}
