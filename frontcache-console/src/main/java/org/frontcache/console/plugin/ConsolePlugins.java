/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * The console's {@link ConsolePlugin}s - loaded once per process, before either Spring context, and read
 * by the import selectors and the JSPs (as the {@code fcPlugins} application
 * attribute, see {@link #view()}).
 *
 * A plugin that fails to load is logged and skipped: the console starts without it, the way a node starts
 * without a module that throws.
 */
public final class ConsolePlugins {

	private static final Logger logger = LoggerFactory.getLogger(ConsolePlugins.class);

	/**
	 * Keys under {@code frontcache.console} that only a plugin reads, with what needs them. A console whose
	 * config sets one that no loaded plugin claims warns once at startup - silently ignoring a configured
	 * feature is how an operator concludes it is broken (proposal section 9).
	 */
	static final Map<String, String> PLUGIN_ONLY_KEYS = Map.of(
			"clusters", "the cluster request rate on the realtime monitor",
			"cache-warmer", "the Cache Warmer screen",
			"usage-dir", "the License screen's node count");

	private static volatile List<ConsolePlugin> plugins = null;

	private ConsolePlugins() {
	}

	public static List<ConsolePlugin> plugins() {

		List<ConsolePlugin> local = plugins;
		if (null != local)
			return local;

		synchronized (ConsolePlugins.class) {
			if (null == plugins)
				plugins = load(ServiceLoader.load(ConsolePlugin.class, ConsolePlugin.class.getClassLoader()));
			return plugins;
		}
	}

	static List<ConsolePlugin> load(Iterable<ConsolePlugin> source) {

		List<ConsolePlugin> loaded = new ArrayList<ConsolePlugin>();
		Iterator<ConsolePlugin> it = source.iterator();
		while (true)
		{
			try {
				if (!it.hasNext())
					break;
				ConsolePlugin plugin = it.next();
				loaded.add(plugin);
				logger.info(" -- Console plugin '{}' found ({})", plugin.id(), plugin.getClass().getName());
			} catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
				logger.error("A console plugin could not be loaded - the console starts without it", e);
			}
		}
		return Collections.unmodifiableList(loaded);
	}

	static List<String> rootComponentNames() {
		List<String> names = new ArrayList<String>();
		for (ConsolePlugin plugin : plugins())
			for (Class<?> c : safe(plugin::rootComponents))
				names.add(c.getName());
		return names;
	}

	static List<String> webComponentNames() {
		List<String> names = new ArrayList<String>();
		for (ConsolePlugin plugin : plugins())
			for (Class<?> c : safe(plugin::webComponents))
				names.add(c.getName());
		return names;
	}

	/** What the JSPs read - see {@link View}. */
	public static View view() {

		View view = new View();
		for (ConsolePlugin plugin : plugins())
		{
			for (NavEntry entry : safe(plugin::navEntries))
			{
				if (NavEntry.TOP.equals(entry.getSection()))
					view.topNav.add(entry);
				else if (NavEntry.CACHE.equals(entry.getSection()))
					view.cacheNav.add(entry);
				else
					logger.warn("Console plugin '{}': nav entry '{}' names an unknown section '{}' - not shown",
							plugin.id(), entry.getLabel(), entry.getSection());
			}
			view.dashboardScripts.addAll(safe(plugin::dashboardScripts));
			view.stylesheets.addAll(safe(plugin::stylesheets));
		}
		return view;
	}

	/**
	 * Warns, once per key, about a plugin-only key the config sets and no loaded plugin claims - see
	 * {@link #PLUGIN_ONLY_KEYS}. WARN, not INFO: the shipped fc-logback.xml filters stdout at WARN.
	 */
	public static void warnUnclaimedKeys(Config consoleConfig) {

		if (null == consoleConfig)
			return;

		Set<String> claimed = new LinkedHashSet<String>();
		for (ConsolePlugin plugin : plugins())
			claimed.addAll(safe(plugin::configKeys));

		for (Map.Entry<String, String> key : PLUGIN_ONLY_KEYS.entrySet())
			if (consoleConfig.hasPath(key.getKey()) && !claimed.contains(key.getKey()))
				logger.warn("frontcache-console.conf sets '{}', which is read by {} - a Pro console plugin this"
						+ " console does not have. It is ignored.", key.getKey(), key.getValue());
	}

	private interface Getter<T> {
		T get();
	}

	/** A plugin's list, or empty when its getter throws - one plugin's bug must not take the console down. */
	private static <T> List<T> safe(Getter<? extends java.util.Collection<T>> getter) {
		try {
			java.util.Collection<T> value = getter.get();
			return (null == value) ? List.of() : new ArrayList<T>(value);
		} catch (RuntimeException | LinkageError e) {
			logger.error("A console plugin failed to describe itself - that part of it is skipped", e);
			return List.of();
		}
	}

	/**
	 * The plugins' menu entries, scripts and stylesheets for the JSPs - a class with getters, for EL. Set as
	 * the {@code fcPlugins} servlet-context attribute by {@code WebMVCConfig}, so every JSP reads it with no
	 * per-request work.
	 */
	public static final class View {

		private final List<NavEntry> topNav = new ArrayList<NavEntry>();
		private final List<NavEntry> cacheNav = new ArrayList<NavEntry>();
		private final List<String> dashboardScripts = new ArrayList<String>();
		private final List<String> stylesheets = new ArrayList<String>();

		public List<NavEntry> getTopNav() {
			return topNav;
		}

		public List<NavEntry> getCacheNav() {
			return cacheNav;
		}

		public List<String> getDashboardScripts() {
			return dashboardScripts;
		}

		public List<String> getStylesheets() {
			return stylesheets;
		}
	}
}
