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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.FrontCacheStatus;
import org.junit.Test;

/**
 * The Edges status, Visitor Types and Guard screens fan out to every edge and key the answers by
 * edge URL. They used to key them by {@link FrontCacheClient#getName()}, which is the host alone - so
 * two edges on one host (the e2e topology's {@code localhost:9080} and {@code localhost:8080}) came
 * back as one, the other silently dropped.
 */
public class FrontcacheServiceByEdgeTest {

	private static FrontCacheClient client(String url) {
		return new FrontCacheClient(url, "test-api-key");
	}

	private static FrontCacheStatus status(String url) {
		FrontCacheStatus status = new FrontCacheStatus();
		status.setName("localhost");
		status.setUrl(url);
		return status;
	}

	@Test
	public void twoEdgesOnOneHostAreTwoEntriesInConfigOrder() {
		List<FrontCacheClient> clients = Arrays.asList(client("http://localhost:9080"), client("http://localhost:8080/"));
		assertEquals("the name collides", clients.get(0).getName(), clients.get(1).getName());

		List<Future<FrontCacheStatus>> answers = Arrays.<Future<FrontCacheStatus>>asList(
				CompletableFuture.completedFuture(status("http://localhost:9080")),
				CompletableFuture.completedFuture(status("http://localhost:8080/")));

		Map<String, FrontCacheStatus> byEdge = FrontcacheService.byEdge(clients, answers, "statuses");

		assertEquals(Arrays.asList("http://localhost:9080/", "http://localhost:8080/"), Arrays.asList(byEdge.keySet().toArray()));
		assertEquals("http://localhost:9080", byEdge.get("http://localhost:9080/").getUrl());
		assertEquals("http://localhost:8080/", byEdge.get("http://localhost:8080/").getUrl());
	}

	@Test
	public void anEdgeThatFailedOrAnsweredNothingIsLeftOut() {
		List<FrontCacheClient> clients = Arrays.asList(client("http://a:9080/"), client("http://b:9080/"),
				client("http://c:9080/"));
		CompletableFuture<FrontCacheStatus> failed = new CompletableFuture<FrontCacheStatus>();
		failed.completeExceptionally(new IllegalStateException("unreachable"));

		List<Future<FrontCacheStatus>> answers = Arrays.<Future<FrontCacheStatus>>asList(failed,
				CompletableFuture.completedFuture((FrontCacheStatus) null),
				CompletableFuture.completedFuture(status("http://c:9080/")));

		Map<String, FrontCacheStatus> byEdge = FrontcacheService.byEdge(clients, answers, "statuses");

		assertEquals(Arrays.asList("http://c:9080/"), Arrays.asList(byEdge.keySet().toArray()));
	}
}
