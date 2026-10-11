/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.frontcache.guard.ratelimit.RateLimitPredicate;

/**
 * Parses one line of guard-rules.conf into a {@link GuardRule}.
 *
 * Line format (see docs/frontcache-protection-tech-design.md):
 *
 *   &lt;name&gt; | &lt;predicate&gt; ; &lt;predicate&gt; ... | &lt;action&gt; [| dry-run]
 *
 * A {@code rate:} predicate is moved to the END of its condition here (see
 * {@link #parseCondition}); a rule may hold at most one.
 *
 * Actions:
 *   allow
 *   reject:&lt;status&gt; [body]
 *   redirect:&lt;301|302|303|307|308&gt; &lt;target&gt;
 *
 * Every rejection is reported as a {@link GuardConfigException} so the caller can skip that single
 * line and keep the rest of the file working.
 */
public class GuardRuleParser {

	private static final Set<Integer> REDIRECT_STATUSES = new HashSet<Integer>(java.util.Arrays.asList(301, 302, 303, 307, 308));

	private final Set<String> allowedRedirectHosts;

	/**
	 * @param allowedRedirectHosts hosts an absolute redirect target may point at. Relative targets
	 *                             are always allowed; anything else is refused at load time, so a
	 *                             stray config line cannot point the whole site at another host.
	 */
	public GuardRuleParser(Set<String> allowedRedirectHosts) {
		this.allowedRedirectHosts = (null != allowedRedirectHosts) ? allowedRedirectHosts : Collections.<String>emptySet();
	}

	public GuardRule parse(String line)
	{
		List<String> parts = splitFields(line);
		if (3 > parts.size())
			throw new GuardConfigException("expected '<name> | <condition> | <action>' but got '" + line + "'"
					+ (-1 < line.indexOf('|')
						? " - the '|' in it is inside a (group) or [class], so it did not separate fields;"
						  + " check for an unbalanced parenthesis or bracket"
						: ""));

		String name = parts.get(0).trim();
		if (0 == name.length())
			throw new GuardConfigException("rule name is empty in '" + line + "'");

		List<GuardPredicate> predicates = parseCondition(parts.get(1), line);
		bindRateLimit(predicates, name, line);
		GuardAction action = parseAction(parts.get(2).trim(), line);

		boolean dryRun = false;
		if (3 < parts.size())
		{
			String flag = parts.get(3).trim();
			if ("dry-run".equalsIgnoreCase(flag))
				dryRun = true;
			else if (0 < flag.length())
				throw new GuardConfigException("unknown flag '" + flag + "' in '" + line + "' (only 'dry-run' is supported)");
		}

		return new GuardRule(name, predicates, action, dryRun);
	}

	/**
	 * Splits a rule line into its {@code name | condition | action [| flag]} fields.
	 *
	 * Public because bots.conf uses the same line format and must split it the same way - see
	 * {@link org.frontcache.bots.BotRuleParser}. One splitter, so the alternation bug below cannot
	 * be re-introduced in the second file.
	 *
	 * The obvious {@code line.split("\\|")} is wrong, and was: {@code |} is also regex alternation, so
	 * a perfectly ordinary predicate like {@code uri~^/(recent|user)-contributions\.htm} got shredded
	 * at the alternation and the rule was refused at load time as "invalid regex ... Unclosed group".
	 * The rest of the file kept working, which is what made it invisible: on a production node this
	 * silently disabled 5 of 19 rules - and they were every anti-scraper redirect, i.e. exactly the
	 * rules whose absence costs nothing until a flood arrives.
	 *
	 * So a {@code |} separates fields only where a regex cannot be using it: outside any {@code (...)}
	 * group, outside any {@code [...]} character class, and not backslash-escaped. Fields come back
	 * byte-identical - nothing is unescaped - so {@code \|} still reads as a literal pipe to the regex
	 * engine, and a top-level alternation is written the way it already was, in parentheses.
	 */
	public static List<String> splitFields(String line)
	{
		List<String> fields = new ArrayList<String>();
		StringBuilder field = new StringBuilder();
		int groupDepth = 0;
		boolean inCharClass = false;

		for (int i = 0; i < line.length(); i++)
		{
			char c = line.charAt(i);

			// A backslash escapes whatever follows it, for the regex and for us alike: an escaped
			// pipe is not a separator, and an escaped bracket does not open a group or a class.
			if ('\\' == c && i + 1 < line.length())
			{
				field.append(c).append(line.charAt(++i));
				continue;
			}

			if (inCharClass)
			{
				if (']' == c)
					inCharClass = false;
			}
			else if ('[' == c)
				inCharClass = true;
			else if ('(' == c)
				groupDepth++;
			else if (')' == c && 0 < groupDepth)
				groupDepth--;
			else if ('|' == c && 0 == groupDepth)
			{
				fields.add(field.toString());
				field.setLength(0);
				continue;
			}

			field.append(c);
		}

		fields.add(field.toString());
		return fields;
	}

	private List<GuardPredicate> parseCondition(String condition, String line)
	{
		List<GuardPredicate> predicates = new ArrayList<GuardPredicate>();

		for (String expr : condition.split(";"))
		{
			if (0 == expr.trim().length())
				continue;

			predicates.add(GuardPredicateFactory.parse(expr));
		}

		if (predicates.isEmpty())
			throw new GuardConfigException("rule has no condition in '" + line + "' - a rule matching everything is never what is meant");

		return predicates;
	}

	/**
	 * Puts the rule's {@code rate:} predicate last and binds it to its counter.
	 *
	 * <b>Order matters here in a way it does not for any other predicate.</b> The condition is an
	 * AND evaluated with short-circuit, so moving the counting predicate to the end is
	 * semantics-preserving for the match - but it is the difference between metering only the
	 * requests the rest of the condition selected and metering every request that reaches the rule.
	 * Written as {@code rate:5/10s ; uri~^/search}, the rule would count the whole site's traffic and
	 * then report hits against a URI it never matched.
	 *
	 * Two rate predicates in one rule are refused rather than silently AND-ed: "over budget A and
	 * over budget B" always consumes both budgets, which is not what anyone means by it.
	 */
	private void bindRateLimit(List<GuardPredicate> predicates, String ruleName, String line)
	{
		RateLimitPredicate rateLimit = null;
		int index = -1;

		for (int i = 0; i < predicates.size(); i++)
		{
			if (!(predicates.get(i) instanceof RateLimitPredicate))
				continue;

			if (null != rateLimit)
				throw new GuardConfigException("rule '" + ruleName + "' has more than one rate predicate in '" + line
						+ "' - split it into two rules, sharing a counter with bucket=<name> if that is what is meant");

			rateLimit = (RateLimitPredicate) predicates.get(i);
			index = i;
		}

		if (null == rateLimit)
			return;

		predicates.remove(index);
		predicates.add(rateLimit.bind(ruleName));
	}

	private GuardAction parseAction(String action, String line)
	{
		if ("allow".equalsIgnoreCase(action))
			return GuardAction.allow();

		int colon = action.indexOf(':');
		if (-1 == colon)
			throw new GuardConfigException("unknown action '" + action + "' in '" + line + "'");

		String verb = action.substring(0, colon).trim();
		String rest = action.substring(colon + 1).trim();

		int space = rest.indexOf(' ');
		String statusStr = (-1 < space) ? rest.substring(0, space).trim() : rest;
		String argument = (-1 < space) ? rest.substring(space + 1).trim() : "";

		int status;
		try {
			status = Integer.parseInt(statusStr);
		} catch (NumberFormatException e) {
			throw new GuardConfigException("'" + statusStr + "' is not an HTTP status in '" + line + "'");
		}

		if ("reject".equalsIgnoreCase(verb))
		{
			if (400 > status || 599 < status)
				throw new GuardConfigException("reject status must be 4xx/5xx, got " + status + " in '" + line + "'");

			String body = (0 < argument.length()) ? argument : status + " Rejected";
			return GuardAction.reject(status, body);
		}

		if ("redirect".equalsIgnoreCase(verb))
		{
			if (!REDIRECT_STATUSES.contains(Integer.valueOf(status)))
				throw new GuardConfigException("redirect status must be one of 301/302/303/307/308, got " + status + " in '" + line + "'");

			if (0 == argument.length())
				throw new GuardConfigException("redirect needs a target in '" + line + "'");

			checkRedirectTarget(argument, line);
			return GuardAction.redirect(status, argument);
		}

		throw new GuardConfigException("unknown action '" + verb + "' in '" + line + "'");
	}

	/**
	 * Open-redirect protection: an absolute target must point at a host we published, otherwise the
	 * rule is refused. Relative targets (starting with '/') stay on the current host and are always
	 * fine.
	 */
	private void checkRedirectTarget(String target, String line)
	{
		if (target.startsWith("/"))
			return;

		String lower = target.toLowerCase();
		if (!lower.startsWith("http://") && !lower.startsWith("https://"))
			throw new GuardConfigException("redirect target must be absolute (http/https) or start with '/' in '" + line + "'");

		int schemeEnd = target.indexOf("://") + 3;
		int hostEnd = target.length();
		for (int i = schemeEnd; i < target.length(); i++)
		{
			char c = target.charAt(i);
			// '${' too: no hostname contains it, and 'https://www.example.com${uri}${query}' - the
			// target that sends a request to the same path on another host - was otherwise read as a
			// host named 'www.example.com${uri}${query}' and refused. Whatever the placeholders expand
			// to, GuardRuleEngine.sanitizeTarget re-checks the host of the expanded target.
			if ('/' == c || '?' == c || '#' == c || ('$' == c && i + 1 < target.length() && '{' == target.charAt(i + 1)))
			{
				hostEnd = i;
				break;
			}
		}

		String host = target.substring(schemeEnd, hostEnd);
		int portIdx = host.lastIndexOf(':');
		if (-1 < portIdx && -1 == host.indexOf(']')) // strip :port, keep IPv6 literals intact
			host = host.substring(0, portIdx);

		if (0 == host.length())
			throw new GuardConfigException("redirect target has no host in '" + line + "'");

		if (!allowedRedirectHosts.contains(host.toLowerCase()))
			throw new GuardConfigException("redirect target host '" + host + "' is not allowed in '" + line
					+ "' - add it to front-cache.guard-rules.allowed-redirect-hosts");
	}

}
