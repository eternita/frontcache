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

import java.util.ArrayList;
import java.util.List;

import org.frontcache.console.auth.ConsoleEdgeScope;
import org.frontcache.console.auth.ConsolePrincipal;
import org.frontcache.console.auth.EdgeScope;

/**
 * The default {@link ConsoleEdgeScope}: every principal sees the console's configured {@code urls}, each called with
 * the one {@code apiKey} - the console as it has always been - and then, as of 2.13.0, every {@code edges} entry, each
 * called with <b>its own</b> key. Read live, so an edge added on the Edges screen is in scope from then on. In this
 * package because it is the only reader of {@link FrontcacheService}'s edge list and keys.
 *
 * <b>This is what keeps each key to its own edges</b>: every request reaches an edge through the scope's
 * {@link EdgeScope.Edge}, which carries that edge's key - never through the console-wide one.
 * {@code EdgeKeyIsolationTest} calls every handler with every edge and has each node check the key it was sent.
 */
public final class ConfiguredEdgeScope implements ConsoleEdgeScope {

	/** Every principal shares this scope, so they share its cached results - which is today's behaviour. */
	public static final String ID = "console";

	private final FrontcacheService frontcacheService;

	public ConfiguredEdgeScope(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
	}

	@Override
	public EdgeScope scopeFor(ConsolePrincipal who) {
		return everyEdge();
	}

	@Override
	public EdgeScope everyEdge() {
		String apiKey = frontcacheService.configuredApiKey();
		List<EdgeScope.Edge> edges = new ArrayList<EdgeScope.Edge>();
		for (String url : frontcacheService.configuredEdgeURLs())
			if (null != url)
				edges.add(new EdgeScope.Edge(url, apiKey));
		edges.addAll(frontcacheService.configuredKeyedEdges());
		return EdgeScope.of(ID, edges);
	}

	@Override
	public boolean edgeEditing() {
		return true;
	}
}
