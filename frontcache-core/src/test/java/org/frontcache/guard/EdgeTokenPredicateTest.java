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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.frontcache.FCConfig;
import org.frontcache.core.EdgeToken;
import org.frontcache.core.RequestContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The {@code edge-token} guard predicate - "this request came from a Frontcache of the same chain".
 * See {@link EdgeToken} and docs/direct-access-restriction-proposal.md.
 *
 * The load-time refusals are the part worth pinning. On a node with no token {@code !edge-token}
 * matches every request, and on a second layer a rule that redirects every request to the public
 * host is a redirect loop through the edge - so such a rule must never load, and the console's Save
 * (which validates through the same parser) must never write one.
 */
public class EdgeTokenPredicateTest {

	private static final String TOKEN = "e2e-0123456789abcdef0123456789abcdef";

	private final String originalToken = FCConfig.getProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");
	private final String originalAllowedHosts = FCConfig.getProperty("front-cache.guard-rules.allowed-redirect-hosts", "");

	@Before
	public void setUp()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, TOKEN);
		FCConfig.setProperty("front-cache.guard-rules.allowed-redirect-hosts", "www.example-site.com");
	}

	@After
	public void tearDown()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, originalToken);
		FCConfig.setProperty("front-cache.guard-rules.allowed-redirect-hosts", originalAllowedHosts);
	}

	private static RequestContext context(boolean edgeTokenValid)
	{
		RequestContext context = GuardRuleEngine.probeContext("https://origin.example-site.com/en/search.htm");
		context.setEdgeTokenValid(edgeTokenValid);
		return context;
	}

	// ---- what it reads -----------------------------------------------------------------------

	@Test
	public void itReadsWhatInitDecided()
	{
		GuardPredicate predicate = GuardPredicateFactory.parse("edge-token");

		assertTrue(predicate.test(context(true)));
		assertFalse(predicate.test(context(false)));
		assertEquals("edge-token", predicate.describe());
	}

	@Test
	public void negatedItMeansReachedDirectly()
	{
		GuardPredicate predicate = GuardPredicateFactory.parse("!edge-token");

		assertFalse(predicate.test(context(true)));
		assertTrue(predicate.test(context(false)));
		assertEquals("!edge-token", predicate.describe());
	}

	@Test
	public void aContextNoOneDecidedForCountsAsNoToken()
	{
		RequestContext undecided = GuardRuleEngine.probeContext("https://origin.example-site.com/");

		assertFalse(GuardPredicateFactory.parse("edge-token").test(undecided));
	}

	// ---- refused at load ---------------------------------------------------------------------

	@Test
	public void itIsRefusedOnANodeWithNoToken()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");

		try {
			GuardPredicateFactory.parse("!edge-token");
			fail("'!edge-token' on a node with no token would match every request");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(EdgeToken.EDGE_TOKEN_PROPERTY));
		}
	}

	@Test
	public void itIsRefusedInBotsConf()
	{
		try {
			GuardPredicateFactory.parse("edge-token", GuardPredicateFactory.Scope.BOTS);
			fail("the token identifies the hop, not the visitor");
		} catch (GuardConfigException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("bots.conf"));
		}
	}

	@Test
	public void itTakesNoArgument()
	{
		for (String written : new String[] { "edge-token:yes", "edge-token~abc" })
		{
			try {
				GuardPredicateFactory.parse(written);
				fail("'" + written + "' should be refused");
			} catch (GuardConfigException e) {
				assertTrue(e.getMessage(), e.getMessage().contains("takes no argument"));
			}
		}
	}

	// ---- in a whole rule, through the engine's validation ------------------------------------

	/**
	 * The rule docs/direct-access-restriction-proposal.md recommends for a second layer. method:GET is
	 * what keeps the load-time loop check satisfied - a probe has no servlet request, so it is never a
	 * GET - and it is what a 301 wants anyway, since a browser re-sends a redirected POST as a GET.
	 */
	@Test
	public void theRecommendedRulesValidate()
	{
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		List<String> errors = engine.validate(
				"direct-get   | !edge-token ; method:GET | redirect:301 https://www.example-site.com${uri}${query}\n"
				+ "direct-other | !edge-token              | reject:403 Use https://www.example-site.com\n");

		assertTrue("expected no errors, got " + errors, errors.isEmpty());
	}

	/**
	 * Without method:GET the loop check cannot tell that the redirected request will come back through
	 * the edge, with a token - from where it stands it is a rule that redirects to a URL its own
	 * condition matches. Pinned so the recommendation and the check stay in step.
	 */
	@Test
	public void aBareRedirectIsReportedAsALoop()
	{
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		List<String> errors = engine.validate("direct | !edge-token | redirect:301 https://www.example-site.com${uri}${query}\n");

		assertEquals(errors.toString(), 1, errors.size());
		assertTrue(errors.get(0), errors.get(0).contains("redirect loop"));
	}

	@Test
	public void validationRefusesTheRuleOnANodeWithNoToken()
	{
		FCConfig.setProperty(EdgeToken.EDGE_TOKEN_PROPERTY, "");
		GuardRuleEngine engine = new GuardRuleEngine(4096);

		List<String> errors = engine.validate("direct-other | !edge-token | reject:403\n");

		assertEquals("the console's Save must refuse what the node would refuse to load: " + errors, 1, errors.size());
		assertTrue(errors.get(0), errors.get(0).startsWith("line 1:"));
	}

}
