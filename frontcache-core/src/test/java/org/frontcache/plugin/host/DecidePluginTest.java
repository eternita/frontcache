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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.frontcache.FCConfig;
import org.frontcache.bots.BotClassifier;
import org.frontcache.bots.BotRule;
import org.frontcache.bots.BotRuleParser;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardOutcome;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEngine;
import org.frontcache.guard.GuardRuleParser;
import org.frontcache.io.PluginStats;
import org.frontcache.plugin.FrontcachePlugin;
import org.frontcache.plugin.PluginConfigException;
import org.frontcache.plugin.PluginContext;
import org.frontcache.plugin.PluginManifest;
import org.frontcache.plugin.decide.GuardPredicateProvider;
import org.frontcache.plugin.decide.PluginPredicate;
import org.frontcache.plugin.decide.RequestView;
import org.frontcache.plugin.event.EventListener;
import org.frontcache.plugin.event.FcEvent;
import org.frontcache.plugin.event.PluginStateChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * E3: a plugin's {@code ext:} predicates in guard and bot rules - bound late, failing as "the rule does not match"
 * (negated or not), timed against the plugin's budget and switched off over it, and the {@code on-error=allow} an
 * {@code allow} rule must say.
 */
public class DecidePluginTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private String previousHome;

	/** The fake clock the budget reads; {@link Geo#slow} advances it. */
	static final AtomicLong clock = new AtomicLong(1_000_000_000L);

	/** Offers ext:prefix:<path>, ext:boom (throws), ext:slow (advances the clock by its argument in microseconds). */
	public static class Geo implements FrontcachePlugin {

		static volatile RequestView lastView;
		static volatile boolean flaky;

		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<GuardPredicateProvider> predicates() {
			return List.of(provider("prefix", argument -> {
				if (!argument.startsWith("/"))
					throw new PluginConfigException(List.of("a prefix starts with '/'"));
				return view -> {
					lastView = view;
					if (flaky)
						throw new IllegalStateException("flaky");
					return null != view.uri() && view.uri().startsWith(argument);
				};
			}), provider("boom", argument -> view -> {
				throw new IllegalStateException("boom");
			}), provider("slow", argument -> {
				long micros = Long.parseLong(argument);
				return view -> {
					clock.addAndGet(TimeUnit.MICROSECONDS.toNanos(micros));
					return true;
				};
			}));
		}
	}

	/** Offers a predicate without declaring decide. */
	public static class Undeclared implements FrontcachePlugin {
		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<GuardPredicateProvider> predicates() {
			return List.of(provider("sneaky", argument -> view -> true));
		}
	}

	/** Watches plugin state changes. */
	public static class Watcher implements FrontcachePlugin {
		static final List<PluginStateChanged> seen = Collections.synchronizedList(new ArrayList<>());
		static volatile CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void start(PluginContext context) {
		}

		@Override
		public List<EventListener> eventListeners() {
			return List.of(FcEventBusTest.listener(Set.of(PluginStateChanged.class), batch -> {
				for (FcEvent e : batch)
					if (e instanceof PluginStateChanged p && "geo".equals(p.plugin()) && "over-budget".equals(p.state())) {
						seen.add(p);
						latch.countDown();
					}
			}));
		}
	}

	interface Parse {
		PluginPredicate parse(String argument) throws PluginConfigException;
	}

	static GuardPredicateProvider provider(String name, Parse parse) {
		return new GuardPredicateProvider() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public PluginPredicate parse(String argument) throws PluginConfigException {
				return parse.parse(argument);
			}
		};
	}

	@Before
	public void setUp() throws IOException {
		FCConfig.getProperty("front-cache.id"); // initialised from the fixture home before the home is swapped
		File home = folder.newFolder("home");
		new File(home, "conf").mkdirs();
		previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, home.getAbsolutePath());
		FCConfig.setProperty("front-cache.guard-rules.enabled", "true");
		DecideBudget.nanoClock = clock::get;
		Geo.flaky = false;
	}

	@After
	public void tearDown() {
		FrontcachePlugins.install(List.of());
		DecideBudget.nanoClock = System::nanoTime;
		FCConfig.setProperty("front-cache.plugins.geo.decide-budget", "");
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);
	}

	private void rules(String... lines) throws IOException {
		Files.writeString(new File(System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY), "conf/guard-rules.conf").toPath(),
				String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
	}

	private void installPlugins(String... idClassKinds) throws IOException {
		List<URL> urls = new ArrayList<>();
		for (int i = 0; i < idClassKinds.length; i += 3) {
			File root = folder.newFolder("plugin-" + idClassKinds[i]);
			File file = new File(root, PluginManifest.RESOURCE);
			file.getParentFile().mkdirs();
			Files.writeString(file.toPath(), "id = " + idClassKinds[i] + "\nclass = " + idClassKinds[i + 1]
					+ "\nversion = 1.0.0\nplugin-api = 1\nkinds = " + idClassKinds[i + 2] + "\n", StandardCharsets.UTF_8);
			urls.add(root.toURI().toURL());
		}
		URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
		FrontcachePlugins.install(FrontcachePlugins.load(loader));
	}

	private static RequestContext request(String uri) {
		return new ServletFakes.Request().uri(uri).context();
	}

	private static GuardRule rule(GuardRuleEngine engine, String name) {
		for (GuardRule rule : engine.getRules())
			if (rule.getName().equals(name))
				return rule;
		throw new AssertionError("no rule " + name);
	}

	// ---- grammar -----------------------------------------------------------------------------------------------

	@Test
	public void extParsesWithoutAPluginAndAnAllowRuleMustSayOnErrorAllow() {
		GuardRuleParser parser = new GuardRuleParser(Set.of());

		GuardRule rule = parser.parse("r | uri~^/a ; !ext:geo:country=DE,FR | reject:403");
		assertEquals("uri~^/a ; !ext:geo:country=DE,FR", rule.getCondition());
		assertTrue(rule.getPredicates().get(1).isPluginPredicate());
		assertEquals("geo", rule.getPredicates().get(1).pluginPredicate().getName());
		assertEquals("country=DE,FR", rule.getPredicates().get(1).pluginPredicate().getArgument());
		assertEquals("ext:flag", parser.parse("r | ext:flag | reject:403").getCondition());

		GuardRule allow = parser.parse("staff | ext:geo:office | allow | on-error=allow");
		assertTrue(allow.isOnErrorAllow());
		assertEquals("staff | ext:geo:office | allow | on-error=allow", allow.toString());
		assertTrue(parser.parse("staff | ext:geo:office | allow | dry-run | on-error = allow").isDryRun());

		for (String refused : new String[] {
				"r | ext:geo:office | allow",                          // the exemption may stop applying: say so
				"r | !ext:geo:office | allow",
				"r | ext:geo:office | reject:403 | on-error=allow",     // means nothing on a reject
				"r | uri~^/a | allow | on-error=allow",                 // nor on an allow without ext:
				"r | ext: | reject:403", "r | ext:Geo | reject:403", "r | ext::x | reject:403",
				"r | ext:geo | reject:403 | dry-run | dry-run" })
			try {
				parser.parse(refused);
				fail("refused: " + refused);
			} catch (GuardConfigException expected) {
			}
	}

	// ---- binding and failure -----------------------------------------------------------------------------------

	@Test
	public void beforeThePluginsStartAnExtRuleDoesNotMatchAndAfterItIsAnswered() throws IOException {
		rules("geo-block | ext:prefix:/blocked | reject:403", "not-geo | !ext:prefix:/open | reject:451");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		assertFalse("no plugin has started: no answer, no match", rule(engine, "geo-block").matches(request("/blocked/x")));
		assertFalse("and never 'true' for the negation", rule(engine, "not-geo").matches(request("/elsewhere")));

		FrontcachePlugins.start(null, engine::getRules);
		engine.checkPluginPredicates();

		GuardOutcome blocked = engine.evaluate(request("/blocked/x"));
		assertTrue(blocked.isTerminal());
		assertEquals("geo-block", blocked.getRule().getName());
		assertEquals(451, engine.evaluate(request("/elsewhere")).getAction().getStatus());
		assertFalse(engine.evaluate(request("/open/page")).isTerminal());

		PluginStats stats = FrontcachePlugins.stats("geo");
		assertEquals(List.of("prefix", "boom", "slow"), stats.getPredicates());
		assertEquals(PluginStats.DECIDE_OK, stats.getDecideState());
		assertEquals(200, stats.getDecideBudgetMicros());
		assertTrue(stats.getDecideCalls() >= 3);
		assertTrue(stats.isRequestPath());
	}

	@Test
	public void aThrowingPredicateIsNeverTrueNegatedOrNot() throws IOException {
		rules("boom | ext:boom | reject:403", "not-boom | !ext:boom | reject:403");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		assertFalse(rule(engine, "boom").matches(request("/")));
		assertFalse(rule(engine, "not-boom").matches(request("/")));
		assertFalse(engine.evaluate(request("/")).isTerminal());
		assertEquals(4, FrontcachePlugins.stats("geo").getDecideErrors()); // two by matches, two by evaluate
	}

	@Test
	public void aNameNoPluginOffersAndAnArgumentThePluginRefusesHaveNoAnswer() throws IOException {
		rules("unknown | ext:asn:16509 | reject:403", "refused | ext:prefix:nope | reject:403");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		String unknown = PluginPredicates.check(rule(engine, "unknown").getPredicates().get(0).pluginPredicate(), "unknown");
		assertTrue(unknown, unknown.contains("no started plugin offers 'ext:asn'") && unknown.contains("prefix"));
		String refused = PluginPredicates.check(rule(engine, "refused").getPredicates().get(0).pluginPredicate(), "refused");
		assertTrue(refused, refused.contains("a prefix starts with '/'"));
		assertFalse(engine.evaluate(request("/")).isTerminal());

		// what set-config is told before it writes the file
		List<String> errors = engine.validate("a | ext:asn:1 | reject:403\nb | ext:prefix:/ok | reject:403\nc | ext:prefix:x | reject:403\n");
		assertEquals(2, errors.size());
		assertTrue(errors.get(0), errors.get(0).startsWith("line 1: no started plugin offers"));
		assertTrue(errors.get(1), errors.get(1).startsWith("line 3: plugin 'geo' refuses"));
	}

	@Test
	public void stoppedPluginsAnswerNothingAndARestartRebinds() throws IOException {
		rules("geo-block | ext:prefix:/blocked | reject:403");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);
		assertTrue(engine.evaluate(request("/blocked")).isTerminal());

		FrontcachePlugins.stop(false);
		assertFalse(engine.evaluate(request("/blocked")).isTerminal());

		FrontcachePlugins.start(null, engine::getRules);
		assertTrue(engine.evaluate(request("/blocked")).isTerminal());
	}

	@Test
	public void aPredicateWithoutDeclaringDecideIsRefused() throws IOException {
		installPlugins("sneaky", Undeclared.class.getName(), "manage");
		FrontcachePlugins.start(null, List::of);

		PluginStats stats = FrontcachePlugins.stats("sneaky");
		assertEquals("refused", stats.getState());
		assertTrue(stats.getReason(), stats.getReason().contains("'decide'"));
		assertFalse(PluginPredicates.offered().containsKey("sneaky"));
	}

	@Test
	public void botRulesUseThemToo() throws IOException {
		installPlugins("geo", Geo.class.getName(), "decide");
		FrontcachePlugins.start(null, List::of);

		BotRule rule = BotRuleParser.parse("feed-reader | ext:prefix:/feed | bot");
		assertTrue(rule.matches(request("/feed/rss")));
		assertFalse(rule.matches(request("/home")));
		assertFalse(BotRuleParser.parse("x | !ext:boom | bot").matches(request("/")));
		assertTrue(BotClassifier.validate("y | ext:nothing | bot\n").get(0).contains("no started plugin offers"));
	}

	@Test
	public void theViewHidesCredentialsAndTheNodesOwnHeaders() throws IOException {
		rules("geo-block | ext:prefix:/x | reject:403");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		RequestContext context = new ServletFakes.Request().uri("/x?q=1").header("Authorization", "Basic c2VjcmV0")
				.header("X-Frontcache-Warm", "token").header("X-Ja4", "t13d1516h2").cookie("session", "s3cr3t").context();
		engine.evaluate(context);

		RequestView view = Geo.lastView;
		assertNotNull(view);
		assertEquals("/x", view.uri());
		assertEquals("q=1", view.query());
		assertEquals("GET", view.method());
		assertEquals("t13d1516h2", view.header("x-ja4"));
		assertNull(view.header("Authorization"));
		assertNull(view.header("Cookie"));
		assertNull(view.header("X-Frontcache-Warm"));
		assertEquals(Set.of("session"), view.cookieNames());
		assertEquals("203.0.113.7", view.clientAddress());
	}

	// ---- the budget --------------------------------------------------------------------------------------------

	@Test
	public void aPluginOverItsBudgetIsSwitchedOffAndTriedAgainLater() throws Exception {
		rules("slow | ext:slow:300 | reject:403");
		Watcher.seen.clear();
		Watcher.latch = new CountDownLatch(1);
		installPlugins("geo", Geo.class.getName(), "decide", "watcher", Watcher.class.getName(), "observe");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		// 300us a call, against 200us: a minute of it - only once enough calls were seen - switches it off
		for (int i = 0; i < 99; i++)
			assertTrue(engine.evaluate(request("/")).isTerminal());
		clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
		assertTrue("the minute is judged by the call that crosses it", engine.evaluate(request("/")).isTerminal());
		PluginStats stats = FrontcachePlugins.stats("geo");
		assertEquals(PluginStats.DECIDE_OVER_BUDGET, stats.getDecideState());
		assertTrue(stats.getDecideReason(), stats.getDecideReason().contains("over its budget"));
		assertEquals(1, stats.getDecideSwitchedOff());
		assertTrue(stats.getDecideRetryAt() > System.currentTimeMillis());
		assertTrue(stats.getDecideP99Micros() >= 300);
		assertTrue(Watcher.latch.await(5, TimeUnit.SECONDS));

		long calls = stats.getDecideCalls();
		assertFalse("switched off: the rule does not match", engine.evaluate(request("/")).isTerminal());
		assertEquals("and the plugin is not asked", calls, FrontcachePlugins.stats("geo").getDecideCalls());

		clock.addAndGet(TimeUnit.MINUTES.toNanos(DecideBudget.OFF_MINUTES));
		assertTrue("tried again after five minutes", engine.evaluate(request("/")).isTerminal());
		assertEquals(PluginStats.DECIDE_OK, FrontcachePlugins.stats("geo").getDecideState());
	}

	@Test
	public void aPluginWithinItsBudgetOrWithTooFewCallsStaysOn() throws IOException {
		FCConfig.setProperty("front-cache.plugins.geo.decide-budget", "0.5ms");
		rules("slow | ext:slow:300 | reject:403", "slower | uri~^/slower ; ext:slow:900 | reject:404");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		for (int i = 0; i < 200; i++)
			engine.evaluate(request("/"));
		clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
		engine.evaluate(request("/"));
		assertEquals(PluginStats.DECIDE_OK, FrontcachePlugins.stats("geo").getDecideState());
		assertEquals(500, FrontcachePlugins.stats("geo").getDecideBudgetMicros());

		// 900us each, but under the minimum a minute is judged on: a cold start is not an outage
		for (int i = 0; i < 50; i++)
			engine.evaluate(request("/slower"));
		clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
		engine.evaluate(request("/"));
		assertEquals(PluginStats.DECIDE_OK, FrontcachePlugins.stats("geo").getDecideState());
	}

	@Test
	public void aPluginThrowingOnMoreThanOnePercentIsSwitchedOff() throws IOException {
		rules("geo-block | ext:prefix:/x | reject:403");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		for (int i = 0; i < 200; i++)
			engine.evaluate(request("/x"));
		Geo.flaky = true;
		for (int i = 0; i < 3; i++)
			assertFalse(engine.evaluate(request("/x")).isTerminal());
		Geo.flaky = false;
		clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
		engine.evaluate(request("/x"));

		PluginStats stats = FrontcachePlugins.stats("geo");
		assertEquals(PluginStats.DECIDE_OVER_BUDGET, stats.getDecideState());
		assertTrue(stats.getDecideReason(), stats.getDecideReason().contains("threw on 3 of"));
	}

	@Test
	public void anAllowRuleOverAPredicateWithNoAnswerDoesNotExempt() throws IOException {
		rules("office | ext:boom | allow | on-error=allow", "everyone | uri~^/ | reject:403");
		installPlugins("geo", Geo.class.getName(), "decide");
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		FrontcachePlugins.start(null, engine::getRules);

		GuardOutcome outcome = engine.evaluate(request("/"));
		assertTrue(outcome.isTerminal());
		assertEquals("everyone", outcome.getRule().getName());
	}

	@Test
	public void budgetsParse() {
		assertEquals(200_000, DecideBudget.parseDuration("200"));
		assertEquals(200_000, DecideBudget.parseDuration("200us"));
		assertEquals(200_000, DecideBudget.parseDuration("200µs"));
		assertEquals(500_000, DecideBudget.parseDuration("0.5ms"));
		assertEquals(750, DecideBudget.parseDuration("750ns"));
		assertEquals(-1, DecideBudget.parseDuration("fast"));
		assertEquals(-1, DecideBudget.parseDuration("-3us"));
	}
}
