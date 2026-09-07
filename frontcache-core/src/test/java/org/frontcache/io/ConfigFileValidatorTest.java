/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * The dry parse that runs before {@code set-config} writes anything.
 *
 * Two things are asserted throughout: that a good file passes untouched, and that a bad line is
 * reported <b>with its line number</b>. The number is the whole value of this over the log line the
 * loader would have produced - the operator is editing a textarea, and "it is broken somewhere" is
 * not actionable.
 */
public class ConfigFileValidatorTest {

	private static void assertValid(ConfigFile configFile, String content)
	{
		List<String> errors = ConfigFileValidator.validate(configFile, content);
		assertEquals("expected no errors, got " + errors, 0, errors.size());
	}

	private static void assertReportsLine(ConfigFile configFile, String content, int lineNumber)
	{
		List<String> errors = ConfigFileValidator.validate(configFile, content);
		assertEquals("expected exactly one error, got " + errors, 1, errors.size());
		assertTrue("expected the message to name line " + lineNumber + ", got: " + errors.get(0),
				errors.get(0).startsWith("line " + lineNumber + ":"));
	}

	// ---- bots.conf ---------------------------------------------------------------------------

	@Test
	public void aRealBotsFilePasses()
	{
		assertValid(ConfigFile.BOTS,
				"# comment\n"
				+ "\n"
				+ "headless-e2e    | ua~(?i)(headlesschrome|phantomjs) | bot\n"
				+ "localhost-guest | client-ip:127.0.0.1,::1           | guest\n"
				+ "never-fires     | ua~(?i)dry                        | bot | dry-run\n"
				+ "Googlebot\n"
				+ "Yahoo! Slurp");
	}

	/**
	 * The verdict vocabulary is {@code bot}|{@code guest} and cannot grow - it keys
	 * {@code WebResponse.expireTimeMap} in every L2 entry already on disk. A typo here would otherwise
	 * be written to the node and silently leave that rule dead.
	 */
	@Test
	public void anUnknownClientTypeIsRefused()
	{
		assertReportsLine(ConfigFile.BOTS, "# c\nbad-verdict | ua~x | browser\n", 2);
	}

	@Test
	public void aRuleWithTooFewFieldsIsRefused()
	{
		assertReportsLine(ConfigFile.BOTS, "half-a-rule | ua~x\n", 1);
	}

	/**
	 * A bare keyword is not a broken rule - it is the pre-2.9.0 format and most of a bots.conf is
	 * still written that way. Getting this wrong would refuse every file in the field.
	 */
	@Test
	public void bareKeywordsArePassedNotFlagged()
	{
		assertValid(ConfigFile.BOTS, "Googlebot\nbingbot\nMail.RU_Bot\n");
	}

	// ---- dynamic-urls.conf -------------------------------------------------------------------

	@Test
	public void dynamicUrlPatternsPass()
	{
		assertValid(ConfigFile.DYNAMIC_URLS, "# never cache\n(.)*/cart/(.)*\n(.)*login(.)*\n");
	}

	/**
	 * The loader logs a bad pattern at info and skips it, and this deliberately does NOT: a skipped
	 * pattern means a URL that must never be cached becomes cacheable, which is the most expensive
	 * silent outcome in this whole set of files.
	 */
	@Test
	public void aBrokenRegexIsRefusedWithItsLineNumber()
	{
		assertReportsLine(ConfigFile.DYNAMIC_URLS, "(.)*/ok/(.)*\n(unclosed\n", 2);
	}

	// ---- fallbacks.conf ----------------------------------------------------------------------

	@Test
	public void twoAndThreeFieldFallbacksPass()
	{
		assertValid(ConfigFile.FALLBACKS,
				"# comment\n"
				+ "http://localhost:9080/a.jsp fallback1.txt\n"
				+ "http://localhost:9080/b.jsp fallback2.txt http://localhost:8080/b_init.jsp\n");
	}

	@Test
	public void aFallbackWithOneFieldIsRefused()
	{
		assertReportsLine(ConfigFile.FALLBACKS, "http://localhost:9080/a.jsp\n", 1);
	}

	@Test
	public void aFallbackWithFourFieldsIsRefused()
	{
		assertReportsLine(ConfigFile.FALLBACKS, "a b c d\n", 1);
	}

	// ---- resilience.properties ---------------------------------------------------------------

	@Test
	public void aResiliencePropertiesFilePasses()
	{
		assertValid(ConfigFile.RESILIENCE,
				"resilience.command.Cache-Miss.execution.isolation.thread.timeoutInMilliseconds=10000\n"
				+ "resilience.threadpool.OriginPool.coreSize=50\n");
	}

	/**
	 * Unknown keys must NOT be an error. The file legitimately carries the legacy {@code hystrix.*}
	 * prefixes, keys for commands this node never runs, and keys a newer node would understand -
	 * refusing them would refuse the exact files COMMAND_KEY_INHERITANCE exists to keep working.
	 */
	@Test
	public void unknownAndLegacyKeysAreAccepted()
	{
		assertValid(ConfigFile.RESILIENCE,
				"hystrix.command.Input-Requests.execution.isolation.thread.timeoutInMilliseconds=1000\n"
				+ "hystrix.threadpool.OriginHitsPool.coreSize=50\n"
				+ "some.key.nobody.knows=1\n");
	}

	@Test
	public void aMalformedEscapeIsRefused()
	{
		List<String> errors = ConfigFileValidator.validate(ConfigFile.RESILIENCE, "key=value\\uZZZZ\n");
		assertEquals("expected one error, got " + errors, 1, errors.size());
	}

	// ---- guard-rules.conf --------------------------------------------------------------------

	/**
	 * Guard rules are validated through the LIVE engine, so the parser and the redirect-host allowlist
	 * are the ones the node will apply. With no engine in this JVM there is nothing to validate
	 * against and no rules are running either, so the file is accepted rather than rejected - a node
	 * that has not served a request is not a node whose config can be mismatched.
	 *
	 * The parsing itself is covered where the engine is: GuardRuleEngineTest.
	 */
	@Test
	public void guardRulesAreAcceptedWhenNoEngineIsRunning()
	{
		assertValid(ConfigFile.GUARD_RULES, "this line would never parse as a rule\n");
	}
}
