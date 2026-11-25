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
package org.frontcache.plugin.testkit.fixtures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.frontcache.plugin.serve.PluginRequest;
import org.frontcache.plugin.serve.PluginResponse;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

/**
 * A plugin that keeps the contract - one of each kind a dropped-in plugin may contribute (decide, observe, serve)
 * and a config read whole or refused whole. {@link WellBehavedContract} runs every {@code PluginContract} test over
 * it, which is the half of {@code PluginContractTest} the broken plugins cannot show: a plugin that does nothing
 * wrong passes every one. Its manifest is on the test class path (src/test/manifests/well-behaved), as a jar's is.
 * (The plugin template and the example plugins, which used to be that half, live in their own repository now.)
 */
public final class WellBehavedPlugin implements FrontcachePlugin {

	static final int DEFAULT_MAX_LENGTH = 256;

	private final LongAdder matched = new LongAdder();
	private volatile int maxLength = DEFAULT_MAX_LENGTH;

	/** {@code max-length} - the only key; every reason it is refused, at once. */
	static int parse(Config config) throws PluginConfigException {
		List<String> reasons = new ArrayList<>();
		for (String key : config.root().keySet())
			if (!"max-length".equals(key))
				reasons.add("unknown key '" + key + "'");
		int length = DEFAULT_MAX_LENGTH;
		if (config.hasPath("max-length"))
		{
			try {
				length = config.getInt("max-length");
				if (length < 1)
					reasons.add("max-length must be at least 1, not " + length);
			} catch (ConfigException e) {
				reasons.add("max-length must be a number");
			}
		}
		if (!reasons.isEmpty())
			throw new PluginConfigException(reasons);
		return length;
	}

	@Override
	public void validateConfig(Config config) throws PluginConfigException {
		parse(config);
	}

	@Override
	public void configure(Config config) throws PluginConfigException {
		maxLength = parse(config);
	}

	@Override
	public void start(PluginContext context) {
	}

	/** {@code ext:uri-longer:<n>} - the request's URI is longer than n characters. */
	@Override
	public List<GuardPredicateProvider> predicates() {
		int limit = maxLength;
		return List.of(new GuardPredicateProvider() {
			@Override
			public String name() {
				return "uri-longer";
			}

			@Override
			public PluginPredicate parse(String argument) throws PluginConfigException {
				int n;
				try {
					n = Integer.parseInt((null == argument) ? "" : argument.trim());
				} catch (NumberFormatException e) {
					throw new PluginConfigException("ext:uri-longer: expected a length, not '" + argument + "'");
				}
				if (n < 0 || n > limit)
					throw new PluginConfigException("ext:uri-longer: " + n + " is not in 0.." + limit);
				return request -> {
					String uri = request.uri();
					return null != uri && uri.length() > n;
				};
			}
		});
	}

	private final EventListener counter = new EventListener() {
		@Override
		public Set<Class<? extends FcEvent>> subscribes() {
			return Set.of(GuardMatched.class);
		}

		@Override
		public void onEvents(List<FcEvent> batch) {
			for (FcEvent event : batch)
				if (event instanceof GuardMatched)
					matched.increment();
		}
	};

	@Override
	public List<EventListener> eventListeners() {
		return List.of(counter);
	}

	@Override
	public PluginEndpoint endpoint() {
		return this::handle;
	}

	private void handle(PluginRequest request, PluginResponse response) throws IOException {
		response.contentType("text/plain; charset=utf-8");
		if (!"/".equals(request.path()))
		{
			response.status(404);
			response.writer().print("Not found\n");
			return;
		}
		response.writer().print("matched " + matched.sum() + "\n");
	}

	/** For the tests. */
	long matched() {
		return matched.sum();
	}
}
