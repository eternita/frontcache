/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.bots;

import java.util.ArrayList;
import java.util.List;

import org.frontcache.core.FCHeaders;
import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardPredicate;
import org.frontcache.guard.GuardPredicateFactory;
import org.frontcache.guard.GuardRuleParser;

/**
 * Parses one line of conf/bots.conf.
 *
 * Two line shapes, and the absence of a top-level {@code |} is what tells them apart:
 *
 * <pre>
 *   Googlebot                                          bare User-Agent keyword (pre-2.10 format)
 *   fake-googlebot | ua~(?i)googlebot ; !client-ip:@googlebot-ips.txt | bot | dry-run
 * </pre>
 *
 * The rule form is the guard-rules.conf grammar with an action field that holds a client type
 * instead of an action - so the field splitter ({@link GuardRuleParser#splitFields}) and the
 * predicate factory are the guard ones, in {@link GuardPredicateFactory.Scope#BOTS} scope. Sharing
 * them is the point: an operator writes one syntax, and a fix to either reaches both files.
 *
 * Every rejection is a {@link GuardConfigException} so the caller can skip that single line and
 * keep the rest of the file working.
 */
public class BotRuleParser {

	private BotRuleParser() {
	}

	/**
	 * @param line one non-blank, non-comment line
	 * @return the compiled rule
	 * @throws GuardConfigException when the line cannot be used
	 */
	public static BotRule parse(String line)
	{
		List<String> parts = GuardRuleParser.splitFields(line);

		if (2 > parts.size())
			return keywordRule(line.trim());

		if (3 > parts.size())
			throw new GuardConfigException("expected '<name> | <condition> | <bot|guest>' but got '" + line
					+ "' - a line with no '|' at all is read as a plain User-Agent keyword instead");

		String name = parts.get(0).trim();
		if (0 == name.length())
			throw new GuardConfigException("rule name is empty in '" + line + "'");

		List<GuardPredicate> predicates = parseCondition(parts.get(1), line);
		String clientType = parseClientType(parts.get(2).trim(), line);

		boolean dryRun = false;
		if (3 < parts.size())
		{
			String flag = parts.get(3).trim();
			if ("dry-run".equalsIgnoreCase(flag))
				dryRun = true;
			else if (0 < flag.length())
				throw new GuardConfigException("unknown flag '" + flag + "' in '" + line + "' (only 'dry-run' is supported)");
		}

		return new BotRule(name, predicates, clientType, dryRun);
	}

	/**
	 * A bare line: the pre-2.10 format, one User-Agent substring per line, still supported and still
	 * how most of a bots.conf is written.
	 *
	 * The keyword becomes the rule name, so the console shows which keyword matched and its hit
	 * counter says whether it is earning its place in the file.
	 */
	public static BotRule keywordRule(String keyword)
	{
		if (null == keyword || 0 == keyword.trim().length())
			throw new GuardConfigException("empty bot keyword");

		String trimmed = keyword.trim();
		GuardPredicate predicate = GuardPredicateFactory.parse("ua-contains:" + trimmed, GuardPredicateFactory.Scope.BOTS);

		List<GuardPredicate> predicates = new ArrayList<GuardPredicate>(1);
		predicates.add(predicate);

		return new BotRule(trimmed, predicates, FCHeaders.REQUEST_CLIENT_TYPE_BOT, false);
	}

	private static List<GuardPredicate> parseCondition(String condition, String line)
	{
		List<GuardPredicate> predicates = new ArrayList<GuardPredicate>();

		for (String expr : condition.split(";"))
		{
			if (0 == expr.trim().length())
				continue;

			predicates.add(GuardPredicateFactory.parse(expr, GuardPredicateFactory.Scope.BOTS));
		}

		if (predicates.isEmpty())
			throw new GuardConfigException("rule has no condition in '" + line
					+ "' - a rule matching everything would shadow every rule below it");

		return predicates;
	}

	/**
	 * The verdict field. Exactly two values parse, and that is a storage contract rather than a
	 * stylistic choice: a WebResponse's expireTimeMap is stored in the L2 index keyed by client
	 * type, so a third value would leave every entry already on disk without a key for it - which
	 * FCUtils.isWebComponentCacheableForClientType answers by logging an error and never caching
	 * that URL again. See docs/dynamic-bots-classification-proposal.md section 3.
	 */
	private static String parseClientType(String value, String line)
	{
		if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equalsIgnoreCase(value))
			return FCHeaders.REQUEST_CLIENT_TYPE_BOT;

		if (FCHeaders.REQUEST_CLIENT_TYPE_GUEST.equalsIgnoreCase(value))
			return FCHeaders.REQUEST_CLIENT_TYPE_GUEST;

		throw new GuardConfigException("'" + value + "' is not a client type in '" + line + "' - expected '"
				+ FCHeaders.REQUEST_CLIENT_TYPE_BOT + "' or '" + FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "'"
				+ ("browser".equalsIgnoreCase(value)
					? " ('browser' was renamed to '" + FCHeaders.REQUEST_CLIENT_TYPE_GUEST + "' in 2.9.0)"
					: ""));
	}

}
