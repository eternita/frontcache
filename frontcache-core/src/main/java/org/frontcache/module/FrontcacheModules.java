/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node's {@link FrontcacheModule}s - loaded once per process, started and stopped with the engine.
 *
 * Every call into a module is wrapped: a module that throws is logged, and one that throws in
 * {@link FrontcacheModule#start} is dropped until the next {@link #start}. Nothing here may stop a node
 * from serving, which is the whole of invariant 1 of docs/ip-protection-proposal.md section 3 once the
 * code that could fail lives in a jar this repository does not build.
 */
public final class FrontcacheModules {

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheModules.class);

	private static volatile List<FrontcacheModule> loaded = null;

	/** Modules whose last start did not throw; replaced wholesale, never mutated. */
	private static volatile List<FrontcacheModule> started = List.of();

	/** Action name -> action, from the started modules; replaced wholesale, never mutated. */
	private static volatile Map<String, ManagementAction> actions = Map.of();

	private static volatile NodeMode mode = null;

	private FrontcacheModules() {
	}

	/** Every module on the class path, instantiated once. A module that fails to load is skipped. */
	static List<FrontcacheModule> loaded() {

		List<FrontcacheModule> local = loaded;
		if (null != local)
			return local;

		synchronized (FrontcacheModules.class) {
			if (null == loaded)
				loaded = load(ServiceLoader.load(FrontcacheModule.class, FrontcacheModule.class.getClassLoader()));
			return loaded;
		}
	}

	static List<FrontcacheModule> load(Iterable<FrontcacheModule> source) {

		List<FrontcacheModule> modules = new ArrayList<>();
		Iterator<FrontcacheModule> it = source.iterator();
		while (true)
		{
			try {
				if (!it.hasNext())
					break;
				FrontcacheModule module = it.next();
				modules.add(module);
				logger.info("Module '{}' found ({})", module.id(), module.getClass().getName());
			} catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
				// a provider that cannot be instantiated - the node runs without it
				logger.error("A Frontcache module could not be loaded - the node starts without it", e);
			}
		}
		return Collections.unmodifiableList(modules);
	}

	/**
	 * Starts every module; called by the engine on each start, {@code reload} included. A module that
	 * throws is dropped until the next start.
	 */
	public static synchronized void start(ModuleContext context) {

		List<FrontcacheModule> ok = new ArrayList<>();
		for (FrontcacheModule module : loaded())
		{
			try {
				module.start(context);
				ok.add(module);
			} catch (Throwable t) {
				logger.error("Module '" + safeId(module) + "' failed to start - the node runs without it", t);
			}
		}

		Map<String, ManagementAction> byName = new LinkedHashMap<>();
		for (FrontcacheModule module : ok)
		{
			try {
				for (Map.Entry<String, ManagementAction> e : module.managementActions().entrySet())
				{
					if (byName.containsKey(e.getKey()))
						logger.warn("Module '{}' declares management action '{}', which another module already"
								+ " answers - the first one wins", module.id(), e.getKey());
					else
						byName.put(e.getKey(), e.getValue());
				}
			} catch (Throwable t) {
				logger.error("Module '" + safeId(module) + "' failed to list its management actions", t);
			}
		}

		started = Collections.unmodifiableList(ok);
		actions = Collections.unmodifiableMap(byName);
	}

	/** @param shutdown see {@link FrontcacheModule#stop(boolean)} */
	public static synchronized void stop(boolean shutdown) {

		for (FrontcacheModule module : started)
		{
			try {
				module.stop(shutdown);
			} catch (Throwable t) {
				logger.error("Module '" + safeId(module) + "' failed to stop", t);
			}
		}

		started = List.of();
		actions = Map.of();
	}

	/**
	 * Called by {@code FrontCacheServlet} / {@code FrontCacheFilter} as they start - possibly before the
	 * engine, so it reaches every loaded module rather than only the started ones.
	 */
	public static void modeKnown(NodeMode deployedAs) {

		if (null == deployedAs || deployedAs == mode)
			return;
		mode = deployedAs;

		for (FrontcacheModule module : loaded())
		{
			try {
				module.onModeKnown(deployedAs);
			} catch (Throwable t) {
				logger.error("Module '" + safeId(module) + "' failed on mode " + deployedAs, t);
			}
		}
	}

	/** The management action a started module answers under {@code name}, or null. */
	public static ManagementAction managementAction(String name) {
		return (null == name) ? null : actions.get(name);
	}

	/** Every module action's description, by name - for the {@code help} action. */
	public static Map<String, String> managementActionDescriptions() {

		Map<String, String> descriptions = new TreeMap<>();
		for (Map.Entry<String, ManagementAction> e : actions.entrySet())
		{
			try {
				descriptions.put(e.getKey(), e.getValue().description());
			} catch (Throwable t) {
				descriptions.put(e.getKey(), "");
			}
		}
		return descriptions;
	}

	/** The management action names the started modules answer. */
	public static Set<String> managementActionNames() {
		return actions.keySet();
	}

	/**
	 * Lets every loaded module register its meters - see {@link FrontcacheModule#bindMeters(Object)}.
	 * Loaded rather than started: the exporter may be built before the engine (a test, or a scrape
	 * arriving first), and meters read their state at scrape time anyway.
	 */
	public static void bindMeters(Object meterRegistry) {

		for (FrontcacheModule module : loaded())
		{
			try {
				module.bindMeters(meterRegistry);
			} catch (Throwable t) {
				logger.error("Module '" + safeId(module) + "' failed to register its meters", t);
			}
		}
	}

	/** The ids of the started modules, in load order - {@code get-node-stats} reports them. */
	public static List<String> ids() {

		List<String> ids = new ArrayList<>();
		for (FrontcacheModule module : started)
			ids.add(safeId(module));
		return ids;
	}

	private static String safeId(FrontcacheModule module) {
		try {
			return module.id();
		} catch (Throwable t) {
			return module.getClass().getName();
		}
	}
}
