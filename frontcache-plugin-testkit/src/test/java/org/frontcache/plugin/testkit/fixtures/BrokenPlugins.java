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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.GuardMatched;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.frontcache.plugin.testkit.PluginContract;
import org.frontcache.plugin.testkit.PluginHarness;

import com.typesafe.config.Config;

/**
 * Plugins that break the contract one way each, and the contract over each - run by {@code PluginContractTest}
 * through JUnitCore, never by Gradle (this package is excluded from the test task): on their own they fail.
 */
public final class BrokenPlugins {

	private BrokenPlugins() {
	}

	static PluginHarness harness(FrontcachePlugin plugin, String kinds) {
		return PluginHarness.of(plugin, PluginHarness.manifest("id = broken\nclass = " + plugin.getClass().getName()
				+ "\nversion = 1\nplugin-api = 1\nkinds = " + kinds + "\nconfig = broken.conf\n"));
	}

	static GuardPredicateProvider provider(String name, PluginPredicate predicate) {
		return new GuardPredicateProvider() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public PluginPredicate parse(String argument) {
				return predicate;
			}
		};
	}

	// ---- a predicate that busy-waits a millisecond ----------------------------------------------------------

	public static class SlowPredicate implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<GuardPredicateProvider> predicates() {
			return List.of(provider("slow", request -> {
				long until = System.nanoTime() + 1_000_000;
				while (System.nanoTime() < until)
					Thread.onSpinWait();
				return false;
			}));
		}
	}

	public static class SlowPredicateContract extends PluginContract {
		@Override
		protected Class<? extends FrontcachePlugin> pluginClass() {
			return SlowPredicate.class;
		}

		@Override
		protected PluginHarness newHarness() {
			return harness(new SlowPredicate(), "decide");
		}

		@Override
		protected Map<String, List<String>> predicateArguments() {
			return Map.of("slow", List.of("x"));
		}

		@Override
		protected Duration predicateBudget() {
			return Duration.ofNanos(200_000);
		}
	}

	// ---- a predicate that throws on a null uri ---------------------------------------------------------------

	public static class NullBlindPredicate implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<GuardPredicateProvider> predicates() {
			return List.of(provider("blind", request -> request.uri().length() > 3));
		}
	}

	public static class NullBlindPredicateContract extends PluginContract {
		@Override
		protected Class<? extends FrontcachePlugin> pluginClass() {
			return NullBlindPredicate.class;
		}

		@Override
		protected PluginHarness newHarness() {
			return harness(new NullBlindPredicate(), "decide");
		}

		@Override
		protected Map<String, List<String>> predicateArguments() {
			return Map.of("blind", List.of(""));
		}
	}

	// ---- a listener that blocks ------------------------------------------------------------------------------

	public static class BlockingListener implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<EventListener> eventListeners() {
			return List.of(new EventListener() {
				@Override
				public Set<Class<? extends FcEvent>> subscribes() {
					return Set.of(GuardMatched.class);
				}

				@Override
				public void onEvents(List<FcEvent> batch) {
					if (batch.isEmpty())
						return;
					try {
						Thread.sleep(60_000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			});
		}
	}

	public static class BlockingListenerContract extends PluginContract {
		@Override
		protected Class<? extends FrontcachePlugin> pluginClass() {
			return BlockingListener.class;
		}

		@Override
		protected PluginHarness newHarness() {
			return harness(new BlockingListener(), "observe");
		}

		@Override
		protected Duration listenerTimeout() {
			return Duration.ofMillis(200);
		}
	}

	// ---- a config check that accepts everything --------------------------------------------------------------

	public static class LaxConfig implements FrontcachePlugin {
		@Override
		public void configure(Config config) throws PluginConfigException {
			if (config.hasPath("explode"))
				throw new PluginConfigException(List.of());
		}

		@Override
		public void start(PluginContext context) {
		}
	}

	public static class LaxConfigContract extends PluginContract {
		@Override
		protected Class<? extends FrontcachePlugin> pluginClass() {
			return LaxConfig.class;
		}

		@Override
		protected PluginHarness newHarness() {
			return harness(new LaxConfig(), "manage");
		}

		@Override
		protected List<String> refusedConfigs() {
			return List.of("explode = true");
		}
	}

	// ---- an endpoint from a plugin that does not declare serve -----------------------------------------------

	public static class UndeclaredEndpoint implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
		}

		@Override
		public PluginEndpoint endpoint() {
			return (request, response) -> response.status(204);
		}
	}

	public static class UndeclaredEndpointContract extends PluginContract {
		@Override
		protected Class<? extends FrontcachePlugin> pluginClass() {
			return UndeclaredEndpoint.class;
		}

		@Override
		protected PluginHarness newHarness() {
			return harness(new UndeclaredEndpoint(), "observe");
		}
	}

	// ---- a periodic task that survives its stop ---------------------------------------------------------------

	public static class Leaky implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
			context.scheduler().scheduleAtFixedRate(() -> {
				throw new IllegalStateException("every time");
			}, 0, 10, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
	}

	public static class LeakyContract extends PluginContract {
		@Override
		protected Class<? extends FrontcachePlugin> pluginClass() {
			return Leaky.class;
		}

		@Override
		protected PluginHarness newHarness() {
			return harness(new Leaky(), "manage");
		}
	}
}
