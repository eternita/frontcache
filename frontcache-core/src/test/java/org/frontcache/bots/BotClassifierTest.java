/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.bots;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardConfigException;
import org.junit.Before;
import org.junit.Test;

/**
 * Classification from conf/bots.conf: the rule grammar, evaluation order, the hit counters the
 * console reports, and the two refusals that keep the file honest.
 *
 * Rules are built here rather than read from a fixture file - the file reading is one loop and the
 * interesting behaviour is all in what the rules then do.
 */
public class BotClassifierTest {

	@Before
	public void resetClientIpResolver() {
		// No trusted proxies: the default, and the setting the spoofing test depends on.
		ClientIpResolver.install(new ClientIpResolver("", "X-Forwarded-For"));
	}

	// ---- order and verdicts -----------------------------------------------------------------

	@Test
	public void firstMatchWinsSoAGuestRuleAboveIsAnExemption() {
		BotClassifier classifier = classifier(
				"partner     | ua~(?i)acme-crawler ; client-ip:127.0.0.1 | guest",
				"seo-tools   | ua~(?i)(acme-crawler|ahrefs)              | bot");

		assertEquals("partner", classifier.classify(request("acme-crawler/1.0", "127.0.0.1")).getName());
		assertEquals(FCHeaders.REQUEST_CLIENT_TYPE_GUEST,
				classifier.classify(request("acme-crawler/1.0", "127.0.0.1")).getClientType());

		// same UA from another address falls through to the rule below
		assertEquals("seo-tools", classifier.classify(request("acme-crawler/1.0", "203.0.113.9")).getName());
		assertEquals(FCHeaders.REQUEST_CLIENT_TYPE_BOT,
				classifier.classify(request("acme-crawler/1.0", "203.0.113.9")).getClientType());
	}

	@Test
	public void anythingUnclaimedIsAGuest() {
		BotClassifier classifier = classifier("seo-tools | ua~(?i)ahrefs | bot");

		BotRule decided = classifier.classify(request("Mozilla/5.0 (Macintosh) Chrome/140", "203.0.113.9"));

		assertEquals(BotClassifier.RULE_DEFAULT, decided.getName());
		assertEquals(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, decided.getClientType());
	}

	@Test
	public void aRequestWithNoUserAgentIsABot() {
		BotClassifier classifier = classifier();

		BotRule decided = classifier.classify(request(null, "203.0.113.9"));

		assertEquals(BotClassifier.RULE_NO_USER_AGENT, decided.getName());
		assertEquals(FCHeaders.REQUEST_CLIENT_TYPE_BOT, decided.getClientType());
	}

	/**
	 * The 2.9.0 behaviour change: before it, a file listing 'Googlebot' did not match 'googlebot/2.1'
	 * and nobody noticed, because the failure mode is a page rendered fresh from origin rather than
	 * an error.
	 */
	@Test
	public void bareKeywordsMatchWhateverCaseTheCrawlerAnnounces() {
		BotClassifier classifier = classifier("Googlebot");

		assertEquals("Googlebot", classifier.classify(request("Mozilla/5.0 (compatible; googlebot/2.1)", "66.249.64.1")).getName());
		assertEquals("Googlebot", classifier.classify(request("Mozilla/5.0 (compatible; Googlebot/2.1)", "66.249.64.1")).getName());
		assertEquals(BotClassifier.RULE_DEFAULT, classifier.classify(request("Mozilla/5.0 Chrome/140", "66.249.64.1")).getName());
	}

	// ---- hit counters (what the console's Hits column means) ---------------------------------

	@Test
	public void onlyTheDecidingRuleCountsAHit() {
		BotRule first = rule("headless | ua~(?i)headlesschrome | bot");
		BotRule second = rule("seo      | ua~(?i)ahrefs         | bot");
		BotClassifier classifier = BotClassifier.of(Arrays.asList(first, second));

		classifier.classify(request("HeadlessChrome/140", "203.0.113.9"));

		assertEquals(1, first.getHits());
		assertEquals("a rule below the one that decided is never even evaluated", 0, second.getHits());
	}

	@Test
	public void aRuleShadowedByABroaderOneAboveItStaysAtZero() {
		BotRule broad = rule("all-crawlers | ua~(?i)bot       | bot");
		BotRule narrow = rule("bingbot     | ua~(?i)bingbot   | bot");
		BotClassifier classifier = BotClassifier.of(Arrays.asList(broad, narrow));

		classifier.classify(request("Mozilla/5.0 (compatible; bingbot/2.0)", "203.0.113.9"));

		assertEquals(1, broad.getHits());
		assertEquals("zero hits is how the console says a rule is unreachable", 0, narrow.getHits());
	}

	@Test
	public void aDryRunRuleCountsButDoesNotDecide() {
		BotRule dryRun = rule("candidate | ua~(?i)chrome | bot | dry-run");
		BotRule live = rule("seo       | ua~(?i)chrome | guest");
		BotClassifier classifier = BotClassifier.of(Arrays.asList(dryRun, live));

		BotRule decided = classifier.classify(request("Chrome/140", "203.0.113.9"));

		assertEquals("dry-run must not decide", "seo", decided.getName());
		assertEquals("...but it must count, or it measures nothing", 1, dryRun.getHits());
		assertEquals(1, live.getHits());
	}

	@Test
	public void aProbeContextIsNotTraffic() {
		BotRule rule = rule("headless | ua~(?i)headlesschrome | bot");
		BotClassifier classifier = BotClassifier.of(Arrays.asList(rule));

		RequestContext probe = request("HeadlessChrome/140", "203.0.113.9");
		probe.setGuardProbe();
		classifier.classify(probe);

		assertEquals("a load-time self-check must not show up as traffic", 0, rule.getHits());
	}

	// ---- client-ip: is not spoofable ---------------------------------------------------------

	/**
	 * The single most important test here. {@code FCUtils.getClientIP()} walks thirteen
	 * client-supplied headers and returns the first non-empty one; if {@code client-ip:} read that,
	 * any client could put itself inside any block named in bots.conf - including a 'guest'
	 * exemption - by sending one header. It reads {@link ClientIpResolver} instead, which believes a
	 * forwarding header only from a configured trusted proxy.
	 */
	@Test
	public void clientIpIgnoresAForwardingHeaderFromAnUntrustedPeer() {
		BotClassifier classifier = classifier("partner | client-ip:198.51.100.7 | guest");

		RequestContext spoofed = request("curl/8.0", "203.0.113.9", "198.51.100.7");

		assertEquals("a client must not be able to claim an exempt address",
				BotClassifier.RULE_DEFAULT, classifier.classify(spoofed).getName());
	}

	@Test
	public void clientIpMatchesTheSocketPeerAndItsBlock() {
		BotClassifier classifier = classifier("office | client-ip:198.51.100.0/24 | guest");

		assertEquals("office", classifier.classify(request("curl/8.0", "198.51.100.7")).getName());
		assertEquals(BotClassifier.RULE_DEFAULT, classifier.classify(request("curl/8.0", "198.51.101.7")).getName());
	}

	@Test
	public void aV4BlockNeverCoversAV6Address() {
		BotClassifier classifier = classifier("office | client-ip:0.0.0.0/0 | guest");

		assertEquals(BotClassifier.RULE_DEFAULT, classifier.classify(request("curl/8.0", "2001:db8::1")).getName());
	}

	// ---- cookies -----------------------------------------------------------------------------

	@Test
	public void aMissingCookieCanClassify() {
		BotClassifier classifier = classifier("no-js-proof | ua~(?i)chrome ; !cookie:hruc | bot");

		RequestContext withCookie = request("Chrome/140", "203.0.113.9", null, new Cookie("hruc", "x"));

		assertEquals(BotClassifier.RULE_DEFAULT, classifier.classify(withCookie).getName());
		assertEquals("no-js-proof", classifier.classify(request("Chrome/140", "203.0.113.9")).getName());
	}

	// ---- what the file refuses ---------------------------------------------------------------

	@Test
	public void aThirdClientTypeIsRefused() {
		assertRefused("suspect | ua~(?i)curl | suspect", "not a client type");
	}

	@Test
	public void theRetiredBrowserSpellingIsRefusedWithAPointer() {
		assertRefused("old | ua~(?i)curl | browser", "renamed to 'guest' in 2.9.0");
	}

	/**
	 * Circular: classification is what computes the value that predicate would read.
	 */
	@Test
	public void clientTypePredicateIsRefusedInBotsConf() {
		assertRefused("circular | client-type:bot | bot", "circular");
	}

	/**
	 * It counts, and classification runs for every request including every include - so it would
	 * spend a visitor's budget once per fragment of their page.
	 */
	@Test
	public void ratePredicateIsRefusedInBotsConf() {
		assertRefused("metered | rate:10/1m | bot", "guard-rules.conf");
	}

	@Test
	public void anEmptyConditionIsRefusedRatherThanMatchingEverything() {
		assertRefused("catch-all |  | bot", "no condition");
	}

	@Test
	public void anAlternationInARegexIsNotMistakenForAFieldSeparator() {
		// the guard-rules splitter is shared precisely so this cannot regress in a second file
		BotClassifier classifier = classifier("headless | ua~(?i)(headlesschrome|phantomjs) | bot");

		assertEquals("headless", classifier.classify(request("PhantomJS/2.1", "203.0.113.9")).getName());
	}

	// ---- helpers -----------------------------------------------------------------------------

	private static void assertRefused(String line, String expectedMessageFragment) {
		try {
			BotRuleParser.parse(line);
			fail("expected '" + line + "' to be refused");
		} catch (GuardConfigException e) {
			assertTrue("unexpected message: " + e.getMessage(),
					e.getMessage().contains(expectedMessageFragment));
		}
	}

	/**
	 * A classifier over the given lines, with the two built-ins around them - i.e. what the loader
	 * would have produced from a bots.conf holding exactly these lines.
	 */
	private static BotClassifier classifier(String... lines) {
		List<BotRule> rules = new ArrayList<BotRule>();
		rules.add(BotClassifier.noUserAgentRule());

		for (String line : lines)
			rules.add(rule(line));

		rules.add(BotClassifier.defaultRule());
		return BotClassifier.of(rules);
	}

	private static BotRule rule(String line) {
		return BotRuleParser.parse(line);
	}

	private static RequestContext request(String userAgent, String remoteAddr) {
		return request(userAgent, remoteAddr, null);
	}

	private static RequestContext request(String userAgent, String remoteAddr, String forwardedFor) {
		return request(userAgent, remoteAddr, forwardedFor, (Cookie[]) null);
	}

	/**
	 * A stand-in servlet request. A dynamic proxy keeps this to a few lines - HttpServletRequest has
	 * far too many methods to implement by hand, and the core module has no mocking library.
	 */
	private static RequestContext request(final String userAgent, final String remoteAddr,
			final String forwardedFor, final Cookie... cookies) {

		RequestContext context = new RequestContext();
		context.setRequestURI("/en/index.htm");
		context.setRequestQueryString("");
		context.setRequestType(FCHeaders.COMPONENT_TOPLEVEL);

		context.setRequest((HttpServletRequest) Proxy.newProxyInstance(
				BotClassifierTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getHeader":
							return ("User-Agent".equals(args[0])) ? userAgent : null;
						case "getHeaders":
							return Collections.enumeration(
									("X-Forwarded-For".equals(args[0]) && null != forwardedFor)
										? Arrays.asList(forwardedFor)
										: Collections.<String>emptyList());
						case "getRemoteAddr":
							return remoteAddr;
						case "getMethod":
							return "GET";
						case "getCookies":
							return cookies;
						default:
							return null;
						}
					}
				}));

		return context;
	}

}
