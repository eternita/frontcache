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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.frontcache.FCConfig;
import org.frontcache.core.EvidenceCookies;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardAction;
import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardOutcome;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEngine;
import org.frontcache.guard.GuardRuleParser;
import org.frontcache.plugin.PluginManifest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The {@code challenge <provider> [else <action>]} guard action: a redirect to the page of the plugin offering the
 * provider, which knows what a redirect does not - which provider, whether it is there, that a POST or an include
 * is never sent away, that the provider's own path is never challenged, and that a visitor holding a pass is not
 * asked again.
 */
public class ChallengeRuleTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private String previousHome;

	private static final String RULES = String.join("\n",
			"search-challenge | uri~^/search | challenge pow",
			"cart-challenge   | uri~^/cart   | challenge pow else reject:429 slow down",
			"absent-challenge | uri~^/absent | challenge turnstile",
			"absent-else      | uri~^/other  | challenge turnstile else redirect:302 /busy.html",
			"watch-challenge  | uri~^/watch  | challenge pow | dry-run",
			"");

	@Before
	public void setUp() throws IOException {
		File home = folder.newFolder("home");
		new File(home, "conf").mkdirs();
		Files.writeString(new File(home, "conf/guard-rules.conf").toPath(), RULES, StandardCharsets.UTF_8);
		previousHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, home.getAbsolutePath());
		FCConfig.setProperty("front-cache.guard-rules.enabled", "true");
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "0123456789abcdef0123456789abcdef-test");
		installProvider("challenge", "pow");
	}

	@After
	public void tearDown() {
		PluginEndpoints.clear();
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
		if (null == previousHome)
			System.clearProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
		else
			System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, previousHome);
	}

	static PluginManifest manifest(String id, String kinds, String cookies, String challenges) {
		Properties p = new Properties();
		p.setProperty("id", id);
		p.setProperty("class", "com.example.Plugin");
		p.setProperty("version", "1.0.0");
		p.setProperty("plugin-api", "1");
		p.setProperty("kinds", kinds);
		if (null != cookies)
			p.setProperty("cookies", cookies);
		if (null != challenges)
			p.setProperty("challenges", challenges);
		return PluginManifest.parse(p, "test:" + id);
	}

	static void installProvider(String plugin, String provider) {
		PluginManifest manifest = manifest(plugin, "serve", "fc_pass", provider);
		PluginEndpoints.install(Map.of(plugin, PluginEndpoints.served(plugin, manifest, (req, res) -> {
		}, new PluginCounters())));
	}

	private static GuardRule rule(GuardRuleEngine engine, String name) {
		for (GuardRule rule : engine.getRules())
			if (rule.getName().equals(name))
				return rule;
		throw new AssertionError("no rule " + name);
	}

	// ---- parsing ---------------------------------------------------------------------------------------------------

	@Test
	public void theActionParsesWithAnOptionalElse() {
		GuardRuleParser parser = new GuardRuleParser(Set.of());

		GuardAction plain = parser.parse("r | uri~^/a | challenge pow").getAction();
		assertEquals(GuardAction.Type.CHALLENGE, plain.getType());
		assertEquals("pow", plain.getProvider());
		assertNull(plain.getOtherwise());
		assertEquals(GuardAction.DISPOSITION_CHALLENGED, plain.getDisposition());
		assertEquals("challenge pow", plain.toString());

		GuardAction withElse = parser.parse("r | uri~^/a | Challenge  pow   else   reject:429 slow down").getAction();
		assertEquals("pow", withElse.getProvider());
		assertEquals(GuardAction.Type.REJECT, withElse.getOtherwise().getType());
		assertEquals("slow down", withElse.getOtherwise().getBody());
		assertEquals("challenge pow else reject:429", withElse.toString());

		assertEquals(GuardAction.Type.REDIRECT,
				parser.parse("r | uri~^/a | challenge pow else redirect:302 /busy").getAction().getOtherwise().getType());

		for (String refused : new String[] { "challenge", "challenge Pow", "challenge pow then reject:429",
				"challenge pow else", "challenge pow else allow", "challenge pow else challenge js",
				"challenge pow else redirect:302 https://evil.example.com/", "challenges pow" })
			try {
				parser.parse("r | uri~^/a | " + refused);
				fail("refused: " + refused);
			} catch (GuardConfigException expected) {
			}
	}

	// ---- evaluation ------------------------------------------------------------------------------------------------

	@Test
	public void aGetIsSentToTheProvidersPageWithItsWayBack() {
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		RequestContext context = new ServletFakes.Request().uri("/search?q=x&page=2").context();

		GuardOutcome outcome = engine.evaluate(context);

		assertTrue(outcome.isTerminal());
		assertEquals("search-challenge", outcome.getRule().getName());
		assertEquals(GuardAction.Type.CHALLENGE, outcome.getAction().getType());
		assertEquals("challenge", outcome.getChallengePlugin());
		assertEquals("/fc-x/challenge/?p=pow&return=%2Fsearch%3Fq%3Dx%26page%3D2", outcome.getResolvedTarget());
		assertEquals(1, rule(engine, "search-challenge").getHits());
	}

	@Test
	public void aPostIsRefusedWithATimeToRetryNeverRedirected() {
		GuardRuleEngine engine = new GuardRuleEngine(4096);
		RequestContext context = new ServletFakes.Request().method("POST").uri("/search").context();

		GuardOutcome outcome = engine.evaluate(context);

		assertTrue(outcome.isTerminal());
		assertEquals(GuardAction.Type.REJECT, outcome.getAction().getType());
		assertEquals(429, outcome.getAction().getStatus());
		assertEquals(GuardAction.DISPOSITION_REJECTED, outcome.getAction().getDisposition());
		assertEquals(60, context.getGuardRetryAfterSeconds());
		assertNull(outcome.getChallengePlugin());
	}

	@Test
	public void anIncludeTheProvidersPathAndAVisitorWithAPassAreNotChallenged() {
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		assertSame(GuardOutcome.CONTINUE, engine.evaluate(
				new ServletFakes.Request().uri("/search").type(FCHeaders.COMPONENT_INCLUDE).context()));

		long expiry = System.currentTimeMillis() / 1000L + 600;
		String pass = EvidenceCookies.sign("fc_pass", expiry, null, null, null);
		assertSame("a pass from the provider's plugin is honoured", GuardOutcome.CONTINUE,
				engine.evaluate(new ServletFakes.Request().uri("/search").cookie("fc_pass", pass).context()));
		assertSame("in a dry run too", GuardOutcome.CONTINUE,
				engine.evaluate(new ServletFakes.Request().uri("/watch").cookie("fc_pass", pass).context()));
		assertTrue("a forged one is not", engine.evaluate(
				new ServletFakes.Request().uri("/search").cookie("fc_pass", expiry + ".forged").context()).isTerminal());

		assertEquals("only the forged one counted", 1, rule(engine, "search-challenge").getHits());
	}

	@Test
	public void theProvidersOwnPathIsNeverChallenged() throws IOException {
		Files.writeString(new File(System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY), "conf/guard-rules.conf")
				.toPath(), "everything | uri~^/ | challenge pow\n", StandardCharsets.UTF_8);
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		assertSame(GuardOutcome.CONTINUE, engine.evaluate(new ServletFakes.Request().uri("/fc-x/challenge/?p=pow").context()));
		assertSame(GuardOutcome.CONTINUE, engine.evaluate(new ServletFakes.Request().uri("/fc-x/challenge/verify").context()));
		assertTrue(engine.evaluate(new ServletFakes.Request().uri("/fc-xyz").context()).isTerminal());
	}

	@Test
	public void anAbsentProviderIsADryRunOrTheElseAction() {
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		GuardOutcome absent = engine.evaluate(new ServletFakes.Request().uri("/absent").context());
		assertFalse("a Community node serves the request as it would without the rule", absent.isTerminal());
		assertEquals(List.of("absent-challenge"), absent.getDryRunMatches().stream().map(GuardRule::getName).toList());

		GuardOutcome otherwise = engine.evaluate(new ServletFakes.Request().uri("/other").context());
		assertTrue(otherwise.isTerminal());
		assertEquals(GuardAction.Type.REDIRECT, otherwise.getAction().getType());
		assertEquals("/busy.html", otherwise.getResolvedTarget());

		GuardOutcome cart = engine.evaluate(new ServletFakes.Request().uri("/cart").context());
		assertEquals("the provider is there - the else is not used", GuardAction.Type.CHALLENGE,
				cart.getAction().getType());
	}

	@Test
	public void withoutASecretNoChallengeIsIssued() {
		FCConfig.setProperty(EvidenceCookies.SECRET_PROPERTY, "");
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		assertNull("a challenge no one can pass is never issued", PluginEndpoints.challenge("pow"));
		GuardOutcome outcome = engine.evaluate(new ServletFakes.Request().uri("/search").context());
		assertFalse(outcome.isTerminal());
		assertEquals("search-challenge", outcome.getDryRunMatches().get(0).getName());

		GuardOutcome cart = engine.evaluate(new ServletFakes.Request().uri("/cart").context());
		assertEquals(429, cart.getAction().getStatus());
		assertEquals("slow down", cart.getAction().getBody());
	}

	@Test
	public void theGuardSummarySaysWhatAChallengeRuleDoesNow() {
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		assertEquals(GuardAction.DISPOSITION_CHALLENGED, GuardSummaryTicker.disposition(rule(engine, "search-challenge")));
		assertEquals("provider absent", GuardAction.DISPOSITION_DRY_RUN,
				GuardSummaryTicker.disposition(rule(engine, "absent-challenge")));
		assertEquals("provider absent: its else", GuardAction.DISPOSITION_REDIRECTED,
				GuardSummaryTicker.disposition(rule(engine, "absent-else")));
		assertEquals(GuardAction.DISPOSITION_DRY_RUN, GuardSummaryTicker.disposition(rule(engine, "watch-challenge")));

		PluginEndpoints.clear();
		assertEquals("the plugin stopped", GuardAction.DISPOSITION_REJECTED,
				GuardSummaryTicker.disposition(rule(engine, "cart-challenge")));
	}

	@Test
	public void aPluginMayRedirectOnlyWhereAGuardRuleMay() {
		String previousDomain = FCConfig.getProperty("front-cache.default-domain", FCConfig.DEFAULT_DOMAIN);
		FCConfig.setProperty("front-cache.default-domain", "www.example.com");
		GuardRuleEngine engine;
		try {
			engine = new GuardRuleEngine(4096);
		} finally {
			FCConfig.setProperty("front-cache.default-domain", previousDomain);
		}

		assertTrue(engine.isAllowedRedirectTarget("/search?q=x"));
		assertTrue(engine.isAllowedRedirectTarget("https://www.example.com/search"));
		assertFalse(engine.isAllowedRedirectTarget("https://evil.example.com/"));
		assertFalse(engine.isAllowedRedirectTarget("//evil.example.com/"));
		assertFalse(engine.isAllowedRedirectTarget("/\\evil.example.com/"));
		assertFalse(engine.isAllowedRedirectTarget("/ok\r\nSet-Cookie: x=y"));
		assertFalse(engine.isAllowedRedirectTarget("javascript:alert(1)"));
		assertFalse(engine.isAllowedRedirectTarget("https://www.example.com@evil.example.com/"));
		assertFalse(engine.isAllowedRedirectTarget(""));
		assertFalse(engine.isAllowedRedirectTarget(null));
	}
}
