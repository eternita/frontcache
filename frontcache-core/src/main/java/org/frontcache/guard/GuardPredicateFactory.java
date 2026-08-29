/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.ratelimit.RateLimitPredicate;
import org.frontcache.guard.ratelimit.RateLimitSpec;

/**
 * Parses a single predicate expression from guard-rules.conf into a {@link GuardPredicate}.
 *
 * Grammar (see docs/frontcache-protection-tech-design.md):
 *
 *   [!] host:ip
 *   [!] host~&lt;regex&gt;
 *   [!] uri~&lt;regex&gt;
 *   [!] query~&lt;regex&gt;
 *   [!] url~&lt;regex&gt;
 *   [!] method:&lt;GET|POST|...&gt;
 *   [!] client-type:&lt;bot|browser&gt;
 *   [!] cookie:&lt;name&gt;
 *   [!] header:&lt;name&gt;[~&lt;regex&gt;]
 *   [!] request-type:&lt;toplevel|include|include-async&gt;
 *       rate:&lt;limit&gt;/&lt;window&gt; [bucket=&lt;name&gt;] [scope=&lt;toplevel|all&gt;]   (never negated)
 *
 * Regexes are compiled once, here, and matched with find() semantics - the same behaviour as
 * dynamic-urls.conf patterns.
 */
public class GuardPredicateFactory {

	/**
	 * Query strings are attacker-controlled and unbounded; a pathological one can make an
	 * innocent-looking regex backtrack for a long time on the request thread. Match against at most
	 * this many characters - anything longer is already handled by the uri-too-long guard.
	 */
	static final int MAX_MATCH_LENGTH = 8192;

	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

	private GuardPredicateFactory() {
	}

	/**
	 * @param expression single predicate as written in the config, e.g. "!cookie:hruc"
	 * @return compiled predicate
	 * @throws GuardConfigException on unknown predicate type or invalid regex
	 */
	public static GuardPredicate parse(String expression)
	{
		String expr = (null != expression) ? expression.trim() : "";
		if (0 == expr.length())
			throw new GuardConfigException("empty predicate");

		boolean negated = false;
		if (expr.startsWith("!"))
		{
			negated = true;
			expr = expr.substring(1).trim();
		}

		GuardPredicate predicate = parsePositive(expr);

		if (!negated)
			return predicate;

		// A counting predicate cannot be negated: "!rate:" would read as admission control, would
		// count on the ALLOWED path, and would then let evaluation continue past it.
		if (predicate instanceof RateLimitPredicate)
			throw new GuardConfigException("rate predicate cannot be negated in '!" + expr
					+ "' - write the rule around the over-limit case and use an 'allow' rule above it for exemptions");

		final GuardPredicate delegate = predicate;
		final String described = "!" + delegate.describe();
		return new GuardPredicate() {
			@Override
			public boolean test(RequestContext context) {
				return !delegate.test(context);
			}

			@Override
			public String describe() {
				return described;
			}
		};
	}

	private static GuardPredicate parsePositive(String expr)
	{
		int tilde = expr.indexOf('~');
		int colon = expr.indexOf(':');

		// type~regex  (no colon before the tilde)
		if (-1 < tilde && (-1 == colon || colon > tilde))
		{
			String type = expr.substring(0, tilde).trim();
			String regex = expr.substring(tilde + 1).trim();
			Pattern pattern = compile(regex, expr);

			switch (type)
			{
			case "host":
				return regexPredicate(expr, pattern, ctx -> ctx.getFrontCacheHost());
			case "uri":
				return regexPredicate(expr, pattern, ctx -> ctx.getRequestURI());
			case "query":
				return regexPredicate(expr, pattern, ctx -> ctx.getRequestQueryString());
			case "url":
				return regexPredicate(expr, pattern, ctx -> GuardAction.requestURL(ctx));
			default:
				throw new GuardConfigException("unknown predicate '" + type + "' in '" + expr + "'");
			}
		}

		if (-1 == colon)
			throw new GuardConfigException("malformed predicate '" + expr + "' (expected type:value or type~regex)");

		String type = expr.substring(0, colon).trim();
		String arg = expr.substring(colon + 1).trim();

		switch (type)
		{
		case "host":
			if (!"ip".equals(arg))
				throw new GuardConfigException("unknown host predicate '" + arg + "' in '" + expr + "' (only host:ip is supported)");
			return simplePredicate(expr, ctx -> isIpLiteral(ctx.getFrontCacheHost()));

		case "method":
		{
			final String method = arg.toUpperCase();
			return simplePredicate(expr, ctx -> {
				HttpServletRequest request = ctx.getRequest();
				return (null != request) && method.equals(request.getMethod());
			});
		}

		case "client-type":
		{
			final String clientType = arg;
			return simplePredicate(expr, ctx -> clientType.equals(ctx.getClientType()));
		}

		case "cookie":
		{
			final String cookieName = arg;
			if (0 == cookieName.length())
				throw new GuardConfigException("cookie predicate needs a name in '" + expr + "'");
			return simplePredicate(expr, ctx -> ctx.getCookieNames().contains(cookieName));
		}

		case "request-type":
		{
			// toplevel | include | include-async - set by FrontCacheEngine.init() from the
			// X-Frontcache-Request-Id / X-Frontcache-Include-Level headers
			final String requestType = arg;
			if (0 == requestType.length())
				throw new GuardConfigException("request-type predicate needs a value in '" + expr
						+ "' (" + FCHeaders.COMPONENT_TOPLEVEL + ", " + FCHeaders.COMPONENT_INCLUDE
						+ " or " + FCHeaders.COMPONENT_ASYNC_INCLUDE + ")");
			return simplePredicate(expr, ctx -> requestType.equals(ctx.getRequestType()));
		}

		case "rate":
			// The bucket defaults to the rule name, which is not known here - GuardRuleParser binds
			// it (and resolves the limiter) once it has parsed the whole line.
			return new RateLimitPredicate(RateLimitSpec.parse(arg, expr));

		case "header":
		{
			// header:<name> or header:<name>~<regex>
			int headerTilde = arg.indexOf('~');
			if (-1 == headerTilde)
			{
				final String headerName = arg;
				return simplePredicate(expr, ctx -> null != header(ctx, headerName));
			}

			final String headerName = arg.substring(0, headerTilde).trim();
			final Pattern pattern = compile(arg.substring(headerTilde + 1).trim(), expr);
			return simplePredicate(expr, ctx -> {
				String value = header(ctx, headerName);
				return (null != value) && pattern.matcher(cap(value)).find();
			});
		}

		default:
			throw new GuardConfigException("unknown predicate '" + type + "' in '" + expr + "'");
		}
	}

	/**
	 * A Host that is an IP literal rather than a name: scanners address the node directly, real
	 * clients arrive through DNS. Covers IPv4 (160.202.254.65) and IPv6 (the servlet container
	 * strips the brackets, so "0:0:0:0:0:0:0:1" arrives as-is).
	 */
	static boolean isIpLiteral(String host)
	{
		if (null == host || 0 == host.length())
			return false;

		if (-1 < host.indexOf(':'))  // IPv6 literal - a DNS name can never contain ':'
			return true;

		return IPV4.matcher(host).matches();
	}

	private static String header(RequestContext context, String name)
	{
		HttpServletRequest request = context.getRequest();
		if (null == request)
			return null;

		try {
			return request.getHeader(name);
		} catch (Exception e) {
			return null; // recycled request (async include) - undecidable, treat as absent
		}
	}

	private static String cap(String value)
	{
		if (null == value)
			return "";

		return (value.length() > MAX_MATCH_LENGTH) ? value.substring(0, MAX_MATCH_LENGTH) : value;
	}

	private interface ContextValue {
		String get(RequestContext context);
	}

	private interface ContextTest {
		boolean test(RequestContext context);
	}

	private static GuardPredicate regexPredicate(String described, Pattern pattern, ContextValue value)
	{
		return new GuardPredicate() {
			@Override
			public boolean test(RequestContext context) {
				String v = value.get(context);
				return (null != v) && pattern.matcher(cap(v)).find();
			}

			@Override
			public String describe() {
				return described;
			}
		};
	}

	private static GuardPredicate simplePredicate(String described, ContextTest test)
	{
		return new GuardPredicate() {
			@Override
			public boolean test(RequestContext context) {
				return test.test(context);
			}

			@Override
			public String describe() {
				return described;
			}
		};
	}

	private static Pattern compile(String regex, String expr)
	{
		try {
			return Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			throw new GuardConfigException("invalid regex in '" + expr + "': " + e.getMessage(), e);
		}
	}

}
