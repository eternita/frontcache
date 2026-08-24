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

/**
 * Parses one line of guard-rules.conf into a {@link GuardRule}.
 *
 * Line format (see docs/frontcache-protection-tech-design.md):
 *
 *   &lt;name&gt; | &lt;predicate&gt; ; &lt;predicate&gt; ... | &lt;action&gt; [| dry-run]
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
		String[] parts = line.split("\\|");
		if (3 > parts.length)
			throw new GuardConfigException("expected '<name> | <condition> | <action>' but got '" + line + "'");

		String name = parts[0].trim();
		if (0 == name.length())
			throw new GuardConfigException("rule name is empty in '" + line + "'");

		List<GuardPredicate> predicates = parseCondition(parts[1], line);
		GuardAction action = parseAction(parts[2].trim(), line);

		boolean dryRun = false;
		if (3 < parts.length)
		{
			String flag = parts[3].trim();
			if ("dry-run".equalsIgnoreCase(flag))
				dryRun = true;
			else if (0 < flag.length())
				throw new GuardConfigException("unknown flag '" + flag + "' in '" + line + "' (only 'dry-run' is supported)");
		}

		return new GuardRule(name, predicates, action, dryRun);
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
			if ('/' == c || '?' == c || '#' == c)
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
