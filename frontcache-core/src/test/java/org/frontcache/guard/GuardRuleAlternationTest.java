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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.frontcache.core.RequestContext;
import org.junit.Test;

/**
 * A guard rule may use regex alternation, because {@code |} is both the field separator and the most
 * common regex operator there is.
 *
 * {@code GuardRuleParser} used to split the line with {@code line.split("\\|")}, so
 * {@code uri~^/(recent|user)-contributions\.htm} arrived at {@code Pattern.compile} as
 * {@code uri~^/(recent} and the rule was refused with "Unclosed group". The parser is deliberately
 * per-line tolerant - one bad line is skipped and the file keeps working - so nothing failed loudly:
 * on a production node this quietly disabled 5 of 19 rules, and they were every one of the
 * anti-scraper redirects. A disabled protection rule costs nothing measurable until the traffic it
 * was written for shows up.
 *
 * These cases are the real config lines. The first four fail against the old splitter.
 */
public class GuardRuleAlternationTest {

	private static final Set<String> ALLOWED_HOSTS = new HashSet<String>(Arrays.asList("www.example-site.com"));

	private final GuardRuleParser parser = new GuardRuleParser(ALLOWED_HOSTS);

	private static RequestContext context(String url) {
		return GuardRuleEngine.probeContext(url);
	}

	/** Verbatim from the node's guard-rules.conf, minus the cookie predicate this harness cannot set. */
	@Test
	public void alternationInsideAGroupIsNotAFieldSeparator() {

		GuardRule rule = parser.parse("anon-contrib | uri~^(/[a-z]{2})?/ccc/(recent|user)-contributions\\.htm"
				+ " ; query~[?&]pagingPage_cvl=[0-9]{2,} | redirect:302 https://www.example-site.com/login.htm");

		assertEquals("anon-contrib", rule.getName());
		assertTrue("both branches of the alternation must match",
				rule.matches(context("https://www.example-site.com/de/ccc/recent-contributions.htm?pagingPage_cvl=40")));
		assertTrue(rule.matches(context("https://www.example-site.com/de/ccc/user-contributions.htm?pagingPage_cvl=40")));
		assertEquals(302, rule.getAction().getStatus());
	}

	/** Three branches, and a branch that itself contains an escaped dot. */
	@Test
	public void aMultiBranchAlternationSurvives() {

		GuardRule rule = parser.parse("anon-coin-prices | uri~^(/[a-z]{2})?/(prices-|buy-sell-|coin_details\\.htm)"
				+ " ; query~[?&]pagingPage_est_tx=[0-9]{2,} | reject:403");

		assertTrue(rule.matches(context("https://www.example-site.com/prices-x.htm?pagingPage_est_tx=12")));
		assertTrue(rule.matches(context("https://www.example-site.com/en/buy-sell-y.htm?pagingPage_est_tx=12")));
		assertTrue(rule.matches(context("https://www.example-site.com/en/coin_details.htm?pagingPage_est_tx=12")));
	}

	/** A group holding a literal {@code /} alternated with an escaped dot - the company rule. */
	@Test
	public void anAlternationOfPunctuationSurvives() {

		GuardRule rule = parser.parse("anon-company | uri~^(/[a-z]{2})?/company(/|\\.htm)"
				+ " ; query~[?&]pagingPage_ci=[0-9]{2,} | reject:403");

		assertTrue(rule.matches(context("https://www.example-site.com/company/?pagingPage_ci=30")));
		assertTrue(rule.matches(context("https://www.example-site.com/de/company.htm?pagingPage_ci=30")));
	}

	/** The trailing dry-run flag is still found when the condition contains alternation. */
	@Test
	public void theDryRunFlagIsStillTheLastFieldNotTheFirstAlternationBranch() {

		GuardRule rule = parser.parse("anon-dates | uri~^/(coins-by-dates|items_by_dates)\\.htm | reject:403 | dry-run");

		assertTrue(rule.isDryRun());
		assertTrue(rule.matches(context("https://www.example-site.com/items_by_dates.htm")));
	}

	/** A {@code |} inside a character class is a literal pipe, not a separator either. */
	@Test
	public void aPipeInsideACharacterClassIsNotAFieldSeparator() {

		List<String> fields = GuardRuleParser.splitFields("n | uri~^/a[|b]c | reject:403");

		assertEquals(3, fields.size());
		assertEquals(" uri~^/a[|b]c ", fields.get(1));
	}

	/** An escaped pipe stays escaped - fields are handed on byte-identical, never unescaped. */
	@Test
	public void anEscapedPipeIsNeitherASeparatorNorRewritten() {

		List<String> fields = GuardRuleParser.splitFields("n | uri~^/a\\|b | reject:403");

		assertEquals(3, fields.size());
		assertEquals(" uri~^/a\\|b ", fields.get(1));
	}

	/** Nested groups: the depth counter, not a boolean, is what makes this work. */
	@Test
	public void nestedGroupsAreCountedNotJustDetected() {

		List<String> fields = GuardRuleParser.splitFields("n | uri~^/((a|b)|(c|d))/x | reject:403");

		assertEquals(3, fields.size());
		assertEquals(" uri~^/((a|b)|(c|d))/x ", fields.get(1));
	}

	/** An ordinary line with no regex metacharacters splits exactly as it always did. */
	@Test
	public void aPlainLineIsUnaffected() {

		GuardRule rule = parser.parse("plain | uri~^/admin | reject:403 nope");

		assertEquals("plain", rule.getName());
		assertTrue(rule.matches(context("https://www.example-site.com/admin")));
	}

	/**
	 * An unbalanced parenthesis swallows the rest of the line, so the rule is still refused - but the
	 * message has to say why, or the next person sees "expected name | condition | action" on a line
	 * that visibly has all three.
	 */
	@Test
	public void anUnbalancedParenthesisIsRefusedWithAnExplanation() {

		try {
			parser.parse("broken | uri~^/(a|b | reject:403");
			fail("expected GuardConfigException");
		} catch (GuardConfigException expected) {
			assertTrue("message must point at the bracket, got: " + expected.getMessage(),
					expected.getMessage().contains("unbalanced parenthesis or bracket"));
		}
	}
}
