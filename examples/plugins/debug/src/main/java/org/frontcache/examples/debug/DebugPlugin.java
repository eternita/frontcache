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
package org.frontcache.examples.debug;

import java.io.IOException;
import java.util.List;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.frontcache.plugin.serve.PluginRequest;
import org.frontcache.plugin.serve.PluginResponse;

import com.typesafe.config.Config;

/**
 * Edge debugging: {@code GET /fc-x/debug/} shows a visitor how the node sees <em>their</em> request - the address it
 * resolved, the client type bots.conf gave it, the scheme, the request id, the headers a plugin's view shows - and
 * what the guard recently decided about their address, from the {@code GuardMatched}, {@code RateLimitExceeded} and
 * {@code ChallengeIssued} events.
 *
 * <p>Only to the addresses {@code conf/debug.conf} allows (by default the node itself); anyone else gets the
 * {@code 404} a node without the plugin answers, so the page does not even say it exists. It decides nothing: it
 * reads the node's view of a request and the node's events, and serves its own path.
 */
public final class DebugPlugin implements FrontcachePlugin {

	private final RecentDecisions decisions = new RecentDecisions();
	private volatile DebugConfig config = DebugConfig.defaults();
	private volatile PluginContext context;

	public DebugPlugin() {
		// cheap and side-effect free: the node may construct a plugin before any engine exists
	}

	@Override
	public void validateConfig(Config config) throws PluginConfigException {
		DebugConfig.parse(config);
	}

	@Override
	public void configure(Config config) throws PluginConfigException {
		DebugConfig parsed = DebugConfig.parse(config);   // throws before anything is applied
		this.config = parsed;
		decisions.limits(parsed.recent(), parsed.visitors());
	}

	@Override
	public void start(PluginContext context) {
		this.context = context;
		decisions.hashWith(context::hashClientKey);
	}

	@Override
	public void stop(boolean shutdown) {
		// the decisions stay across a reload: the node's client-key hash is stable for the life of the process
	}

	@Override
	public List<EventListener> eventListeners() {
		return List.of(decisions);
	}

	@Override
	public PluginEndpoint endpoint() {
		return this::handle;
	}

	private void handle(PluginRequest request, PluginResponse response) throws IOException {
		boolean read = "GET".equals(request.method()) || "HEAD".equals(request.method());
		if (!config.allows(request.clientAddress()) || !read)
		{
			notFound(response);
			return;
		}
		switch (request.path()) {
			case "/" -> {
				response.contentType("text/html; charset=utf-8");
				response.header("Content-Security-Policy", DebugPage.CSP);
				response.header("Referrer-Policy", "no-referrer");
				response.header("X-Frame-Options", "DENY");
				PluginContext c = context;
				response.writer().print(DebugPage.render(request, decisions.about(request.clientAddress()),
						config.recent(), (null == c) ? null : c.nodeId(), (null == c) ? null : c.coreVersion(),
						(null == c) ? null : c.manifest().version()));
			}
			case "/debug.css" -> {
				response.contentType("text/css; charset=utf-8");
				response.writer().print(DebugPage.CSS);
			}
			default -> notFound(response);
		}
	}

	private static void notFound(PluginResponse response) {
		response.status(404);
		response.contentType("text/plain; charset=utf-8");
		response.writer().print("Not found\n");
	}

	/** For the tests. */
	RecentDecisions decisions() {
		return decisions;
	}
}
