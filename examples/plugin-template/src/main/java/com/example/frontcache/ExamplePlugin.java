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
package com.example.frontcache;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.frontcache.plugin.serve.PluginRequest;
import org.frontcache.plugin.serve.PluginResponse;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

/**
 * A template plugin - one of each kind a community plugin may have, each kept tiny:
 *
 * <ul>
 * <li><b>E3 decide</b>: {@code ext:path-depth:>4} for guard and bot rules ({@link PathDepthPredicate});</li>
 * <li><b>E1 observe</b>: counts the guard's matches by disposition ({@link GuardMatchCounter});</li>
 * <li><b>E2 serve</b>: {@code GET /fc-x/example/} answers those counts as JSON - to a visitor on the node itself
 * only, everyone else gets the 404 a node without the plugin answers.</li>
 * </ul>
 *
 * Its config is {@code conf/example.conf} (the manifest's {@code config}), read whole or refused whole:
 *
 * <pre>
 * # the deepest path a rule may name in ext:path-depth
 * max-depth = 32
 * </pre>
 *
 * The node instantiates this class once per process through the public no-argument constructor, configures it, then
 * starts it; a {@code reload} is {@code stop(false)} then {@code start} on the same instance.
 */
public final class ExamplePlugin implements FrontcachePlugin {

	static final int DEFAULT_MAX_DEPTH = 32;
	static final int LIMIT_MAX_DEPTH = 1000;

	private final GuardMatchCounter counter = new GuardMatchCounter();
	private volatile int maxDepth = DEFAULT_MAX_DEPTH;

	public ExamplePlugin() {
		// cheap and side-effect free: the node may construct a plugin before any engine exists
	}

	// ---- config: parse into a new value, apply only when all of it is accepted --------------------------------

	/** The settings in example.conf - every reason it is refused, at once. */
	static int parse(Config config) throws PluginConfigException {
		List<String> reasons = new ArrayList<>();
		for (String key : config.root().keySet())
			if (!"max-depth".equals(key))
				reasons.add("unknown key '" + key + "' - example.conf has max-depth only");
		int depth = DEFAULT_MAX_DEPTH;
		if (config.hasPath("max-depth"))
		{
			try {
				depth = config.getInt("max-depth");
				if (depth < 1 || depth > LIMIT_MAX_DEPTH)
					reasons.add("max-depth must be 1.." + LIMIT_MAX_DEPTH + ", not " + depth);
			} catch (ConfigException e) {
				reasons.add("max-depth must be a number");
			}
		}
		if (!reasons.isEmpty())
			throw new PluginConfigException(reasons);
		return depth;
	}

	@Override
	public void validateConfig(Config config) throws PluginConfigException {
		parse(config);
	}

	@Override
	public void configure(Config config) throws PluginConfigException {
		maxDepth = parse(config);
	}

	// ---- life cycle --------------------------------------------------------------------------------------------

	@Override
	public void start(PluginContext context) {
		// nothing to start: no thread, no file. A plugin with background work schedules it here, on
		// context.scheduler() - its tasks are cancelled when the plugin stops.
	}

	// ---- what it contributes - read by the node after every start ---------------------------------------------

	@Override
	public List<GuardPredicateProvider> predicates() {
		return List.of(new PathDepthPredicate(maxDepth));
	}

	@Override
	public List<EventListener> eventListeners() {
		// the same instance every time: on a config reload the node keeps its queue and thread
		return List.of(counter);
	}

	@Override
	public PluginEndpoint endpoint() {
		return this::handle;
	}

	private void handle(PluginRequest request, PluginResponse response) throws IOException {
		if (!isLoopback(request.clientAddress()) || !"/".equals(request.path()) || !"GET".equals(request.method()))
		{
			response.status(404);
			response.contentType("text/plain; charset=utf-8");
			response.writer().print("Not found\n");
			return;
		}
		response.contentType("application/json");
		PrintWriter out = response.writer();
		out.print("{\"guardMatched\":{");
		boolean first = true;
		for (Map.Entry<String, Long> e : counter.counts().entrySet())
		{
			out.print((first ? "\"" : ",\"") + e.getKey().replaceAll("[^a-z0-9-]", "_") + "\":" + e.getValue());
			first = false;
		}
		out.print("},\"maxDepth\":" + maxDepth + "}");
	}

	/** The node's resolved address - through its trusted proxies only, never a header a client could set. */
	static boolean isLoopback(String address) {
		return "127.0.0.1".equals(address) || "::1".equals(address) || "0:0:0:0:0:0:0:1".equals(address);
	}

	/** For the tests. */
	Map<String, Long> counts() {
		return counter.counts();
	}
}
