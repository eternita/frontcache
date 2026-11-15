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
package org.frontcache.plugin.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.io.ActionResponse;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginKind;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.serve.PluginEndpoint;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;

/**
 * A plugin, run in-process the way a node runs a dropped-in one - for a plugin's own tests, with no engine:
 *
 * <pre>
 * try (PluginHarness harness = PluginHarness.forPlugin(MyPlugin.class)) {
 *     harness.configure("allow = [\"127.0.0.1/32\"]");
 *     harness.start();
 *     harness.deliver(new GuardMatched(...));
 *     FakePluginResponse page = harness.serve(FakePluginRequest.get("/").from("127.0.0.1"));
 *     assertTrue(harness.predicate("ext:path-depth:&gt;4").test(RequestViews.get("/a/b/c/d/e").build()));
 * }
 * </pre>
 *
 * What it does as the node does:
 * <ul>
 * <li>finds the manifest on the class path that names the plugin's class ({@link #manifestOf}) - several may be on one
 * class path, one per jar on a node - and instantiates the class through its public no-argument constructor;</li>
 * <li>hands the plugin a {@link TestPluginContext}: a plain {@code PluginContext}, never the core's bundled one;</li>
 * <li>{@linkplain #start() configures it before every start} (the last config accepted, or an empty one - an absent
 * file), then starts it, then reads its listeners, endpoint, actions and predicates and <b>refuses</b> a contribution
 * of a kind the manifest does not declare ({@link PluginRefusedException});</li>
 * <li>{@link #reload()} is {@code stop(false)} then {@code start} on the same instance; stopping cancels the
 * scheduler's tasks; {@link #configure} on a running plugin is {@code reload-plugin-config}: the listeners are read
 * again.</li>
 * </ul>
 * What it does differently: an exception the plugin throws reaches the test, where the node would log it and answer
 * for the plugin (a dropped plugin, a {@code 503}, a rule that does not match) - a test is better told.
 */
public final class PluginHarness implements AutoCloseable {

	private final FrontcachePlugin plugin;
	private final PluginManifest manifest;
	private final TestPluginContext context;

	private String configText;
	private boolean running;
	private List<EventListener> listeners = List.of();
	private PluginEndpoint endpoint;
	private Map<String, ManagementAction> actions = Map.of();
	private List<GuardPredicateProvider> predicates = List.of();

	private PluginHarness(FrontcachePlugin plugin, PluginManifest manifest) {
		this.plugin = plugin;
		this.manifest = manifest;
		this.context = new TestPluginContext(manifest);
	}

	// ---- making one -----------------------------------------------------------------------------------------

	/** The plugin its manifest on the class path names, instantiated as the node instantiates it. */
	public static PluginHarness forPlugin(Class<? extends FrontcachePlugin> type) {
		PluginManifest manifest = manifestOf(type);
		FrontcachePlugin plugin;
		try {
			plugin = type.getConstructor().newInstance();
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(type.getName() + " has no public no-argument constructor - the node"
					+ " instantiates a plugin through one", e);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("the node could not instantiate " + type.getName(), e);
		}
		return new PluginHarness(plugin, manifest);
	}

	/** A given instance with a given manifest - for a plugin built in a test. */
	public static PluginHarness of(FrontcachePlugin plugin, PluginManifest manifest) {
		return new PluginHarness(plugin, manifest);
	}

	/**
	 * The manifest ({@value PluginManifest#RESOURCE}) on the type's class path whose {@code class} names it - parsed
	 * as the node parses it.
	 *
	 * @throws IllegalStateException when none names it, or more than one does
	 * @throws IllegalArgumentException when the one naming it is refused, with every reason
	 */
	public static PluginManifest manifestOf(Class<?> type) {
		List<URL> found = manifestsNaming(type);
		if (found.isEmpty())
			throw new IllegalStateException("no " + PluginManifest.RESOURCE + " on the class path names class = "
					+ type.getName());
		if (found.size() > 1)
			throw new IllegalStateException(found.size() + " manifests name class = " + type.getName() + ": " + found);
		URL url = found.get(0);
		return PluginManifest.parse(load(url), url.toString());
	}

	/** Every manifest on the type's class path whose {@code class} is the type's name. */
	public static List<URL> manifestsNaming(Class<?> type) {
		ClassLoader loader = (null == type.getClassLoader()) ? ClassLoader.getSystemClassLoader() : type.getClassLoader();
		List<URL> naming = new ArrayList<>();
		try {
			Enumeration<URL> urls = loader.getResources(PluginManifest.RESOURCE);
			while (urls.hasMoreElements())
			{
				URL url = urls.nextElement();
				String entry = load(url).getProperty("class");
				if (null != entry && entry.trim().equals(type.getName()))
					naming.add(url);
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + PluginManifest.RESOURCE + " from the class path", e);
		}
		return naming;
	}

	/** The raw properties of a manifest. */
	public static Properties load(URL url) {
		Properties p = new Properties();
		try (InputStream in = url.openStream()) {
			p.load(in);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + url, e);
		}
		return p;
	}

	/** A manifest from {@code .properties} text - for a plugin built in a test. */
	public static PluginManifest manifest(String properties) {
		Properties p = new Properties();
		try {
			p.load(new StringReader(properties));
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
		return PluginManifest.parse(p, "test");
	}

	/** HOCON as the node hands it to {@code configure}: parsed with {@code conf/<file>} as its origin, resolved. */
	public static Config parseConfig(String hocon, String origin) {
		String text = (null == hocon) ? "" : hocon;
		return ConfigFactory.parseString(text, ConfigParseOptions.defaults().setOriginDescription(origin)).resolve();
	}

	// ---- the plugin's life ---------------------------------------------------------------------------------

	/**
	 * {@code conf/<config>} is now this text: on a stopped plugin, configured now (so a refusal reaches the test) and
	 * again before every start; on a running one, {@code reload-plugin-config} - configured, and its listeners read
	 * again. A refused config leaves the previous one in place.
	 */
	public PluginHarness configure(String hocon) throws PluginConfigException {
		plugin.configure(parseConfig(hocon, configOrigin()));
		configText = hocon;
		if (running)
			readListeners();
		return this;
	}

	/** {@code validateConfig}: what the node asks before {@code set-config} writes the file. Nothing is applied. */
	public void validateConfig(String hocon) throws PluginConfigException {
		plugin.validateConfig(parseConfig(hocon, configOrigin()));
	}

	/**
	 * Configures the plugin (when its manifest names a config: the last accepted text, else empty - an absent file),
	 * starts it, and reads what it contributes, refusing a kind it did not declare.
	 *
	 * @throws PluginConfigException when the config is refused - the node then does not start the plugin
	 * @throws PluginRefusedException when the plugin contributes a kind it does not declare
	 */
	public PluginHarness start() throws PluginConfigException {
		if (running)
			throw new IllegalStateException("plugin '" + manifest.id() + "' is already started");
		context.newScheduler();
		if (null != manifest.config())
		{
			try {
				plugin.configure(parseConfig(configText, configOrigin()));
			} catch (PluginConfigException | RuntimeException e) {
				context.cancelScheduledTasks();
				throw e;
			}
		}
		try {
			plugin.start(context);
		} catch (RuntimeException | Error e) {
			context.cancelScheduledTasks();
			throw e;
		}
		running = true;

		List<String> refusals = new ArrayList<>();
		endpoint = plugin.endpoint();
		if (null != endpoint && !manifest.declares(PluginKind.SERVE))
			refusals.add("it serves an endpoint without declaring kind 'serve'");
		actions = copy(plugin.managementActions());
		if (!actions.isEmpty() && !manifest.declares(PluginKind.MANAGE))
			refusals.add("it answers management actions " + actions.keySet() + " without declaring kind 'manage'");
		predicates = copy(plugin.predicates());
		if (!predicates.isEmpty() && !manifest.declares(PluginKind.DECIDE))
			refusals.add("it offers predicates without declaring kind 'decide'");
		List<EventListener> offered = copy(plugin.eventListeners());
		if (!offered.isEmpty() && !manifest.declares(PluginKind.OBSERVE))
			refusals.add("it hands over event listeners without declaring kind 'observe'");
		listeners = offered;

		if (!refusals.isEmpty())
		{
			stopQuietly(true);
			throw new PluginRefusedException("plugin '" + manifest.id() + "' (kinds " + manifest.kindList()
					+ ") is refused at start: " + String.join("; ", refusals));
		}
		return this;
	}

	/** {@code stop(false)}, as before an engine {@code reload}: the scheduler's tasks are cancelled. */
	public PluginHarness stop() {
		return stop(false);
	}

	/** {@code stop(shutdown)}; the scheduler's tasks are cancelled. */
	public PluginHarness stop(boolean shutdown) {
		if (!running)
			return this;
		try {
			plugin.stop(shutdown);
		} finally {
			running = false;
			context.cancelScheduledTasks();
			clear();
		}
		return this;
	}

	/** The engine's {@code reload}: {@code stop(false)} then {@code start} on the same instance. */
	public PluginHarness reload() throws PluginConfigException {
		stop(false);
		return start();
	}

	public boolean isRunning() {
		return running;
	}

	/** Stops the plugin ({@code stop(true)}) if it runs, and removes the temporary FRONTCACHE_HOME. */
	@Override
	public void close() {
		try {
			if (running)
				stop(true);
		} finally {
			context.delete();
		}
	}

	// ---- what it contributes -------------------------------------------------------------------------------

	public FrontcachePlugin plugin() {
		return plugin;
	}

	public PluginManifest manifest() {
		return manifest;
	}

	public TestPluginContext context() {
		return context;
	}

	/** The scheduler of the current (or last) start. */
	public PluginScheduler scheduler() {
		return context.scheduler();
	}

	public List<EventListener> listeners() {
		return listeners;
	}

	/** Null when the plugin serves nothing. */
	public PluginEndpoint endpoint() {
		return endpoint;
	}

	public Map<String, ManagementAction> actions() {
		return actions;
	}

	public List<GuardPredicateProvider> predicates() {
		return predicates;
	}

	/** The predicate provider of that name, or null. */
	public GuardPredicateProvider predicateProvider(String name) {
		for (GuardPredicateProvider p : predicates)
			if (name.equals(p.name()))
				return p;
		return null;
	}

	/**
	 * Compiles a rule's predicate - {@code ext:<name>:<argument>} or {@code <name>:<argument>} - as a rule load does:
	 * the argument trimmed.
	 *
	 * @throws IllegalArgumentException when no started predicate has the name
	 * @throws PluginConfigException when the predicate refuses the argument
	 */
	public PluginPredicate predicate(String expression) throws PluginConfigException {
		String e = expression.startsWith("ext:") ? expression.substring(4) : expression;
		int colon = e.indexOf(':');
		String name = (colon < 0) ? e : e.substring(0, colon);
		String argument = (colon < 0) ? "" : e.substring(colon + 1).trim();
		GuardPredicateProvider provider = predicateProvider(name);
		if (null == provider)
			throw new IllegalArgumentException("no started predicate is named '" + name + "' - the node refuses a rule"
					+ " naming it");
		return provider.parse(argument);
	}

	// ---- driving it ----------------------------------------------------------------------------------------

	/**
	 * Delivers events to the listeners subscribed to their types - on this thread, in batches of at most 500, each
	 * listener only the types it {@code subscribes()} to, as the node's dispatcher does.
	 *
	 * @return how many deliveries were made (an event to two listeners counts twice)
	 */
	public int deliver(FcEvent... events) {
		return deliver(List.of(events));
	}

	public int deliver(List<? extends FcEvent> events) {
		int delivered = 0;
		for (EventListener listener : listeners)
		{
			java.util.Set<Class<? extends FcEvent>> types = listener.subscribes();
			List<FcEvent> wanted = new ArrayList<>();
			for (FcEvent event : events)
				if (null != types && types.contains(event.getClass()))
					wanted.add(event);
			for (int from = 0; from < wanted.size(); from += 500)
			{
				List<FcEvent> batch = List.copyOf(wanted.subList(from, Math.min(wanted.size(), from + 500)));
				listener.onEvents(batch);
				delivered += batch.size();
			}
		}
		return delivered;
	}

	/**
	 * Calls the endpoint with a request under the plugin's prefix; the request shows the cookies the manifest names.
	 *
	 * @throws IllegalStateException when the plugin serves nothing
	 * @throws IOException what the endpoint threw, or a body over {@link FakePluginResponse#MAX_BODY} - the node
	 *         answers 503 (413 for a request body over its limit)
	 */
	public FakePluginResponse serve(FakePluginRequest request) throws IOException {
		if (null == endpoint)
			throw new IllegalStateException("plugin '" + manifest.id() + "' serves nothing - a node answers 404 on"
					+ " /fc-x/" + manifest.id() + "/");
		request.visibleCookies(manifest.cookies());
		FakePluginResponse response = new FakePluginResponse(manifest, context.publicDomain(),
				context.allowedRedirectHosts(), request.scheme());
		endpoint.handle(request, response);
		response.flush();
		if (response.overLimit())
			throw new IOException("the response body is over " + FakePluginResponse.MAX_BODY
					+ " bytes - the node answers 503");
		return response;
	}

	/** A GET of a path under the plugin's prefix. */
	public FakePluginResponse serve(String pathAndQuery) throws IOException {
		return serve(FakePluginRequest.get(pathAndQuery));
	}

	/**
	 * Calls a management action as {@code /frontcache-io?action=<name>} would, past the API-key gate.
	 *
	 * @throws IllegalArgumentException when the plugin answers no such action - a node answers it with a ping
	 */
	public ActionResponse call(FakeManagementRequest request) throws IOException {
		ManagementAction action = actions.get(request.action());
		if (null == action)
			throw new IllegalArgumentException("plugin '" + manifest.id() + "' answers no action '" + request.action()
					+ "' - a node answers it with a ping");
		return action.handle(request);
	}

	public ActionResponse call(String action) throws IOException {
		return call(FakeManagementRequest.of(action));
	}

	// ---- inside --------------------------------------------------------------------------------------------

	private void readListeners() {
		List<EventListener> offered = copy(plugin.eventListeners());
		if (!offered.isEmpty() && !manifest.declares(PluginKind.OBSERVE))
			throw new PluginRefusedException("plugin '" + manifest.id() + "' hands over event listeners without"
					+ " declaring kind 'observe'");
		listeners = offered;
		// the node reads a decide plugin's predicates again after an accepted reload, too
		List<GuardPredicateProvider> again = copy(plugin.predicates());
		if (!again.isEmpty() && !manifest.declares(PluginKind.DECIDE))
			throw new PluginRefusedException("plugin '" + manifest.id() + "' offers predicates without declaring kind"
					+ " 'decide'");
		predicates = again;
	}

	private String configOrigin() {
		return "conf/" + ((null == manifest.config()) ? manifest.id() + ".conf" : manifest.config());
	}

	private void stopQuietly(boolean shutdown) {
		try {
			stop(shutdown);
		} catch (RuntimeException e) {
			// refused anyway
		}
	}

	private void clear() {
		listeners = List.of();
		endpoint = null;
		actions = Map.of();
		predicates = List.of();
	}

	private static <T> List<T> copy(List<T> list) {
		if (null == list)
			return List.of();
		List<T> out = new ArrayList<>();
		for (T t : list)
			if (null != t)
				out.add(t);
		return List.copyOf(out);
	}

	private static <K, V> Map<K, V> copy(Map<K, V> map) {
		return (null == map) ? Map.of() : Map.copyOf(map);
	}
}
