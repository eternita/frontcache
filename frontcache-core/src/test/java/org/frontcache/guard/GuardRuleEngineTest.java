/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.FCConfig;
import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.ratelimit.GuardRateLimiterRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The guard pipeline end to end: loading src/test/resources/guard-home/conf/guard-rules.conf,
 * ordering, the built-in rules migrated out of FrontCacheEngine, dry-run, and the safety rails
 * (loop detection, open-redirect refusal, bad lines skipped).
 *
 * The fixture file deliberately contains broken rules; the point of several tests here is that the
 * good rules around them still load.
 */
public class GuardRuleEngineTest {

	private static final int MAX_URI_LENGTH = 4096;

	private static GuardRuleEngine engine;

	@BeforeClass
	public static void setUpClass() throws Exception {
		// Point frontcache.home at the fixture BEFORE the engine (and thus FCConfig) is touched.
		// FCConfig.getConfigInputStream() reads this property live, so guard-rules.conf resolves
		// here even if another test class already ran FCConfig's one-time static init.
		URL confMarker = GuardRuleEngineTest.class.getClassLoader().getResource("guard-home/conf/guard-rules.conf");
		assertNotNull("fixture guard-home/conf/guard-rules.conf not found on test classpath", confMarker);
		File guardHome = new File(confMarker.toURI()).getParentFile().getParentFile();
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, guardHome.getAbsolutePath());

		// ...but properties come from whichever frontcache.properties FCConfig loaded first, so set
		// the one value the fixture rules depend on explicitly.
		FCConfig.setProperty("front-cache.guard-rules.allowed-redirect-hosts", "www.example-site.com");

		// This node's own site. Deliberately NOT one of the allowed-redirect-hosts above: as of
		// docs/guard-redirect-url-host-fix.md it is allowed as a redirect target on its own, which
		// is what the own-site fixture rule exists to prove. It is also what the runtime sanitizer
		// rewrites a disallowed embedded host onto.
		FCConfig.setProperty("front-cache.default-domain", "own.example.com");

		// The rate-limit registry is process-scoped (it has to survive a reload), so start from a
		// known state rather than from whatever another test class left behind.
		GuardRateLimiterRegistry.reset();

		engine = new GuardRuleEngine(MAX_URI_LENGTH);
	}

	// ---- built-ins (migrated from FrontCacheEngine.processRequest) --------------------------

	@Test
	public void builtInsComeFirstAndKeepTheirNames() {
		List<GuardRule> rules = engine.getRules();

		assertEquals(GuardRuleEngine.RULE_URI_TOO_LONG, rules.get(0).getName());
		assertEquals(GuardRuleEngine.RULE_BAD_REQUEST, rules.get(1).getName());
	}

	@Test
	public void longUriIsRejectedWith414() {
		StringBuilder uri = new StringBuilder("/en/");
		while (uri.length() <= MAX_URI_LENGTH)
			uri.append("aaaaaaaaaa");

		GuardOutcome outcome = engine.evaluate(GuardRuleEngine.probeContext("https://www.example-site.com" + uri));

		assertTrue(outcome.isTerminal());
		assertEquals(GuardRuleEngine.RULE_URI_TOO_LONG, outcome.getRule().getName());
		assertEquals(414, outcome.getAction().getStatus());
		assertEquals(GuardAction.DISPOSITION_REJECTED, outcome.getAction().getDisposition());
	}

	@Test
	public void mangledQueryIsRejectedWith400() {
		GuardOutcome outcome = engine.evaluate(
				GuardRuleEngine.probeContext("https://www.example-site.com/en/welcome.htm?a=1&amp;amp;b=2"));

		assertTrue(outcome.isTerminal());
		assertEquals(GuardRuleEngine.RULE_BAD_REQUEST, outcome.getRule().getName());
		assertEquals(400, outcome.getAction().getStatus());
	}

	@Test
	public void ordinaryRequestPassesThrough() {
		GuardOutcome outcome = engine.evaluate(GuardRuleEngine.probeContext("https://www.example-site.com/en/welcome.htm"));

		assertFalse(outcome.isTerminal());
		assertTrue(outcome.getDryRunMatches().isEmpty());
	}

	// ---- use case 1: access by IP -----------------------------------------------------------

	@Test
	public void requestByIpIsRedirected() {
		GuardOutcome outcome = engine.evaluate(GuardRuleEngine.probeContext("http://160.202.254.65/whatever.htm"));

		assertTrue(outcome.isTerminal());
		assertEquals("ip-access", outcome.getRule().getName());
		assertEquals(301, outcome.getAction().getStatus());
		assertEquals("https://www.example-site.com/en/welcome.htm", outcome.getResolvedTarget());
	}

	@Test
	public void healthCheckAndManagementApiStayReachableByIp() {
		// the exemptions that keep the ip-access rule from becoming an outage: GSLB pings the node
		// by IP, and frontcache-agent / console / replication call the management servlet by IP
		assertFalse(engine.evaluate(GuardRuleEngine.probeContext("http://160.202.254.65/fc-ping.jsp")).isTerminal());
		assertFalse(engine.evaluate(GuardRuleEngine.probeContext("http://160.202.254.65/frontcache-io?action=ping")).isTerminal());
	}

	// ---- use case 2: no session cookie on account-only pages --------------------------------

	@Test
	public void anonymousRequestToAccountPageIsRedirectedToLoginWithReturnUrl() {
		RequestContext context = requestContext(
				"https://www.example-site.com/en/ccc/va/visited-coins-analytics.htm?itemTypeFQ=itemType:coin",
				new Cookie[] { new Cookie("other", "1") });

		GuardOutcome outcome = engine.evaluate(context);

		assertTrue(outcome.isTerminal());
		assertEquals("anon-analytics", outcome.getRule().getName());
		assertEquals(302, outcome.getAction().getStatus());
		assertEquals("https://www.example-site.com/login.htm?return="
				+ "https%3A%2F%2Fwww.example-site.com%2Fen%2Fccc%2Fva%2Fvisited-coins-analytics.htm%3FitemTypeFQ%3DitemType%3Acoin",
				outcome.getResolvedTarget());
	}

	@Test
	public void loggedInRequestToAccountPageIsUntouched() {
		RequestContext context = requestContext(
				"https://www.example-site.com/en/ccc/va/visited-coins-analytics.htm",
				new Cookie[] { new Cookie("hruc", "session-value") });

		assertFalse(engine.evaluate(context).isTerminal());
	}

	@Test
	public void botsAreRedirectedLikeBrowsers() {
		// decided during review: no client-type predicate on the cookie rule, so a crawler with no
		// session cookie is redirected instead of costing an origin render
		RequestContext context = requestContext("https://www.example-site.com/en/ccc/va/visited.htm", new Cookie[0]);
		context.setClientType("bot");

		GuardOutcome outcome = engine.evaluate(context);

		assertTrue(outcome.isTerminal());
		assertEquals("anon-analytics", outcome.getRule().getName());
	}

	@Test
	public void loginPageIsExemptSoTheRedirectCannotLoop() {
		assertFalse(engine.evaluate(GuardRuleEngine.probeContext("https://www.example-site.com/login.htm")).isTerminal());
	}

	// ---- dry-run -----------------------------------------------------------------------------

	@Test
	public void dryRunRuleIsReportedButNeverActedOn() {
		RequestContext context = requestContext("https://www.example-site.com/search.htm?q=coins", new Cookie[0]);
		context.setClientType("bot");

		GuardOutcome outcome = engine.evaluate(context);

		assertFalse("dry-run must not stop the request", outcome.isTerminal());
		assertEquals(1, outcome.getDryRunMatches().size());
		assertEquals("noisy-bots", outcome.getDryRunMatches().get(0).getName());
	}

	// ---- safety rails ------------------------------------------------------------------------

	@Test
	public void unsafeAndBrokenRulesAreSkippedWhileTheRestLoad() {
		for (GuardRule rule : engine.getRules())
		{
			String name = rule.getName();
			assertFalse("open redirect must not load", "open-redirect".equals(name));
			assertFalse("self-redirect loop must not load", "self-loop".equals(name));
			assertFalse("invalid regex must not load", "bad-regex".equals(name));
			assertFalse("unknown predicate must not load", "unknown-pred".equals(name));
			assertFalse("action-less rule must not load", "no-action".equals(name));
		}

		for (GuardRule rule : engine.getRules())
		{
			String name = rule.getName();
			assertFalse("unparseable rate window must not load", "bad-rate".equals(name));
			assertFalse("two rate predicates in one rule must not load", "double-rate".equals(name));
			assertFalse("a negated rate predicate must not load", "negated-rate".equals(name));
		}

		// 2 built-ins + 3 allow + ip-access + anon-analytics + noisy-bots(dry-run) + own-site + api-flood
		assertEquals(10, engine.getRules().size());
	}

	/**
	 * front-cache.default-domain is an allowed redirect target without being repeated in
	 * front-cache.guard-rules.allowed-redirect-hosts.
	 *
	 * This settles the follow-up left open by the multi-domain retirement (the guard javadoc claimed
	 * it was allowed; the code never did it). It HAS to be allowed now: ${url} and ${host} emit that
	 * hostname, so a rule redirecting to your own site would be refused at load time against an
	 * allowlist that does not contain the one name the node calls itself.
	 */
	@Test
	public void aRedirectToThisNodesOwnSiteIsAllowedWithoutBeingListed() {
		assertFalse("own.example.com must not be in allowed-redirect-hosts, or this proves nothing",
				FCConfig.getProperty("front-cache.guard-rules.allowed-redirect-hosts", "").contains("own.example.com"));

		GuardOutcome outcome = engine.evaluate(GuardRuleEngine.probeContext("https://www.example-site.com/own-site.htm"));

		assertTrue(outcome.isTerminal());
		assertEquals("own-site", outcome.getRule().getName());
		assertEquals("https://own.example.com/welcome.htm", outcome.getResolvedTarget());
	}

	/**
	 * The regression that would quietly disable every host-based rule.
	 *
	 * Placeholders now emit the public host, but predicates must keep matching what actually
	 * arrived - they ask the opposite question, and 'reject requests that arrive under a bare IP' is
	 * meaningless if the host is normalized before it is matched. Here the node has a perfectly good
	 * public name and the request still arrived at an IP: host:ip must fire.
	 */
	@Test
	public void hostPredicatesStillMatchTheArrivingHostNotThePublicOne() {
		RequestContext context = GuardRuleEngine.probeContext("http://160.202.254.65/a.htm");
		context.setPublicHost("own.example.com");

		GuardOutcome outcome = engine.evaluate(context);

		assertTrue("host:ip must read the Host that arrived", outcome.isTerminal());
		assertEquals("ip-access", outcome.getRule().getName());
	}

	// ---- runtime host check on the expanded target -------------------------------------------

	/**
	 * The load-time check validates the target as WRITTEN in guard-rules.conf. A host that only
	 * appears once ${...} is expanded - which is every request, since ${url} carries one - is
	 * invisible to it. These cover what catches those.
	 */
	@Test
	public void anEmbeddedDisallowedHostIsRewrittenOntoTheOwnSite() {
		GuardRule rule = ruleByName("anon-analytics");

		String sanitized = engine.sanitizeTarget(rule,
				"https://www.example-site.com/login.htm?return=https%3A%2F%2Forigin.example-site.com%2Fen%2Fx.htm");

		assertFalse("the origin hostname must not survive: " + sanitized, sanitized.contains("origin.example-site.com"));
		assertTrue(sanitized, sanitized.contains("own.example.com"));
	}

	@Test
	public void anEmbeddedAllowedHostIsLeftExactlyAsItWas() {
		GuardRule rule = ruleByName("anon-analytics");
		String target = "https://www.example-site.com/login.htm?return=https%3A%2F%2Fwww.example-site.com%2Fen%2Fx.htm&a=1";

		assertEquals(target, engine.sanitizeTarget(rule, target));
	}

	/**
	 * A parameter that is not an absolute URL must come back byte for byte - the sanitizer
	 * re-serializes the query, and a value it does not understand is a value it must not touch.
	 */
	@Test
	public void ordinaryParametersAreNotRewritten() {
		GuardRule rule = ruleByName("anon-analytics");
		String target = "https://www.example-site.com/login.htm?q=coins&empty=&flag&path=%2Fen%2Fx.htm";

		assertEquals(target, engine.sanitizeTarget(rule, target));
	}

	/**
	 * "https://www.example-site.com@evil.example.com/" is a URL whose host is evil.example.com. Reading
	 * the part before the '@' as the host is the classic way to be fooled by one.
	 */
	@Test
	public void userinfoDoesNotDisguiseTheRealHost() {
		assertEquals("evil.example.com", GuardRuleEngine.hostOf("https://www.example-site.com@evil.example.com/x"));
		assertEquals("evil.example.com", GuardRuleEngine.hostOf("https://evil.example.com:8443/x"));
		assertEquals("www.example-site.com", GuardRuleEngine.hostOf("https://www.example-site.com/x?u=https://evil.example.com/"));

		// not absolute URLs, so not hosts
		assertEquals(null, GuardRuleEngine.hostOf("/login.htm"));
		assertEquals(null, GuardRuleEngine.hostOf("coins"));
		assertEquals(null, GuardRuleEngine.hostOf("mailto:someone@example.com"));
	}

	/**
	 * A disallowed host on the TARGET ITSELF is an open redirect, so the rule does not act at all -
	 * unlike a bad embedded parameter, which is only rewritten. The request carries on to
	 * cache/origin, which is Frontcache-as-usual rather than an outage.
	 */
	@Test
	public void aDisallowedTargetHostSkipsTheRule() {
		GuardRule rule = ruleByName("anon-analytics");

		assertEquals(null, engine.sanitizeTarget(rule, "https://evil.example.com/login.htm"));
	}

	@Test
	public void aRelativeTargetIsNeverTouched() {
		GuardRule rule = ruleByName("anon-analytics");

		assertEquals("/login.htm?return=%2Fen%2Fx.htm",
				engine.sanitizeTarget(rule, "/login.htm?return=%2Fen%2Fx.htm"));
	}

	/**
	 * "Unchanged" has to mean byte-identical: the sanitizer re-serializes the query to do its work,
	 * and the caller compares the result with the input to decide whether to rebuild the target. An
	 * empty segment quietly dropped here would turn every redirect into a rewritten one.
	 */
	@Test
	public void anUnusualButHarmlessQueryComesBackByteForByte() {
		GuardRule rule = ruleByName("anon-analytics");
		String[] targets = {
				"https://www.example-site.com/login.htm?a=1&",
				"https://www.example-site.com/login.htm?&a=1",
				"https://www.example-site.com/login.htm?a=1&&b=2",
				"https://www.example-site.com/login.htm?",
				"https://www.example-site.com/login.htm",
		};

		for (String target : targets)
			assertEquals(target, engine.sanitizeTarget(rule, target));
	}

	/**
	 * With no public host to put there instead, a disallowed embedded URL is dropped rather than
	 * left in place - and the rule still acts. Losing a return= costs the visitor their place;
	 * skipping the guard would let the abusive request through to origin, which is the thing the
	 * rule exists to stop.
	 */
	@Test
	public void withNoOwnSiteADisallowedEmbeddedHostIsDropped() {
		GuardRule rule = ruleByName("anon-analytics");
		String saved = FCConfig.getProperty("front-cache.default-domain", "");
		try {
			FCConfig.setProperty("front-cache.default-domain", "");

			assertEquals("https://www.example-site.com/login.htm?a=1",
					engine.sanitizeTarget(rule,
							"https://www.example-site.com/login.htm?return=https%3A%2F%2Forigin.example-site.com%2Fx&a=1"));
		} finally {
			FCConfig.setProperty("front-cache.default-domain", saved);
		}
	}

	@Test
	public void startupProbesDoNotInflateHitCounters() {
		// load() self-checks the health-check / management / dashboard-stream paths against an IP host;
		// those synthetic probes must not look like traffic in the management API's hit counts
		GuardRuleEngine fresh = new GuardRuleEngine(MAX_URI_LENGTH);

		for (GuardRule rule : fresh.getRules())
			assertEquals("rule " + rule.getName() + " counted a startup probe", 0, rule.getHits());
	}

	@Test
	public void hitCountersCountMatches() {
		GuardRule ipAccess = ruleByName("ip-access");
		long before = ipAccess.getHits();

		engine.evaluate(GuardRuleEngine.probeContext("http://160.202.254.65/a.htm"));
		engine.evaluate(GuardRuleEngine.probeContext("http://160.202.254.65/b.htm"));

		assertEquals(before + 2, ipAccess.getHits());
	}

	// ---- per-IP rate limiting ----------------------------------------------------------------

	@Test
	public void aRateLimitedClientIsRejectedWith429AndTheConditionReadsAsWritten() {
		ClientIpResolver.install(new ClientIpResolver("", "X-Forwarded-For"));
		try {
			GuardRule apiFlood = ruleByName("api-flood");
			assertEquals("uri~^/api/ ; rate:3/10s", apiFlood.getCondition());

			for (int i = 0; i < 3; i++)
				assertFalse("request " + (i + 1) + " of 3 must pass",
						engine.evaluate(apiRequest("203.0.113.7")).isTerminal());

			RequestContext overLimit = apiRequest("203.0.113.7");
			GuardOutcome outcome = engine.evaluate(overLimit);

			assertTrue(outcome.isTerminal());
			assertEquals("api-flood", outcome.getRule().getName());
			assertEquals(429, outcome.getAction().getStatus());
			assertTrue("Retry-After must be carried to the response", 0 < overLimit.getGuardRetryAfterSeconds());

			assertFalse("a different client is unaffected", engine.evaluate(apiRequest("198.51.100.9")).isTerminal());
		} finally {
			ClientIpResolver.reload();
		}
	}

	@Test
	public void theStartupSelfCheckDoesNotSpendAnyClientsBudget() {
		// load() evaluates the health-check / management / dashboard paths against every rule. Those
		// probes must not touch the counter - otherwise every reload quietly consumes a budget, and
		// the self-check would report nonsense for a rate rule.
		GuardRuleEngine fresh = new GuardRuleEngine(MAX_URI_LENGTH);

		for (GuardRule rule : fresh.getRules())
			assertEquals("rule " + rule.getName() + " counted a startup probe", 0, rule.getHits());
	}

	/**
	 * A top-level request to /api from one address. Rate limiting keys on the socket peer here (the
	 * test installs a resolver with no trusted proxies), and only counts toplevel requests.
	 */
	private static RequestContext apiRequest(final String remoteAddr) {
		RequestContext context = probeRequestContext("/api/items.htm", remoteAddr);
		context.setRequestType(FCHeaders.COMPONENT_TOPLEVEL);
		return context;
	}

	private static RequestContext probeRequestContext(String uri, final String remoteAddr) {
		// Built by hand rather than from probeContext(): that one marks itself as a load-time probe,
		// and this stands in for real traffic.
		RequestContext context = new RequestContext();
		context.setFrontCacheProtocol("https");
		context.setFrontCacheHost("www.example-site.com");
		context.setRequestURI(uri);
		context.setRequestQueryString("");

		context.setRequest((HttpServletRequest) Proxy.newProxyInstance(
				GuardRuleEngineTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getRemoteAddr":
							return remoteAddr;
						case "getHeaders":
							return java.util.Collections.enumeration(java.util.Collections.<String>emptyList());
						case "getMethod":
							return "GET";
						case "getServerPort":
							return Integer.valueOf(443);
						default:
							return null;
						}
					}
				}));

		return context;
	}

	private GuardRule ruleByName(String name) {
		for (GuardRule rule : engine.getRules())
			if (name.equals(rule.getName()))
				return rule;

		throw new IllegalStateException("rule not loaded: " + name);
	}

	/**
	 * Probe context plus a stand-in servlet request, so the cookie / method predicates have
	 * something to read. A dynamic proxy keeps this to a few lines - HttpServletRequest has far too
	 * many methods to implement by hand, and the core module has no mocking library.
	 */
	private static RequestContext requestContext(String url, final Cookie[] cookies) {
		RequestContext context = GuardRuleEngine.probeContext(url);

		HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
				GuardRuleEngineTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getCookies":
							return cookies;
						case "getMethod":
							return "GET";
						case "getServerPort":
							return Integer.valueOf(443);
						case "getHeader":
							return null;
						default:
							return null;
						}
					}
				});

		context.setRequest(request);
		return context;
	}

}
