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
package org.frontcache.plugin.host;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.frontcache.FCConfig;
import org.frontcache.guard.GuardRule;
import org.frontcache.io.PluginStats;
import org.frontcache.license.BuildInfo;
import org.frontcache.module.FrontcacheModule;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.NodeMode;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginKind;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.RequestCompleted;
import org.frontcache.plugin.serve.PluginEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node's plugins - found once per process, started and stopped with the engine.
 *
 * <h2>Finding them</h2>
 *
 * Every {@value PluginManifest#RESOURCE} on the core's class path names a plugin's entry class; the class is
 * instantiated through its no-argument constructor. A pre-2.13 {@code FrontcacheModule} found through its
 * {@code META-INF/services} file and carrying no manifest is run through {@link LegacyModulePlugin} as a
 * {@code manage} plugin, with a deprecation WARN. A manifest that is refused, a class that cannot be loaded, a
 * duplicate id, an unsupported {@code plugin-api} major or a kind this node does not offer: logged, reported in
 * {@code get-node-stats}, and <b>the node starts without that plugin</b>.
 *
 * <h2>A plugin that fails is a plugin that is absent</h2>
 *
 * Every call into a plugin is wrapped. One that throws in {@code start} is dropped until the next start; one that
 * contributes something of a kind it did not declare (an event listener without {@code observe}, an endpoint without
 * {@code serve}, a management action without {@code manage}) is refused and stopped. Nothing here may stop a node
 * from serving. A started plugin's endpoint answers {@code /fc-x/<id>/} through {@link PluginEndpoints}.
 *
 * <h2>A plugin's config</h2>
 *
 * A manifest's {@code config} names {@code conf/<file>}, which no other plugin and no core file may be. It is read,
 * parsed and resolved here ({@link PluginConfigs}) and handed to {@link FrontcachePlugin#configure} before every
 * start; a refusal there is a plugin that does not start. {@link #reloadConfig} re-reads it for a running plugin
 * (which keeps its old config when the new one is refused, and has its event listeners read again when it accepts
 * it) and starts one that failed on its config.
 */
public final class FrontcachePlugins {

	private static final Logger logger = LoggerFactory.getLogger(FrontcachePlugins.class);

	/** The kinds this node offers today; a plugin declaring another is refused at load. */
	static final Set<PluginKind> OFFERED = Collections.unmodifiableSet(
			java.util.EnumSet.of(PluginKind.OBSERVE, PluginKind.SERVE, PluginKind.DECIDE, PluginKind.MANAGE));

	/** {@code front-cache.plugins.<id>.queue}: a listener's queue capacity. */
	static final String QUEUE_PROPERTY = "front-cache.plugins.%s.queue";

	/** {@code front-cache.plugins.<id>.request-sample}: the share of requests its listeners get as RequestCompleted. */
	static final String REQUEST_SAMPLE_PROPERTY = "front-cache.plugins.%s.request-sample";

	static final String CONFIG_OK = "ok";
	static final String CONFIG_ABSENT = "absent";
	static final String CONFIG_REFUSED = "refused";

	static final long GUARD_SUMMARY_INTERVAL_MILLIS = 10_000L;

	enum State {
		LOADED, STARTED, FAILED, REFUSED, STOPPED,
		/** A dropped-in plugin's directory that {@code front-cache.plugins} does not list: found, nothing of it read. */
		NOT_ENABLED;

		String label() {
			return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
		}
	}

	/** One plugin as found: its manifest (null when the manifest itself was refused) and instance (null likewise). */
	static final class Slot {
		final String id;
		final PluginManifest manifest;
		final FrontcachePlugin plugin;
		final boolean legacy;
		final PluginCounters counters = new PluginCounters();
		volatile State state;
		volatile String reason;
		/** The last read of the plugin's config - see PluginStats.getConfigStatus; null before the first. */
		volatile String configStatus;
		volatile List<String> configErrors = List.of();
		volatile long configLoaded;
		/** Its last start failed on its config: {@link #reloadConfig} may start it. */
		volatile boolean failedOnConfig;
		/** A dropped-in plugin's directory ({@code plugins/<id>}); null for a bundled one. */
		volatile String directory;
		volatile List<String> jars = List.of();
		volatile boolean pinned;
		/** {@code decide}: the budget its predicates are timed against - the process's, kept across starts. */
		volatile DecideBudget budget;
		/** The scheduler handed to its current start; shut down when it stops. */
		volatile PluginScheduler scheduler;
		/** {@code decide}: what its current start offers. */
		volatile List<PluginPredicates.Provider> offered = List.of();

		Slot(String id, PluginManifest manifest, FrontcachePlugin plugin, boolean legacy, State state, String reason) {
			this.id = id;
			this.manifest = manifest;
			this.plugin = plugin;
			this.legacy = legacy;
			this.state = state;
			this.reason = reason;
		}

		/** Refused for good (manifest, class, kinds), or not enabled: never started, whatever the engine does. */
		boolean refused() {
			return null == plugin || State.REFUSED == state || State.NOT_ENABLED == state;
		}

		/** Marks it as found in a plugin directory. */
		void droppedIn(String directory, List<String> jars, boolean pinned) {
			this.directory = directory;
			this.jars = List.copyOf(jars);
			this.pinned = pinned;
		}

		boolean isDroppedIn() {
			return null != directory;
		}
	}

	private static volatile List<Slot> loaded = null;

	/** Plugins whose last start succeeded; replaced wholesale, never mutated. */
	private static volatile List<Slot> started = List.of();

	/** Action name -> action, from the started plugins; replaced wholesale, never mutated. */
	private static volatile Map<String, ManagementAction> actions = Map.of();

	private static volatile NodeMode mode = null;

	/** Whether dropped-in plugins were read from {@code FRONTCACHE_HOME/plugins} before the mode was known. */
	private static volatile boolean droppedInByDefault = false;

	/** Whether the engine has started the plugins and not stopped them - and with what. */
	private static boolean running = false;
	private static String runningDomain = null;
	private static Supplier<List<GuardRule>> runningGuardRules = List::of;

	private FrontcachePlugins() {
	}

	/** Every plugin on the core's class path, found once. */
	static List<Slot> loaded() {

		List<Slot> local = loaded;
		if (null != local)
			return local;

		synchronized (FrontcachePlugins.class) {
			if (null == loaded)
				loaded = loadAll(FrontcachePlugin.class.getClassLoader());
			return loaded;
		}
	}

	/** For tests: replaces what was found. */
	static synchronized void install(List<Slot> slots) {
		stop(true);
		loaded = Collections.unmodifiableList(new ArrayList<>(slots));
		mode = null;
	}

	/** The bundled plugins, then the dropped-in ones. Never throws. */
	static List<Slot> loadAll(ClassLoader core) {

		List<Slot> slots = new ArrayList<>(load(core));
		File dir;
		Set<String> enabled;
		try {
			dir = PluginDirectory.directory(mode);
			enabled = PluginDirectory.enabled();
		} catch (Throwable t) {
			logger.error("Could not read the plugin directory's settings - the node starts without dropped-in plugins", t);
			return Collections.unmodifiableList(slots);
		}
		if (null == dir && enabled.isEmpty())
			return Collections.unmodifiableList(slots);

		List<Slot> droppedIn;
		try {
			droppedIn = PluginDirectory.scan(dir, core, slots, enabled);
		} catch (Throwable t) {
			logger.error("Could not read the plugin directory " + dir + " - the node starts without dropped-in plugins", t);
			droppedIn = List.of();
		}
		for (Slot slot : droppedIn)
		{
			if (State.NOT_ENABLED == slot.state)
				continue; // said at INFO by the scan
			if (slot.refused())
				logger.error("Plugin '{}' ({}) is not loaded - the node starts without it: {}", slot.id, slot.directory,
						slot.reason);
			else
				logger.info("Plugin '{}' found in {}: {} ({}{})", slot.id, slot.directory, slot.manifest,
						slot.plugin.getClass().getName(), slot.pinned ? "; pinned" : "");
		}
		if (!droppedIn.isEmpty() && null == mode && PluginDirectory.isDefault())
			droppedInByDefault = true;
		slots.addAll(droppedIn);
		return Collections.unmodifiableList(slots);
	}

	/** Finds the plugins a class loader sees - their manifests, then the legacy modules. Never throws. */
	static List<Slot> load(ClassLoader loader) {

		List<Slot> slots = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Set<String> classes = new HashSet<>();
		Set<String> configs = new HashSet<>();

		Enumeration<URL> manifests;
		try {
			manifests = loader.getResources(PluginManifest.RESOURCE);
		} catch (IOException e) {
			logger.error("Could not list " + PluginManifest.RESOURCE + " - the node starts without plugins", e);
			manifests = Collections.emptyEnumeration();
		}

		while (manifests.hasMoreElements())
		{
			URL url = manifests.nextElement();
			Slot slot = fromManifest(url, loader, ids, configs);
			slots.add(slot);
			if (null != slot.manifest)
			{
				ids.add(slot.id);
				classes.add(slot.manifest.entryClass());
				if (null != slot.manifest.config())
					configs.add(slot.manifest.config());
			}
		}

		slots.addAll(legacyModules(loader, ids, classes));

		for (Slot slot : slots)
			if (slot.refused())
				logger.error("Plugin '{}' is not loaded - the node starts without it: {}", slot.id, slot.reason);
			else if (slot.legacy)
				logger.warn("Plugin '{}' found: {} - a FrontcacheModule without a manifest, run through the deprecated"
						+ " adapter for one release; give it {}", slot.id, slot.plugin.getClass().getName(),
						PluginManifest.RESOURCE);
			else
				logger.info("Plugin '{}' found: {} ({})", slot.id, slot.manifest, slot.plugin.getClass().getName());
		return Collections.unmodifiableList(slots);
	}

	static Slot fromManifest(URL url, ClassLoader loader, Set<String> ids, Set<String> configs) {

		Properties properties = new Properties();
		try (InputStream in = url.openStream(); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException | RuntimeException e) {
			return new Slot(String.valueOf(url), null, null, false, State.REFUSED, "manifest unreadable: " + e);
		}

		String declaredId = properties.getProperty("id", String.valueOf(url)).trim();
		PluginManifest manifest;
		try {
			manifest = PluginManifest.parse(properties, String.valueOf(url));
		} catch (IllegalArgumentException e) {
			return new Slot(declaredId, null, null, false, State.REFUSED, e.getMessage());
		}
		for (String warning : manifest.warnings())
			logger.warn("Plugin '{}' ({}): {}", manifest.id(), url, warning);

		String refusal = null;
		if (ids.contains(manifest.id()))
			refusal = "another plugin already has the id '" + manifest.id() + "'";
		else if (null != manifest.config() && PluginConfigs.CORE_FILES.contains(manifest.config()))
			refusal = "its config, conf/" + manifest.config() + ", is a file the core reads";
		else if (null != manifest.config() && configs.contains(manifest.config()))
			refusal = "another plugin already has the config conf/" + manifest.config();
		else if (PluginManifest.PLUGIN_API != manifest.pluginApi())
			refusal = "built for plugin-api " + manifest.pluginApi() + "; this node offers plugin-api "
					+ PluginManifest.PLUGIN_API;
		else
			for (PluginKind kind : manifest.kinds())
				if (!OFFERED.contains(kind))
				{
					refusal = "declares kind '" + kind.manifestName() + "', which this node does not offer";
					break;
				}
		if (null != refusal)
			return new Slot(manifest.id(), manifest, null, false, State.REFUSED, refusal);

		try {
			Class<?> type = Class.forName(manifest.entryClass(), true, loader);
			if (!FrontcachePlugin.class.isAssignableFrom(type))
				return new Slot(manifest.id(), manifest, null, false, State.REFUSED,
						manifest.entryClass() + " does not implement " + FrontcachePlugin.class.getName());
			FrontcachePlugin plugin = (FrontcachePlugin) type.getDeclaredConstructor().newInstance();
			return new Slot(manifest.id(), manifest, plugin, false, State.LOADED, null);
		} catch (Throwable t) {
			return new Slot(manifest.id(), manifest, null, false, State.REFUSED,
					"cannot instantiate " + manifest.entryClass() + ": " + t);
		}
	}

	@SuppressWarnings("deprecation")
	private static List<Slot> legacyModules(ClassLoader loader, Set<String> ids, Set<String> classes) {

		List<Slot> slots = new ArrayList<>();
		Iterator<FrontcacheModule> it = ServiceLoader.load(FrontcacheModule.class, loader).iterator();
		while (true)
		{
			FrontcacheModule module;
			try {
				if (!it.hasNext())
					break;
				module = it.next();
			} catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
				logger.error("A Frontcache module could not be loaded - the node starts without it", e);
				continue;
			}

			if (classes.contains(module.getClass().getName()))
				continue; // it has a manifest as well, and was loaded from it

			String id;
			try {
				id = module.id();
			} catch (Throwable t) {
				id = module.getClass().getName();
			}
			PluginManifest manifest = PluginManifest.forLegacyModule(id, module.getClass().getName(),
					BuildInfo.current().getVersion(), "META-INF/services/" + FrontcacheModule.class.getName());
			if (ids.contains(id))
				slots.add(new Slot(id, manifest, null, true, State.REFUSED, "another plugin already has the id '" + id + "'"));
			else
			{
				ids.add(id);
				slots.add(new Slot(id, manifest, new LegacyModulePlugin(module), true, State.LOADED, null));
			}
		}
		return slots;
	}

	/**
	 * Starts every plugin; called by the engine on each start, {@code reload} included.
	 *
	 * @param publicDomain {@code FrontCacheEngine.getPublicDomain()}, null when unset
	 * @param guardRules   the engine's current guard rules, for the guard summary
	 */
	public static synchronized void start(String publicDomain, Supplier<List<GuardRule>> guardRules) {

		CoreEvents.setSite(publicDomain);
		running = true;
		runningDomain = publicDomain;
		runningGuardRules = (null == guardRules) ? List::of : guardRules;

		List<Slot> ok = new ArrayList<>();
		Map<String, ManagementAction> byName = new LinkedHashMap<>();
		Map<String, PluginEndpoints.Served> served = new LinkedHashMap<>();

		for (Slot slot : loaded())
		{
			if (slot.refused())
				continue;
			if (startOne(slot, publicDomain, byName, served))
				ok.add(slot);
		}

		started = Collections.unmodifiableList(ok);
		actions = Collections.unmodifiableMap(byName);
		PluginEndpoints.install(served);
		PluginPredicates.install(offered(ok));

		FcEventBus.startGuardSummary(guardRules, GUARD_SUMMARY_INTERVAL_MILLIS);

		for (Slot slot : loaded())
			CoreEvents.pluginStateChanged(slot.id, slot.state.label(), slot.reason);
	}

	private static boolean startOne(Slot slot, String publicDomain, Map<String, ManagementAction> byName,
			Map<String, PluginEndpoints.Served> served) {

		FrontcachePlugin plugin = slot.plugin;
		slot.failedOnConfig = false;
		if (null != slot.manifest.config() && !configure(slot))
		{
			slot.failedOnConfig = true;
			fail(slot, State.FAILED, "conf/" + slot.manifest.config() + " is refused: "
					+ String.join("; ", slot.configErrors), null);
			return false;
		}

		PluginScheduler scheduler = new PluginScheduler(slot.id, slot.counters);
		slot.scheduler = scheduler;
		if (slot.manifest.declares(PluginKind.DECIDE) && null == slot.budget)
			slot.budget = new DecideBudget(slot.id);
		try {
			CorePluginContext context = new CorePluginContext(slot.manifest, publicDomain, scheduler);
			plugin.start(slot.isDroppedIn() ? new DroppedInPluginContext(context) : context);
		} catch (Throwable t) {
			slot.counters.errors.increment();
			fail(slot, State.FAILED, "start threw " + t, t);
			scheduler.shutdown();
			return false;
		}

		List<EventListener> listeners;
		Map<String, ManagementAction> own;
		PluginEndpoint endpoint;
		List<GuardPredicateProvider> predicates;
		try {
			listeners = nonNull(plugin.eventListeners());
			own = nonNull(plugin.managementActions());
			endpoint = plugin.endpoint();
			predicates = nonNull(plugin.predicates());
		} catch (Throwable t) {
			slot.counters.errors.increment();
			fail(slot, State.FAILED, "listing its contributions threw " + t, t);
			stopQuietly(slot, false);
			return false;
		}

		String refusal = null;
		if (!listeners.isEmpty() && !slot.manifest.declares(PluginKind.OBSERVE))
			refusal = "it has event listeners but does not declare kind 'observe'";
		else if (!own.isEmpty() && !slot.manifest.declares(PluginKind.MANAGE))
			refusal = "it has management actions but does not declare kind 'manage'";
		else if (null != endpoint && !slot.manifest.declares(PluginKind.SERVE))
			refusal = "it has an endpoint but does not declare kind 'serve'";
		else if (!predicates.isEmpty() && !slot.manifest.declares(PluginKind.DECIDE))
			refusal = "it has predicates but does not declare kind 'decide'";
		List<PluginPredicates.Provider> offers = List.of();
		if (null == refusal)
		{
			try {
				offers = providers(slot, predicates);
			} catch (IllegalArgumentException e) {
				refusal = e.getMessage();
			}
		}
		if (null != refusal)
		{
			fail(slot, State.REFUSED, refusal, null);
			stopQuietly(slot, false);
			return false;
		}
		slot.offered = offers;
		if (slot.manifest.declares(PluginKind.DECIDE) && offers.isEmpty())
			logger.warn("Plugin '{}' declares kind 'decide' but offers no predicate", slot.id);

		subscribe(slot, listeners, false);

		if (null != endpoint)
			served.put(slot.id, PluginEndpoints.served(slot.id, slot.manifest, endpoint, slot.counters));
		else if (slot.manifest.declares(PluginKind.SERVE))
			logger.warn("Plugin '{}' declares kind 'serve' but has no endpoint - {}{}/ answers 404{}", slot.id,
					PluginEndpoints.PREFIX, slot.id, slot.manifest.challenges().isEmpty() ? ""
							: ", and its challenge providers " + slot.manifest.challenges() + " are absent");

		for (Map.Entry<String, ManagementAction> e : own.entrySet())
		{
			if (byName.containsKey(e.getKey()))
				logger.warn("Plugin '{}' declares management action '{}', which another plugin already answers - the"
						+ " first one wins", slot.id, e.getKey());
			else
				byName.put(e.getKey(), e.getValue());
		}

		slot.state = State.STARTED;
		slot.reason = null;
		logger.info("Plugin '{}' started: {}", slot.id, slot.manifest);
		return true;
	}

	/**
	 * A plugin's predicates as offered names. A name that is not one refuses the plugin (an author's mistake, found at
	 * its first start); a name it offers twice is the first one.
	 */
	private static List<PluginPredicates.Provider> providers(Slot slot, List<GuardPredicateProvider> predicates) {

		List<PluginPredicates.Provider> offers = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (GuardPredicateProvider provider : predicates)
		{
			if (null == provider)
				continue;
			String name;
			try {
				name = provider.name();
			} catch (Throwable t) {
				slot.counters.errors.increment();
				throw new IllegalArgumentException("a predicate's name() threw " + t);
			}
			if (null == name || !org.frontcache.guard.ExtPredicate.NAME.matcher(name).matches())
				throw new IllegalArgumentException("it offers a predicate named '" + name + "', which is not a name ("
						+ org.frontcache.guard.ExtPredicate.NAME.pattern() + ")");
			if (names.add(name))
				offers.add(new PluginPredicates.Provider(name, slot.id, provider, slot.budget));
		}
		return Collections.unmodifiableList(offers);
	}

	/** What the started plugins offer, by name - the first plugin loaded wins a name two offer. */
	private static Map<String, PluginPredicates.Provider> offered(List<Slot> slots) {

		Map<String, PluginPredicates.Provider> byName = new LinkedHashMap<>();
		for (Slot slot : slots)
			for (PluginPredicates.Provider provider : slot.offered)
			{
				PluginPredicates.Provider first = byName.putIfAbsent(provider.name(), provider);
				if (null != first && first != provider)
					logger.warn("Plugin '{}' offers predicate 'ext:{}', which plugin '{}' already offers - the first one wins",
							slot.id, provider.name(), first.plugin());
			}
		return byName;
	}

	/**
	 * Subscribes a started plugin's listeners to the event bus - or, on a reload of its config, re-subscribes them:
	 * a new config may want other events, and {@link FrontcachePlugin#eventListeners()} is read again for it.
	 */
	private static void subscribe(Slot slot, List<EventListener> listeners, boolean replace) {

		int capacity = queueCapacity(slot.id);
		double sample = requestSample(slot.id);
		long bits = 0L;
		if (replace)
		{
			try {
				bits = FcEventBus.resubscribe(slot.id, listeners, capacity, sample, slot.counters);
			} catch (Throwable t) {
				slot.counters.errors.increment();
				logger.error("Plugin '" + slot.id + "' has an event listener that could not be subscribed", t);
			}
		}
		else
		{
			for (EventListener listener : listeners)
			{
				try {
					bits |= FcEventBus.subscribe(slot.id, listener, capacity, sample, slot.counters);
				} catch (Throwable t) {
					slot.counters.errors.increment();
					logger.error("Plugin '" + slot.id + "' has an event listener that could not be subscribed", t);
				}
			}
		}

		boolean samplesRequests = 0L != (bits & FcEventBus.REQUEST_COMPLETED);
		if (samplesRequests && 1.0 <= sample)
			logger.warn("Plugin '{}' receives every request as a RequestCompleted event ({} = 1.0) - one event per"
					+ " request; a full queue drops and counts", slot.id, String.format(REQUEST_SAMPLE_PROPERTY, slot.id));
		else if (samplesRequests)
			logger.info("Plugin '{}' receives {}% of requests as RequestCompleted events ({})", slot.id, sample * 100,
					String.format(REQUEST_SAMPLE_PROPERTY, slot.id));
	}

	/**
	 * After a running plugin accepted a new config: its listeners again, since what it observes may have changed. A
	 * plugin that now hands over listeners without declaring {@code observe} keeps none - it is not stopped for it, as
	 * it is running on a config it accepted.
	 */
	private static void resubscribe(Slot slot) {

		List<EventListener> listeners;
		try {
			listeners = nonNull(slot.plugin.eventListeners());
		} catch (Throwable t) {
			slot.counters.errors.increment();
			logger.error("Plugin '" + slot.id + "' threw listing its event listeners after a config reload - it keeps"
					+ " the ones it had", t);
			return;
		}
		if (!listeners.isEmpty() && !slot.manifest.declares(PluginKind.OBSERVE))
		{
			logger.error("Plugin '{}' has event listeners after a config reload but does not declare kind 'observe'"
					+ " - none is subscribed", slot.id);
			listeners = List.of();
		}
		subscribe(slot, listeners, true);
		FcEventBus.startGuardSummary(runningGuardRules, GUARD_SUMMARY_INTERVAL_MILLIS);
	}

	/**
	 * After a running plugin accepted a new config: its predicates again, since a config may change what they answer -
	 * and every {@code ext:} predicate binds afresh (its argument compiled again) at its next use.
	 */
	private static void repredicate(Slot slot) {

		if (!slot.manifest.declares(PluginKind.DECIDE))
			return;
		try {
			List<GuardPredicateProvider> predicates = nonNull(slot.plugin.predicates());
			slot.offered = providers(slot, predicates);
		} catch (Throwable t) {
			slot.counters.errors.increment();
			logger.error("Plugin '" + slot.id + "' threw listing its predicates after a config reload - it keeps the ones"
					+ " it had", t);
			return;
		}
		PluginPredicates.install(offered(started));
	}

	/**
	 * Reads the slot's config and hands it to the plugin.
	 *
	 * @return whether the plugin accepted it; the slot's config fields say what happened either way
	 */
	private static boolean configure(Slot slot) {

		String file = slot.manifest.config();
		PluginConfigs.Read read;
		try {
			read = PluginConfigs.read(file);
		} catch (PluginConfigException e) {
			configRefused(slot, e.reasons());
			return false;
		}

		try {
			slot.plugin.configure(read.config());
		} catch (PluginConfigException e) {
			configRefused(slot, e.reasons());
			return false;
		} catch (Throwable t) {
			slot.counters.errors.increment();
			logger.error("Plugin '" + slot.id + "' threw from configure", t);
			configRefused(slot, List.of("configure threw " + t));
			return false;
		}

		slot.configStatus = read.absent() ? CONFIG_ABSENT : CONFIG_OK;
		slot.configErrors = List.of();
		slot.configLoaded = System.currentTimeMillis();
		if (read.absent())
			logger.info("Plugin '{}': conf/{} is absent - configured with an empty config", slot.id, file);
		else
			logger.info("Plugin '{}': conf/{} accepted", slot.id, file);
		return true;
	}

	private static void configRefused(Slot slot, List<String> reasons) {
		slot.configStatus = CONFIG_REFUSED;
		slot.configErrors = (null == reasons || reasons.isEmpty()) ? List.of("refused, with no reason given")
				: List.copyOf(reasons);
		logger.error("Plugin '{}': conf/{} is refused - {}", slot.id, slot.manifest.config(),
				String.join("; ", slot.configErrors));
	}

	/** What {@link #reloadConfig} did. */
	public record ConfigReload(boolean ok, String file, String state, List<String> errors) {
	}

	/**
	 * {@code reload-plugin-config}: re-reads a plugin's {@code conf/<config>}. A running plugin is
	 * {@linkplain FrontcachePlugin#configure configured} with it - and keeps the config it had when it refuses the
	 * new one; a plugin whose last start failed on its config is started with it. Emits {@code ConfigReloaded}.
	 *
	 * @return the outcome; never null
	 */
	public static synchronized ConfigReload reloadConfig(String id) {

		Slot slot = configurable(id);
		if (null == slot)
			return new ConfigReload(false, null, null, List.of(noConfigReason(id)));
		String file = slot.manifest.config();

		boolean ok;
		if (State.STARTED == slot.state)
		{
			ok = configure(slot);
			if (ok)
			{
				resubscribe(slot);
				repredicate(slot);
			}
		}
		else if (running && State.FAILED == slot.state && slot.failedOnConfig)
		{
			Map<String, ManagementAction> byName = new LinkedHashMap<>(actions);
			Map<String, PluginEndpoints.Served> served = new LinkedHashMap<>();
			ok = startOne(slot, runningDomain, byName, served);
			if (ok)
			{
				if (!served.isEmpty())
					PluginEndpoints.add(served);
				List<Slot> next = new ArrayList<>(started);
				next.add(slot);
				started = Collections.unmodifiableList(next);
				if (!slot.offered.isEmpty())
					PluginPredicates.install(offered(started));
				actions = Collections.unmodifiableMap(byName);
				FcEventBus.startGuardSummary(runningGuardRules, GUARD_SUMMARY_INTERVAL_MILLIS);
				CoreEvents.pluginStateChanged(slot.id, slot.state.label(), slot.reason);
			}
		}
		else
		{
			return new ConfigReload(false, file, slot.state.label(), List.of("plugin '" + id + "' is "
					+ slot.state.label() + ((null == slot.reason) ? "" : " (" + slot.reason + ")")
					+ " - its config is read when it starts"));
		}

		// a config accepted by a plugin whose start then threw is not a config error - the slot's reason says why
		List<String> errors = ok ? List.of()
				: !slot.configErrors.isEmpty() ? slot.configErrors : List.of(String.valueOf(slot.reason));
		CoreEvents.configReloaded(file, ok, -1, errors);
		return new ConfigReload(ok, file, slot.state.label(), errors);
	}

	/**
	 * Whether a config the caller wants to write would be accepted: it parses, resolves, and the plugin's
	 * {@link FrontcachePlugin#validateConfig} accepts it. Touches nothing the plugin runs on.
	 *
	 * @return every reason it would be refused; empty when it would not
	 */
	public static List<String> validateConfig(String id, String content) {

		Slot slot = configurable(id);
		if (null == slot)
			return List.of(noConfigReason(id));

		try {
			slot.plugin.validateConfig(PluginConfigs.parse(slot.manifest.config(), (null == content) ? "" : content));
			return List.of();
		} catch (PluginConfigException e) {
			return e.reasons().isEmpty() ? List.of("refused, with no reason given") : e.reasons();
		} catch (Throwable t) {
			slot.counters.errors.increment();
			logger.error("Plugin '" + id + "' threw from validateConfig", t);
			return List.of("validateConfig threw " + t);
		}
	}

	/**
	 * The {@code conf/} file a loaded plugin's manifest names - never composed from a caller's input - or null when
	 * there is no such plugin, it was refused, or it has no config.
	 */
	public static String configFile(String id) {
		Slot slot = configurable(id);
		return (null == slot) ? null : slot.manifest.config();
	}

	private static Slot configurable(String id) {
		if (null == id)
			return null;
		for (Slot slot : loaded())
			if (slot.id.equals(id.trim()))
				return (slot.refused() || null == slot.manifest || null == slot.manifest.config()) ? null : slot;
		return null;
	}

	private static String noConfigReason(String id) {
		for (Slot slot : loaded())
			if (slot.id.equals(id))
				return slot.refused() ? "plugin '" + id + "' is refused: " + slot.reason
						: "plugin '" + id + "' has no config";
		return "no plugin '" + id + "' on this node";
	}

	private static void fail(Slot slot, State state, String reason, Throwable t) {
		slot.state = state;
		slot.reason = reason;
		if (null == t)
			logger.error("Plugin '{}' {} - the node runs without it: {}", slot.id, state.label(), reason);
		else
			logger.error("Plugin '" + slot.id + "' " + state.label() + " - the node runs without it", t);
	}

	private static int queueCapacity(String id) {
		String value = FCConfig.getProperty(String.format(QUEUE_PROPERTY, id));
		if (null == value)
			return FcEventBus.DEFAULT_QUEUE_CAPACITY;
		try {
			int capacity = Integer.parseInt(value.trim());
			if (0 < capacity)
				return capacity;
		} catch (NumberFormatException e) {
			// fall through
		}
		logger.warn("{} is not a positive number: '{}' - using {}", String.format(QUEUE_PROPERTY, id), value,
				FcEventBus.DEFAULT_QUEUE_CAPACITY);
		return FcEventBus.DEFAULT_QUEUE_CAPACITY;
	}

	private static double requestSample(String id) {
		String property = String.format(REQUEST_SAMPLE_PROPERTY, id);
		String value = FCConfig.getProperty(property);
		if (null == value)
			return RequestCompleted.DEFAULT_SAMPLE;
		try {
			double sample = Double.parseDouble(value.trim());
			if (0.0 < sample && sample <= 1.0)
				return sample;
		} catch (NumberFormatException e) {
			// fall through
		}
		logger.warn("{} must be a share in (0, 1]: '{}' - using {}", property, value, RequestCompleted.DEFAULT_SAMPLE);
		return RequestCompleted.DEFAULT_SAMPLE;
	}

	/** @param shutdown see {@link FrontcachePlugin#stop(boolean)} */
	public static synchronized void stop(boolean shutdown) {

		running = false;
		PluginEndpoints.clear();
		PluginPredicates.clear();
		FcEventBus.stopAll();

		for (Slot slot : started)
			if (stopQuietly(slot, shutdown))
				slot.state = State.STOPPED;

		started = List.of();
		actions = Map.of();
	}

	private static boolean stopQuietly(Slot slot, boolean shutdown) {
		try {
			slot.plugin.stop(shutdown);
			return true;
		} catch (Throwable t) {
			slot.counters.errors.increment();
			logger.error("Plugin '" + slot.id + "' failed to stop", t);
			return false;
		} finally {
			PluginScheduler scheduler = slot.scheduler;
			if (null != scheduler)
				scheduler.shutdown(); // its tasks end with it - a reload's next start schedules its own
		}
	}

	/**
	 * Called by {@code FrontCacheServlet} / {@code FrontCacheFilter} as they start - possibly before the engine, so
	 * it reaches every loaded plugin rather than only the started ones.
	 */
	public static void modeKnown(NodeMode deployedAs) {

		if (null == deployedAs || deployedAs == mode)
			return;
		mode = deployedAs;
		if (NodeMode.FILTER == deployedAs && droppedInByDefault)
			logger.warn("Dropped-in plugins were loaded from FRONTCACHE_HOME/plugins before the filter started - in filter"
					+ " mode a node reads a plugin directory only when -D{} or ${} names it", PluginDirectory.DIR_PROPERTY,
					PluginDirectory.DIR_ENV);

		for (Slot slot : loaded())
		{
			if (slot.refused())
				continue;
			try {
				slot.plugin.onModeKnown(deployedAs);
			} catch (Throwable t) {
				slot.counters.errors.increment();
				logger.error("Plugin '" + slot.id + "' failed on mode " + deployedAs, t);
			}
		}
	}

	/** The management action a started plugin answers under {@code name}, or null. */
	public static ManagementAction managementAction(String name) {
		return (null == name) ? null : actions.get(name);
	}

	/** Every plugin action's description, by name - for the {@code help} action. */
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

	/** The management action names the started plugins answer. */
	public static Set<String> managementActionNames() {
		return actions.keySet();
	}

	/**
	 * Lets every loaded plugin register its meters - see {@link FrontcachePlugin#bindMeters(Object)}. Loaded rather
	 * than started: the exporter may be built before the engine, and meters read their state at scrape time anyway.
	 */
	public static void bindMeters(Object meterRegistry) {

		for (Slot slot : loaded())
		{
			if (slot.refused())
				continue;
			try {
				slot.plugin.bindMeters(meterRegistry);
			} catch (Throwable t) {
				slot.counters.errors.increment();
				logger.error("Plugin '" + slot.id + "' failed to register its meters", t);
			}
		}
	}

	/** The ids of the started plugins, in load order - {@code get-node-stats}' {@code modules}. */
	public static List<String> ids() {
		List<String> ids = new ArrayList<>();
		for (Slot slot : started)
			ids.add(slot.id);
		return ids;
	}

	/** Every plugin found, started or not - {@code get-node-stats}' {@code plugins}. */
	public static List<PluginStats> stats() {

		List<PluginStats> all = new ArrayList<>();
		for (Slot slot : loaded())
			all.add(stats(slot));
		return all;
	}

	/** The ids of every plugin found, for the metrics exporter's per-plugin series. */
	public static List<String> loadedIds() {
		List<String> ids = new ArrayList<>();
		for (Slot slot : loaded())
			ids.add(slot.id);
		return ids;
	}

	/** One plugin's stats by id, read at scrape time; null when there is none. */
	public static PluginStats stats(String id) {
		for (Slot slot : loaded())
			if (slot.id.equals(id))
				return stats(slot);
		return null;
	}

	private static PluginStats stats(Slot slot) {
		PluginStats stats = new PluginStats();
		stats.setId(slot.id);
		PluginManifest manifest = slot.manifest;
		if (null != manifest)
		{
			stats.setName(manifest.name());
			stats.setVersion(manifest.version());
			stats.setVendor(manifest.vendor());
			stats.setLicense(manifest.license());
			stats.setPluginApi(manifest.pluginApi());
			List<String> kinds = new ArrayList<>();
			for (PluginKind kind : manifest.kinds())
				kinds.add(kind.manifestName());
			stats.setKinds(kinds);
			stats.setRequestPath(manifest.isRequestPath());
		}
		stats.setState(slot.state.label());
		stats.setReason(slot.reason);
		stats.setLegacy(slot.legacy);
		if (slot.isDroppedIn())
		{
			stats.setSource(PluginStats.SOURCE_DROPPED_IN);
			stats.setDirectory(slot.directory);
			stats.setJars(new ArrayList<>(slot.jars));
			stats.setPinned(slot.pinned);
		}
		DecideBudget budget = slot.budget;
		if (null != budget)
		{
			List<String> names = new ArrayList<>();
			for (PluginPredicates.Provider provider : slot.offered)
				names.add(provider.name());
			stats.setPredicates(names);
			stats.setDecideState(budget.isOverBudget() ? PluginStats.DECIDE_OVER_BUDGET : PluginStats.DECIDE_OK);
			stats.setDecideReason(budget.reason());
			stats.setDecideBudgetMicros(budget.budgetNanos() / 1_000);
			stats.setDecideP99Micros(budget.lastP99Micros());
			stats.setDecideCalls(budget.calls());
			stats.setDecideErrors(budget.errors());
			stats.setDecideSwitchedOff(budget.switchedOff());
			stats.setDecideRetryAt(budget.retryAtMillis());
		}
		if (null != manifest && manifest.declares(PluginKind.SERVE))
		{
			stats.setServes(PluginEndpoints.serves(slot.id));
			stats.setCookies(new ArrayList<>(manifest.cookies()));
			stats.setChallenges(new ArrayList<>(manifest.challenges()));
			stats.setServed(slot.counters.served.sum());
			stats.setServeRefused(slot.counters.serveRefused.sum());
			stats.setServeFailed(slot.counters.serveFailed.sum());
		}
		stats.setEventsDelivered(slot.counters.delivered.sum());
		stats.setEventsDropped(slot.counters.dropped.sum());
		stats.setErrors(slot.counters.errors.sum());
		if (null != manifest && null != manifest.config())
		{
			stats.setConfig(manifest.config());
			stats.setConfigStatus(slot.configStatus);
			stats.setConfigErrors(slot.configErrors);
			stats.setConfigLoaded(slot.configLoaded);
		}
		return stats;
	}

	private static <T> List<T> nonNull(List<T> list) {
		return (null == list) ? List.of() : list;
	}

	private static <K, V> Map<K, V> nonNull(Map<K, V> map) {
		return (null == map) ? Map.of() : map;
	}
}
