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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.ManagementAction;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;
import org.frontcache.plugin.decide.RequestView;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The contract every plugin's test inherits - what a node relies on a plugin for, checked without a node:
 *
 * <pre>
 * public class MyPluginContractTest extends PluginContract {
 *     protected Class&lt;? extends FrontcachePlugin&gt; pluginClass() { return MyPlugin.class; }
 *     protected Map&lt;String, List&lt;String&gt;&gt; predicateArguments() { return Map.of("geo", List.of("country=DE,FR")); }
 *     protected List&lt;String&gt; refusedConfigs() { return List.of("database = 42"); }
 * }
 * </pre>
 *
 * <ul>
 * <li>the manifest is on the class path, parses, names the class, has no trailing comment and no unknown key, and the
 * class has a public no-argument constructor;</li>
 * <li>what the plugin contributes matches its declared kinds (the node refuses it otherwise) - and starts, stops and
 * starts again, its scheduled tasks cancelled at each stop;</li>
 * <li>{@code decide}: every argument of {@link #predicateArguments()} parses; {@code test} never throws on the all-null
 * view or the {@link #requestCorpus() corpus}; after a warm-up, the p99 of {@code test} over the corpus is under the
 * {@link #predicateBudget() budget} (the node's default, 200 microseconds);</li>
 * <li>{@code observe}: every listener takes an empty batch, and a batch of the types it subscribes to, without throwing
 * and within {@link #listenerTimeout()} - run on another thread, as the node runs it;</li>
 * <li>{@code serve}: the endpoint answers each of {@link #endpointRequests()} without throwing, with a status;</li>
 * <li>{@code manage}: every action has a name and a description;</li>
 * <li>each of {@link #acceptedConfigs()} is accepted by {@code validateConfig} and {@code configure}; each of
 * {@link #refusedConfigs()} is refused by both, with at least one reason.</li>
 * </ul>
 *
 * Every hook has a default, so a minimal contract test names only the class.
 */
public abstract class PluginContract {

	private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9-]{0,63}");
	private static final Pattern PREDICATE = Pattern.compile("[a-z][a-z0-9-]{0,30}");
	private static final int WARM_UP = 20_000;
	private static final int MEASURED = 20_000;
	private static final int MIN_SAMPLES = 1_000;
	private static final long WARM_UP_NANOS = 2_000_000_000L;
	private static final long MEASURE_NANOS = 3_000_000_000L;

	/** The harness of the test running: started with {@link #startConfig()} before each test, closed after. */
	protected PluginHarness harness;

	// ---- hooks ---------------------------------------------------------------------------------------------

	/** The plugin's entry class - the one its manifest names. */
	protected abstract Class<? extends FrontcachePlugin> pluginClass();

	/** A harness for the plugin; override to set up the context (a public domain, a signer) before it starts. */
	protected PluginHarness newHarness() {
		return PluginHarness.forPlugin(pluginClass());
	}

	/**
	 * The config the plugin is started with in these tests - {@code conf/<config>}'s text, or null for an absent
	 * file (an empty config). A plugin that would reach out at start (a fetch scheduled at once) points it at nothing
	 * here.
	 */
	protected String startConfig() {
		return null;
	}

	/**
	 * For each predicate name the plugin offers, the arguments a rule may write after {@code ext:<name>:} - each must
	 * parse, and every one is timed over the corpus. A name with no argument here fails the contract.
	 */
	protected Map<String, List<String>> predicateArguments() {
		return Map.of();
	}

	/** The requests predicates are tested and timed over - by default {@link RequestViews#corpus()}. */
	protected List<RequestView> requestCorpus() {
		return RequestViews.corpus();
	}

	/** A predicate's p99 must be under this - the node's default {@code decide-budget}, 200 microseconds. */
	protected Duration predicateBudget() {
		return Duration.ofNanos(200_000);
	}

	/** How long a listener may take over one batch before it counts as blocking. */
	protected Duration listenerTimeout() {
		return Duration.ofSeconds(5);
	}

	/** The requests the endpoint must answer - by default a GET of {@code /} and of a path it does not have, and a POST. */
	protected List<FakePluginRequest> endpointRequests() {
		return List.of(FakePluginRequest.get("/"), FakePluginRequest.get("/no-such-page?x=1"),
				FakePluginRequest.form("/", "a=1"), FakePluginRequest.get("/").from(null));
	}

	/** Configs ({@code conf/<config>}'s text) the plugin must accept. */
	protected List<String> acceptedConfigs() {
		return List.of();
	}

	/** Configs the plugin must refuse whole, with a reason. */
	protected List<String> refusedConfigs() {
		return List.of();
	}

	// ---- the life of each test -----------------------------------------------------------------------------

	@Before
	public void startPlugin() throws Exception {
		harness = newHarness();
		if (null != harness.manifest().config() && null != startConfig())
			harness.configure(startConfig());
		harness.start();
	}

	@After
	public void closePlugin() {
		if (null != harness)
			harness.close();
	}

	// ---- the manifest --------------------------------------------------------------------------------------

	@Test
	public void manifestIsPresentParsesAndNamesTheClass() {
		Class<? extends FrontcachePlugin> type = pluginClass();
		List<URL> found = PluginHarness.manifestsNaming(type);
		assertEquals("exactly one " + PluginManifest.RESOURCE + " on the class path names class = " + type.getName()
				+ " - found " + found, 1, found.size());

		Properties raw = PluginHarness.load(found.get(0));
		for (String key : raw.stringPropertyNames())
		{
			String value = raw.getProperty(key);
			assertFalse("'" + key + " = " + value + "': a .properties file has no trailing comments - everything after"
					+ " '=' is the value", value.contains(" #") || value.contains("\t#"));
		}

		PluginManifest manifest = PluginManifest.parse(raw, found.get(0).toString());
		assertEquals(type.getName(), manifest.entryClass());
		assertTrue("unknown manifest keys are ignored by the node, which is how a typo goes unnoticed: "
				+ manifest.warnings(), manifest.warnings().isEmpty());
		assertTrue("plugin-api " + manifest.pluginApi() + " is newer than this API's " + PluginManifest.PLUGIN_API,
				manifest.pluginApi() <= PluginManifest.PLUGIN_API);
		assertTrue(type.getName() + " must be a public, concrete class", Modifier.isPublic(type.getModifiers())
				&& !Modifier.isAbstract(type.getModifiers()) && !type.isInterface());
		try {
			assertTrue(Modifier.isPublic(type.getConstructor().getModifiers()));
		} catch (NoSuchMethodException e) {
			fail(type.getName() + " has no public no-argument constructor - the node instantiates a plugin through one");
		}
	}

	// ---- the life cycle ------------------------------------------------------------------------------------

	@Test
	public void contributesOnlyTheKindsItDeclares() {
		// start() refused it otherwise (PluginRefusedException); what is left is the names
		for (GuardPredicateProvider provider : harness.predicates())
			assertTrue("predicate name '" + provider.name() + "' must match " + PREDICATE.pattern(),
					null != provider.name() && PREDICATE.matcher(provider.name()).matches());
		List<String> names = new ArrayList<>();
		for (GuardPredicateProvider provider : harness.predicates())
			names.add(provider.name());
		assertEquals("two predicates of this plugin share a name: " + names, names.size(), Set.copyOf(names).size());
		for (EventListener listener : harness.listeners())
		{
			Set<Class<? extends FcEvent>> types = listener.subscribes();
			assertNotNull(listener + " subscribes to null", types);
			assertFalse(listener + " subscribes to nothing - hand it over only when it observes something",
					types.isEmpty());
		}
	}

	@Test
	public void startsStopsAndStartsAgain() throws Exception {
		PluginScheduler first = harness.scheduler();
		harness.stop(false);
		assertEquals("every task the plugin scheduled is cancelled when it stops", 0, first.activeTasks());

		harness.start();
		PluginScheduler second = harness.scheduler();
		harness.reload();
		assertEquals(0, second.activeTasks());
		assertTrue(harness.isRunning());

		harness.stop(true);
		assertEquals(0, harness.scheduler().activeTasks());
		assertFalse(harness.isRunning());
		assertEquals("a scheduled task threw: " + first.lastError(), 0, first.taskErrors() + second.taskErrors());
	}

	// ---- decide --------------------------------------------------------------------------------------------

	@Test
	public void everyPredicateArgumentParses() throws Exception {
		for (GuardPredicateProvider provider : harness.predicates())
		{
			List<String> arguments = predicateArguments().get(provider.name());
			assertTrue("predicateArguments() names no argument for ext:" + provider.name() + ": - the contract cannot"
					+ " test a predicate without one", null != arguments && !arguments.isEmpty());
			for (String argument : arguments)
			{
				try {
					assertNotNull("ext:" + provider.name() + ":" + argument + " parsed to null", provider.parse(argument));
				} catch (PluginConfigException e) {
					fail("ext:" + provider.name() + ":" + argument + " is refused: " + e.reasons());
				}
			}
		}
	}

	@Test
	public void predicatesAnswerAViewWithNothingBehindIt() throws Exception {
		RequestView empty = RequestViews.empty();
		for (Compiled c : compiled())
		{
			try {
				c.predicate().test(empty);
			} catch (RuntimeException e) {
				throw new AssertionError(c + " threw on the all-null view - the node's load-time probe passes one, and"
						+ " a throw makes its rule never match", e);
			}
		}
	}

	@Test
	public void predicatesStayUnderTheBudget() throws Exception {
		List<RequestView> corpus = requestCorpus();
		assertFalse("requestCorpus() is empty", corpus.isEmpty());
		long budget = predicateBudget().toNanos();
		for (Compiled c : compiled())
		{
			for (RequestView view : corpus)
			{
				try {
					c.predicate().test(view);
				} catch (RuntimeException e) {
					throw new AssertionError(c + " threw on " + view + " - its rule then does not match", e);
				}
			}
			// bounded in time too, so a predicate far over its budget fails in seconds, not minutes
			boolean sink = false;
			long warmUntil = System.nanoTime() + WARM_UP_NANOS;
			for (int i = 0; i < WARM_UP && (i < MIN_SAMPLES || System.nanoTime() < warmUntil); i++)
				sink ^= c.predicate().test(corpus.get(i % corpus.size()));
			long[] samples = new long[MEASURED];
			int n = 0;
			long measureUntil = System.nanoTime() + MEASURE_NANOS;
			for (; n < MEASURED && (n < MIN_SAMPLES || System.nanoTime() < measureUntil); n++)
			{
				RequestView view = corpus.get(n % corpus.size());
				long start = System.nanoTime();
				sink ^= c.predicate().test(view);
				samples[n] = System.nanoTime() - start;
			}
			long[] nanos = Arrays.copyOf(samples, n);
			Arrays.sort(nanos);
			long p99 = nanos[(int) Math.ceil(n * 0.99) - 1];
			assertTrue(c + ": p99 " + p99 / 1000 + " us is over the budget of " + budget / 1000 + " us - a node"
					+ " switches the plugin off; answer from memory, refresh in the background", p99 <= budget);
			if (sink && System.nanoTime() == 0)
				System.out.print("");   // keeps the results live
		}
	}

	// ---- observe -------------------------------------------------------------------------------------------

	@Test
	public void listenersTakeAnEmptyBatch() throws Exception {
		for (EventListener listener : harness.listeners())
			onItsOwnThread(listener, List.of(), "an empty batch");
	}

	@Test
	public void listenersTakeABatchOfWhatTheySubscribeTo() throws Exception {
		for (EventListener listener : harness.listeners())
		{
			List<FcEvent> batch = SampleEvents.batchOf(listener.subscribes());
			onItsOwnThread(listener, batch, "a batch of " + batch.size() + " events of " + listener.subscribes());
		}
	}

	// ---- serve ---------------------------------------------------------------------------------------------

	@Test
	public void endpointAnswers() throws Exception {
		if (null == harness.endpoint())
			return;
		for (FakePluginRequest request : endpointRequests())
		{
			FakePluginResponse response;
			try {
				response = harness.serve(request);
			} catch (Exception e) {
				throw new AssertionError("the endpoint threw on " + request + " - the node answers 503", e);
			}
			assertTrue(request + " answered status " + response.status(), response.status() >= 100
					&& response.status() <= 599);
		}
	}

	// ---- manage --------------------------------------------------------------------------------------------

	@Test
	public void actionsHaveANameAndADescription() {
		for (Map.Entry<String, ManagementAction> e : harness.actions().entrySet())
		{
			assertTrue("action name '" + e.getKey() + "' must match " + ACTION.pattern(),
					ACTION.matcher(e.getKey()).matches());
			String description = e.getValue().description();
			assertTrue("action '" + e.getKey() + "' has no description - the help action lists it",
					null != description && !description.isBlank());
		}
	}

	// ---- config --------------------------------------------------------------------------------------------

	@Test
	public void acceptedConfigsAreAccepted() throws Exception {
		for (String config : acceptedConfigs())
		{
			try {
				harness.validateConfig(config);
			} catch (PluginConfigException e) {
				fail("validateConfig refused a config it should accept: " + e.reasons() + "\n" + config);
			}
			try {
				harness.configure(config);
			} catch (PluginConfigException e) {
				fail("configure refused a config it should accept: " + e.reasons() + "\n" + config);
			}
		}
	}

	@Test
	public void refusedConfigsAreRefusedWholeWithAReason() {
		for (String config : refusedConfigs())
		{
			try {
				harness.validateConfig(config);
				fail("validateConfig accepted a config it should refuse - set-config would write it:\n" + config);
			} catch (PluginConfigException e) {
				assertReasons(e, config);
			}
			try {
				harness.configure(config);
				fail("configure accepted a config it should refuse:\n" + config);
			} catch (PluginConfigException e) {
				assertReasons(e, config);
			}
		}
	}

	// ---- inside --------------------------------------------------------------------------------------------

	private static void assertReasons(PluginConfigException e, String config) {
		assertFalse("refused without a reason:\n" + config, e.reasons().isEmpty());
		for (String reason : e.reasons())
			assertTrue("a blank reason:\n" + config, null != reason && !reason.isBlank());
	}

	private record Compiled(String expression, PluginPredicate predicate) {
		@Override
		public String toString() {
			return "ext:" + expression;
		}
	}

	private List<Compiled> compiled() throws PluginConfigException {
		List<Compiled> out = new ArrayList<>();
		for (GuardPredicateProvider provider : harness.predicates())
		{
			List<String> arguments = predicateArguments().get(provider.name());
			if (null == arguments)
				continue;   // everyPredicateArgumentParses says so
			for (String argument : arguments)
				out.add(new Compiled(provider.name() + ":" + argument, provider.parse(argument)));
		}
		return out;
	}

	private void onItsOwnThread(EventListener listener, List<FcEvent> batch, String what) throws Exception {
		ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "fc-contract-listener");
			t.setDaemon(true);
			return t;
		});
		try {
			Future<?> done = thread.submit(() -> listener.onEvents(batch));
			done.get(listenerTimeout().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			fail(listener + " did not return from " + what + " within " + listenerTimeout() + " - a listener that"
					+ " blocks fills its queue, and the node drops what does not fit");
		} catch (ExecutionException e) {
			throw new AssertionError(listener + " threw on " + what, e.getCause());
		} finally {
			thread.shutdownNow();
		}
	}
}
