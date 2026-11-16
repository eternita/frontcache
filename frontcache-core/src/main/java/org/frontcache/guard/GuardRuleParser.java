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
 * Line format (see docs/guard-getting-started.md):
 *
 *   &lt;name&gt; | &lt;predicate&gt; ; &lt;predicate&gt; ... | &lt;action&gt; [| dry-run] [| on-error=allow] [| trap]
 *
 * A {@code rate:} predicate is moved to the END of its condition here (see
 * {@link #parseCondition}); a rule may hold at most one.
 *
 * Actions:
 *   allow
 *   reject:&lt;status&gt; [body]
 *   redirect:&lt;301|302|303|307|308&gt; &lt;target&gt;
 *   challenge &lt;provider&gt; [else &lt;reject:...|redirect:...&gt;]
 *
 * Every rejection is reported as a {@link GuardConfigException} so the caller can skip that single
 * line and keep the rest of the file working.
 */
public class GuardRuleParser {

	private static final String CHALLENGE = "challenge";

	/**
	 * The flag an {@code allow} rule naming a plugin's predicate ({@code ext:}) must carry: its author has accepted that
	 * when the predicate has no answer - the plugin threw, is over its budget, or is absent - the rule does not match,
	 * so the exemption does not apply to that request.
	 */
	public static final String ON_ERROR_ALLOW = "on-error=allow";

	/**
	 * The flag of a honeypot rule - a path no visitor is ever sent to, whose fetch identifies the client: the rule
	 * matches a client's request only, never an include, and its log line names the address this node resolved
	 * ({@link GuardRule#logReason}). Refused on an {@code allow} rule, which records nothing.
	 */
	public static final String TRAP = "trap";

	/** A provider's name - as a plugin's manifest names it in {@code challenges}. */
	private static final java.util.regex.Pattern PROVIDER = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,30}");

	private static final Set<Integer> REDIRECT_STATUSES = new HashSet<Integer>(java.util.Arrays.asList(301, 302, 303, 307, 308));

	private final Set<String> allowedRedirectHosts;

	/**
	 * @param allowedRedirectHosts hosts an absolute redirect target may point at. Relative targets
	 *                             are always allowed; anything else is refused at load time, so a
	 *                             stray config line cannot point the whole site at another host.
	 */
	public GuardRuleParser(Set<String> allowedRedirectHosts) {
		this(allowedRedirectHosts, null);
	}

	/**
	 * @param visitorTypes what {@code client-type:} is checked against; null - the node's loaded vocabulary, read
	 *                     when each line is parsed. A validator with no node passes the bots.conf declarations.
	 */
	public GuardRuleParser(Set<String> allowedRedirectHosts, org.frontcache.bots.VisitorTypes visitorTypes) {
		this.allowedRedirectHosts = (null != allowedRedirectHosts) ? allowedRedirectHosts : Collections.<String>emptySet();
		this.visitorTypes = visitorTypes;
	}

	private final org.frontcache.bots.VisitorTypes visitorTypes;

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
		boolean onErrorAllow = false;
		boolean trap = false;
		for (int i = 3; i < parts.size(); i++)
		{
			String flag = parts.get(i).trim();
			if ("dry-run".equalsIgnoreCase(flag) && !dryRun)
				dryRun = true;
			else if (ON_ERROR_ALLOW.equalsIgnoreCase(flag.replace(" ", "")) && !onErrorAllow)
				onErrorAllow = true;
			else if (TRAP.equalsIgnoreCase(flag) && !trap)
				trap = true;
			else if (0 < flag.length())
				throw new GuardConfigException("unknown or repeated flag '" + flag + "' in '" + line + "' (the flags are 'dry-run', '"
						+ ON_ERROR_ALLOW + "' and '" + TRAP + "')");
		}

		checkPluginPredicates(predicates, action, onErrorAllow, line);
		if (trap && GuardAction.Type.ALLOW == action.getType())
			throw new GuardConfigException("'" + TRAP + "' is for a rule that answers the request - reject, redirect or"
					+ " challenge, or a dry run of one - not an allow rule, which records nothing, in '" + line + "'");

		return new GuardRule(name, predicates, action, dryRun, onErrorAllow, trap);
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

			predicates.add(GuardPredicateFactory.parse(expr, GuardPredicateFactory.Scope.GUARD,
					(null != visitorTypes) ? visitorTypes : org.frontcache.bots.VisitorTypes.active()));
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

	/**
	 * A rule whose predicate a plugin answers does not match when the plugin has no answer. For a reject, redirect or
	 * challenge that is fail-open; for an {@code allow} rule it is an exemption that stops applying - a lockout risk the
	 * rule must state in {@value #ON_ERROR_ALLOW}, which means nothing on any other rule and is refused there.
	 */
	private static void checkPluginPredicates(List<GuardPredicate> predicates, GuardAction action, boolean onErrorAllow,
			String line)
	{
		boolean plugin = false;
		for (GuardPredicate predicate : predicates)
			plugin |= predicate.isPluginPredicate();
		boolean allow = GuardAction.Type.ALLOW == action.getType();

		if (allow && plugin && !onErrorAllow)
			throw new GuardConfigException("an allow rule naming a plugin's ext: predicate needs '| " + ON_ERROR_ALLOW + "' in '"
					+ line + "' - when the plugin has no answer (it threw, is over its budget or is absent) the rule does"
					+ " not match, so the exemption does not apply; the flag says that is accepted");
		if (onErrorAllow && !(allow && plugin))
			throw new GuardConfigException("'" + ON_ERROR_ALLOW + "' is only for an allow rule naming an ext: predicate, in '"
					+ line + "'");
	}

	private GuardAction parseAction(String action, String line)
	{
		if ("allow".equalsIgnoreCase(action))
			return GuardAction.allow();

		if (action.regionMatches(true, 0, CHALLENGE, 0, CHALLENGE.length())
				&& (action.length() == CHALLENGE.length() || Character.isWhitespace(action.charAt(CHALLENGE.length()))))
			return parseChallenge(action.substring(CHALLENGE.length()).trim(), line);

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
	 * {@code challenge <provider> [else <action>]}. The provider is not looked up here: plugins start after the
	 * rules load, a rule must load the same on a node without the plugin (where it acts as a dry run), and the
	 * console and the portal validate files with no plugin at all. The {@code else} action is a reject or a
	 * redirect - never {@code allow} (a missing provider must not become an exemption) or another challenge.
	 */
	private GuardAction parseChallenge(String rest, String line)
	{
		if (0 == rest.length())
			throw new GuardConfigException("challenge needs a provider in '" + line + "' - e.g. 'challenge pow'");

		String provider = rest;
		GuardAction otherwise = null;

		int space = indexOfWhitespace(rest);
		if (-1 < space)
		{
			provider = rest.substring(0, space);
			String tail = rest.substring(space).trim();
			if (!tail.regionMatches(true, 0, "else", 0, 4) || (4 < tail.length() && !Character.isWhitespace(tail.charAt(4))))
				throw new GuardConfigException("expected 'challenge <provider> [else <action>]' but got 'challenge " + rest
						+ "' in '" + line + "'");
			String elseAction = tail.substring(4).trim();
			if (0 == elseAction.length())
				throw new GuardConfigException("'else' needs an action in '" + line + "' - reject:<status> or redirect:<status> <target>");
			otherwise = parseAction(elseAction, line);
			if (GuardAction.Type.REJECT != otherwise.getType() && GuardAction.Type.REDIRECT != otherwise.getType())
				throw new GuardConfigException("a challenge's 'else' must be reject or redirect, not '" + elseAction + "', in '" + line
						+ "' - with the provider absent the rule acts as a dry run, which is what leaving 'else' out says");
		}

		if (!PROVIDER.matcher(provider).matches())
			throw new GuardConfigException("'" + provider + "' is not a challenge provider name in '" + line
					+ "' - lower case letters, digits and '-', as a plugin's manifest names it in 'challenges'");

		return GuardAction.challenge(provider, otherwise);
	}

	private static int indexOfWhitespace(String value)
	{
		for (int i = 0; i < value.length(); i++)
			if (Character.isWhitespace(value.charAt(i)))
				return i;
		return -1;
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
