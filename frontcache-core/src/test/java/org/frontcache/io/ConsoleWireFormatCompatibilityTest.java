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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.frontcache.FCConfig;
import org.frontcache.resilience.fr.FallbackConfigEntry;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The management API's config responses are a wire format between two SEPARATELY DEPLOYED things: a
 * node and a console. There is no moment at which they upgrade together, so both directions of version
 * skew have to work, and 2.7 changed three of these responses from {@code Map<domain, collection>} to a
 * flat collection and renamed {@code hystrixConfig} to {@code resilienceConfig}.
 *
 * Nothing else covers this. The e2e suite never calls {@code get-bots}, {@code get-dynamic-urls},
 * {@code get-fallback-configs} or the resilience-config action, and the console is not built against a
 * node in CI - so a shape change here would have shipped as a blank panel in someone's browser with
 * nothing in any log. These tests are the contract.
 */
public class ConsoleWireFormatCompatibilityTest {

	private final ObjectMapper jsonMapper = new ObjectMapper();

	/** What the pre-2.7 single-entry map was keyed by, and what the console's JSPs matched on. */
	private static final String LEGACY_KEY = "default-domain";

	// ---- the legacy key itself ---------------------------------------------------------------

	/**
	 * The legacy wire key is duplicated rather than referenced, so it has to be pinned against the
	 * config constant it was copied from - otherwise renaming the config value silently breaks the
	 * compatibility field, which is the one thing the field exists to prevent.
	 */
	@Test
	public void legacyDomainKeyMatchesTheConfigConstantItWasCopiedFrom()
	{
		assertEquals(LEGACY_KEY, LegacyDomainKeyedField.LEGACY_DOMAIN_KEY);
		assertEquals("a 2.6 console's JSPs switch on this literal",
				FCConfig.DEFAULT_DOMAIN, LegacyDomainKeyedField.LEGACY_DOMAIN_KEY);
	}

	// ---- 2.7 node -> 2.6 console (serialization) --------------------------------------------

	@Test
	public void botsResponseCarriesBothTheFlatSetAndTheLegacyMap() throws Exception
	{
		GetBotsActionResponse response = new GetBotsActionResponse();
		response.setBotKeywords(set("googlebot", "bingbot"));

		JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

		assertEquals("[\"googlebot\",\"bingbot\"]", json.get("botKeywords").toString());
		assertEquals("a 2.6 console reads this field",
				"[\"googlebot\",\"bingbot\"]", json.get("bots").get(LEGACY_KEY).toString());
	}

	@Test
	public void dynamicUrlsResponseCarriesBothTheFlatSetAndTheLegacyMap() throws Exception
	{
		GetDynamicURLsActionResponse response = new GetDynamicURLsActionResponse();
		response.setDynamicUrlPatterns(set("/cart.*", "/login.*"));

		JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

		assertEquals("[\"/cart.*\",\"/login.*\"]", json.get("dynamicUrlPatterns").toString());
		assertEquals("[\"/cart.*\",\"/login.*\"]", json.get("dynamicURLs").get(LEGACY_KEY).toString());
	}

	@Test
	public void resilienceConfigResponseCarriesBothFieldNames() throws Exception
	{
		GetResilienceConfigActionResponse response = new GetResilienceConfigActionResponse();
		response.setResilienceConfig("resilience.command.default.circuitBreaker.errorThresholdPercentage=50");

		JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));

		assertEquals("resilience.command.default.circuitBreaker.errorThresholdPercentage=50",
				json.get("resilienceConfig").asText());
		assertEquals("a 2.6 console reads this field",
				"resilience.command.default.circuitBreaker.errorThresholdPercentage=50",
				json.get("hystrixConfig").asText());
	}

	/**
	 * An absent value must stay absent in BOTH fields. Emitting the legacy field as an empty map when
	 * there is no data would turn "the node could not read its config" into "the node has no bots",
	 * which is a different fact.
	 */
	@Test
	public void anAbsentValueIsAbsentUnderBothNames() throws Exception
	{
		JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(new GetBotsActionResponse()));

		assertTrue("botKeywords must be absent or null", null == json.get("botKeywords") || json.get("botKeywords").isNull());
		assertTrue("bots must be absent or null", null == json.get("bots") || json.get("bots").isNull());
	}

	// ---- 2.6 node -> 2.7 console (deserialization) ------------------------------------------

	@Test
	public void readsA26NodesBotsResponse() throws Exception
	{
		String legacyOnly = "{\"responseStatus\":\"OK\",\"bots\":{\"" + LEGACY_KEY + "\":[\"googlebot\"]}}";

		GetBotsActionResponse response = jsonMapper.readValue(legacyOnly, GetBotsActionResponse.class);

		assertEquals(set("googlebot"), response.getBotKeywords());
	}

	@Test
	public void readsA26NodesDynamicUrlsResponse() throws Exception
	{
		String legacyOnly = "{\"responseStatus\":\"OK\",\"dynamicURLs\":{\"" + LEGACY_KEY + "\":[\"/cart.*\"]}}";

		GetDynamicURLsActionResponse response = jsonMapper.readValue(legacyOnly, GetDynamicURLsActionResponse.class);

		assertEquals(set("/cart.*"), response.getDynamicUrlPatterns());
	}

	@Test
	public void readsA26NodesResilienceConfigResponse() throws Exception
	{
		String legacyOnly = "{\"responseStatus\":\"OK\",\"hystrixConfig\":\"hystrix.command.default.x=1\"}";

		GetResilienceConfigActionResponse response =
				jsonMapper.readValue(legacyOnly, GetResilienceConfigActionResponse.class);

		assertEquals("hystrix.command.default.x=1", response.getResilienceConfig());
	}

	/**
	 * A genuinely old multi-domain node could send more than one entry. Their union is the only honest
	 * answer; keeping one and dropping the rest would silently hide config.
	 */
	@Test
	public void aMultiEntryLegacyMapIsFlattenedToTheUnion() throws Exception
	{
		String multiDomain = "{\"bots\":{\"a.example.com\":[\"googlebot\"],\"b.example.com\":[\"bingbot\"]}}";

		GetBotsActionResponse response = jsonMapper.readValue(multiDomain, GetBotsActionResponse.class);

		assertEquals(set("googlebot", "bingbot"), response.getBotKeywords());
	}

	/**
	 * A 2.7 node sends both fields, and Jackson invokes setters in document order. Whichever arrives
	 * first, the flat value has to win - the legacy setter must never overwrite it.
	 */
	@Test
	public void withBothFieldsPresentTheFlatOneWinsInEitherOrder() throws Exception
	{
		String flatFirst = "{\"botKeywords\":[\"real\"],\"bots\":{\"" + LEGACY_KEY + "\":[\"stale\"]}}";
		String legacyFirst = "{\"bots\":{\"" + LEGACY_KEY + "\":[\"stale\"]},\"botKeywords\":[\"real\"]}";

		assertEquals("flat field first",
				set("real"), jsonMapper.readValue(flatFirst, GetBotsActionResponse.class).getBotKeywords());
		assertEquals("legacy field first",
				set("real"), jsonMapper.readValue(legacyFirst, GetBotsActionResponse.class).getBotKeywords());
	}

	/** Round-trips both ways, which is what a same-version console/node pair actually does. */
	@Test
	public void roundTripsThroughItsOwnFormat() throws Exception
	{
		FallbackConfigEntry entry = new FallbackConfigEntry();
		entry.setUrlPattern("https?://[^/]+/product-.*");
		entry.setFileName("product.html");

		GetFallbackConfigActionResponse original = new GetFallbackConfigActionResponse();
		original.setFallbackEntries(new LinkedHashSet<FallbackConfigEntry>(Arrays.asList(entry)));

		GetFallbackConfigActionResponse parsed = jsonMapper.readValue(
				jsonMapper.writeValueAsString(original), GetFallbackConfigActionResponse.class);

		assertEquals(1, parsed.getFallbackEntries().size());
		assertEquals("https?://[^/]+/product-.*",
				parsed.getFallbackEntries().iterator().next().getUrlPattern());
	}

	// ---- helpers ------------------------------------------------------------------------------

	@Test
	public void flattenAndWrapHandleNullWithoutInventingData()
	{
		assertNull(LegacyDomainKeyedField.wrap(null));
		assertNull(LegacyDomainKeyedField.<String>flatten(null));

		Map<String, Set<String>> emptyMap = new LinkedHashMap<String, Set<String>>();
		assertTrue("an empty map flattens to an empty set, not null",
				LegacyDomainKeyedField.flatten(emptyMap).isEmpty());

		Map<String, Set<String>> nullValued = new LinkedHashMap<String, Set<String>>();
		nullValued.put(LEGACY_KEY, null);
		assertTrue("a null value inside the map must not NPE",
				LegacyDomainKeyedField.flatten(nullValued).isEmpty());
	}

	private static Set<String> set(String... values)
	{
		return new LinkedHashSet<String>(Arrays.asList(values));
	}

}
