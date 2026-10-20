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

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The 2.7 client asks for {@code get-resilience-configs}; a 2.6 node has never heard of it. That is
 * not an error on the wire - the management servlet answers an unknown action with a PING response, so
 * without a retry the console would render an empty config panel with nothing in any log to say why.
 *
 * This drives {@link FrontCacheClient} against a {@link StubNode} that answers like each node version in
 * turn, and records the actions it was asked for. A stub rather than the e2e suite because the
 * interesting node is the OLD one, and the e2e suite can only run the current code.
 */
public class ResilienceConfigActionFallbackTest {

	private StubNode node;

	@Before
	public void startStub() throws java.io.IOException
	{
		node = new StubNode();
		node.start();
	}

	@After
	public void stopStub()
	{
		node.stop();
	}

	/** A 2.7 node: answers the current action, so there must be exactly one request. */
	@Test
	public void currentNodeIsAskedOnce() throws Exception
	{
		node.answerWith("{\"responseStatus\":\"OK\",\"resilienceConfig\":\"resilience.command.default.x=1\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");

		assertEquals("resilience.command.default.x=1", client.getResilienceConfig());
		assertEquals(Collections.singletonList("get-resilience-configs"), node.actionsSeen());
	}

	/**
	 * A 2.6 node: a ping response to the current action, then the real answer under the legacy field
	 * name. Both the retry and the legacy field have to work for the value to come back.
	 */
	@Test
	public void legacyNodeIsRetriedWithTheOldActionName() throws Exception
	{
		node.answerWith(
				"{\"action\":\"ping\",\"responseStatus\":\"OK\"}",
				"{\"responseStatus\":\"OK\",\"hystrixConfig\":\"hystrix.command.default.x=1\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");

		assertEquals("hystrix.command.default.x=1", client.getResilienceConfig());
		assertEquals("the current action first, then the pre-2.7 one",
				java.util.Arrays.asList("get-resilience-configs", "get-hystrix-configs"), node.actionsSeen());
	}

	/**
	 * A node that recognizes the action but cannot read the file answers with an ERROR, not a ping.
	 * Retrying that would be a wasted round trip against a node that already gave its answer.
	 */
	@Test
	public void aRealErrorIsNotRetried() throws Exception
	{
		node.answerWith("{\"responseStatus\":\"ERROR\",\"errorDescription\":\"resilience.properties not found\"}");

		FrontCacheClient client = new FrontCacheClient(node.url(), "test-api-key");

		assertNull(client.getResilienceConfig());
		assertEquals(Collections.singletonList("get-resilience-configs"), node.actionsSeen());
	}

	/** The deprecated accessor must route through the same fallback, not the raw legacy action. */
	@Test
	public void theDeprecatedAccessorGetsTheSameTreatment() throws Exception
	{
		node.answerWith("{\"responseStatus\":\"OK\",\"resilienceConfig\":\"resilience.command.default.x=1\"}");

		@SuppressWarnings("deprecation")
		String viaLegacyAccessor = new FrontCacheClient(node.url(), "test-api-key").getHystrixConfig();

		assertEquals("resilience.command.default.x=1", viaLegacyAccessor);
		assertEquals(Collections.singletonList("get-resilience-configs"), node.actionsSeen());
	}

}
