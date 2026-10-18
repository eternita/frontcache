/*
 * Copyright 2015 Eternita
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.FCConfig;
import org.frontcache.core.Cidr;
import org.frontcache.core.EdgeToken;
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
 *   [!] ua~&lt;regex&gt;
 *   [!] ua-contains:&lt;literal&gt;        (case-insensitive)
 *   [!] client-ip:&lt;cidr|address|@file&gt;
 *   [!] uri~&lt;regex&gt;
 *   [!] query~&lt;regex&gt;
 *   [!] url~&lt;regex&gt;
 *   [!] method:&lt;GET|POST|...&gt;
 *   [!] client-type:&lt;bot|guest&gt;
 *   [!] cookie:&lt;name&gt;
 *   [!] header:&lt;name&gt;[~&lt;regex&gt;]
 *   [!] request-type:&lt;toplevel|include|include-async&gt;
 *   [!] edge-token                        (carries this node's front-cache.edge-token; guard-rules.conf only)
 *       rate:&lt;limit&gt;/&lt;window&gt; [bucket=&lt;name&gt;] [scope=&lt;toplevel|all&gt;]   (never negated)
 *
 * Regexes are compiled once, here, and matched with find() semantics - the same behaviour as
 * dynamic-urls.conf patterns.
 */
public class GuardPredicateFactory {

	/**
	 * Which config file a predicate is being parsed for. The grammar is shared; two predicates are
	 * not.
	 *
	 * <ul>
	 * <li>{@code client-type:} is refused in {@link #BOTS} because it is circular - classification
	 *     computes the value that predicate reads.</li>
	 * <li>{@code rate:} is refused in {@link #BOTS} because it counts. Classification runs for every
	 *     request including every include, before the guard rules, so metering there would spend a
	 *     visitor's budget once per fragment of their page. Rate-based reactions belong in
	 *     guard-rules.conf, which can read the classification back through {@code client-type:}.</li>
	 * </ul>
	 */
	public enum Scope {
		GUARD("guard-rules.conf"),
		BOTS("bots.conf");

		private final String file;

		private Scope(String file) {
			this.file = file;
		}

		public String getFile() {
			return file;
		}
	}

	/**
	 * Query strings are attacker-controlled and unbounded; a pathological one can make an
	 * innocent-looking regex backtrack for a long time on the request thread. Match against at most
	 * this many characters - anything longer is already handled by the uri-too-long guard.
	 */
	static final int MAX_MATCH_LENGTH = 8192;

	private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GuardPredicateFactory.class);

	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

	private static final String EDGE_TOKEN = "edge-token";

	private GuardPredicateFactory() {
	}

	/**
	 * @param expression single predicate as written in the config, e.g. "!cookie:hruc"
	 * @return compiled predicate
	 * @throws GuardConfigException on unknown predicate type or invalid regex
	 */
	public static GuardPredicate parse(String expression)
	{
		return parse(expression, Scope.GUARD);
	}

	/**
	 * @param expression single predicate as written in the config, e.g. "!cookie:hruc"
	 * @param scope      which file it came from - see {@link Scope}
	 * @return compiled predicate
	 * @throws GuardConfigException on unknown predicate type or invalid regex
	 */
	public static GuardPredicate parse(String expression, Scope scope)
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

		GuardPredicate predicate = parsePositive(expr, scope);

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

	private static GuardPredicate parsePositive(String expr, Scope scope)
	{
		// The one predicate that takes no argument: it asks about the hop, not about the request's
		// content. Checked first, so the type:value / type~regex parsing below never sees it.
		if (EDGE_TOKEN.equals(expr) || expr.startsWith(EDGE_TOKEN + ":") || expr.startsWith(EDGE_TOKEN + "~"))
			return parseEdgeToken(expr, scope);

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
			case "ua":
				// sugar for header:User-Agent~<regex>, which bots.conf writes on most of its lines.
				// Reads the memoized value rather than the header, so a recycled request degrades to
				// "no user agent" instead of throwing.
				return regexPredicate(expr, pattern, ctx -> ctx.getUserAgent());
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
			if (Scope.BOTS == scope)
				throw new GuardConfigException("'" + expr + "' cannot be used in " + scope.getFile()
						+ " - classification is what computes the client type, so a rule reading it here is circular."
						+ " Write the classification here and the reaction in guard-rules.conf");

			final String clientType = arg;
			return simplePredicate(expr, ctx -> clientType.equals(ctx.getClientType()));
		}

		case "ua-contains":
		{
			// The bare-keyword form of bots.conf. Case-INSENSITIVE as of 2.9.0: the pre-2.9.0
			// String.contains() meant a file listing 'Googlebot' did not match 'googlebot/2.1',
			// which was a latent bug rather than a contract (see
			// docs/archive/dynamic-bots-classification-proposal.md section 4.2).
			if (0 == arg.length())
				throw new GuardConfigException("ua-contains predicate needs a value in '" + expr + "'");

			final String needle = arg.toLowerCase();
			return simplePredicate(expr, ctx -> {
				String userAgent = ctx.getUserAgent();
				return (null != userAgent) && cap(userAgent).toLowerCase().contains(needle);
			});
		}

		case "client-ip":
		{
			final List<Cidr> blocks = parseClientIpArgument(arg, expr);
			return simplePredicate(expr, ctx -> {
				// Resolved through ClientIpResolver, NOT FCUtils.getClientIP(): the latter believes
				// thirteen client-supplied headers, so a client could put itself inside any block
				// named here just by sending one.
				String address = ctx.getResolvedClientIP();
				if (null == address)
					return false; // undecidable (recycled request, no trusted proxy answer) - never a match

				for (Cidr block : blocks)
					if (block.contains(address))
						return true;

				return false;
			});
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
			if (Scope.BOTS == scope)
				throw new GuardConfigException("'" + expr + "' cannot be used in " + scope.getFile()
						+ " - it counts, and classification runs for every request including every include,"
						+ " so it would spend a visitor's budget once per fragment of their page."
						+ " Put the rate rule in guard-rules.conf and select on client-type: there");

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
	 * {@code edge-token}: the request carried this node's {@code front-cache.edge-token}, i.e. it came
	 * from a Frontcache of the same chain rather than straight from a client. Written negated on a
	 * second-layer node - {@code !edge-token} is "reached me directly". See {@link EdgeToken}.
	 *
	 * Refused, so the rule is skipped with an ERROR like any other unparseable rule, in two cases:
	 *
	 * <ul>
	 * <li>On a node with no token. {@code !edge-token} would then match EVERY request - the edge's
	 *     included - and a rule meant to send direct traffic back to the main route would send all of
	 *     it, which on a second layer is a redirect loop through the edge. Refusing is also what makes
	 *     the console's Save refuse such a file, since {@code ConfigFileValidator} parses through
	 *     here.</li>
	 * <li>In bots.conf. The token identifies the hop, not the visitor, and the client type is about
	 *     the visitor - an edge's request is classified on what the edge propagates, not on the fact
	 *     that it came from an edge.</li>
	 * </ul>
	 */
	private static GuardPredicate parseEdgeToken(String expr, Scope scope)
	{
		if (!EDGE_TOKEN.equals(expr))
			throw new GuardConfigException("'" + expr + "' takes no argument - write '" + EDGE_TOKEN + "' or '!"
					+ EDGE_TOKEN + "'");

		if (Scope.BOTS == scope)
			throw new GuardConfigException("'" + expr + "' cannot be used in " + scope.getFile()
					+ " - it identifies the hop a request came through, not the visitor; put it in guard-rules.conf");

		if (!EdgeToken.isConfigured())
			throw new GuardConfigException("'" + expr + "' needs " + EdgeToken.EDGE_TOKEN_PROPERTY + " in frontcache.properties"
					+ " - without a token no request carries one, so '!" + EDGE_TOKEN + "' would match every request");

		return simplePredicate(expr, ctx -> ctx.isEdgeTokenValid());
	}

	/**
	 * Parses the argument of a {@code client-ip:} predicate: one or more comma-separated blocks, or
	 * {@code @<file>} naming a list under FRONTCACHE_HOME/conf.
	 *
	 * The file form exists because published crawler ranges are hundreds of CIDRs, refreshed by the
	 * vendor: inlining them makes the rules file unreadable and puts an ops cron job inside a file
	 * humans edit. One block per line, '#' comments and blank lines skipped.
	 *
	 * A file that is missing, empty or entirely unparseable fails THIS RULE at load time. That is
	 * deliberate: a client-ip rule with nothing in it silently matches nothing, and a rule that
	 * silently matches nothing is worse than a rule the log says was skipped.
	 */
	private static List<Cidr> parseClientIpArgument(String arg, String expr)
	{
		if (0 == arg.length())
			throw new GuardConfigException("client-ip predicate needs an address, a CIDR block or @file in '" + expr + "'");

		if (!arg.startsWith("@"))
		{
			List<Cidr> blocks = new ArrayList<Cidr>();
			for (String token : arg.split(","))
			{
				String trimmed = token.trim();
				if (0 == trimmed.length())
					continue;

				Cidr block = Cidr.parse(trimmed);
				if (null == block)
					throw new GuardConfigException("'" + trimmed + "' is not an address or CIDR block in '" + expr
							+ "' - expected e.g. 203.0.113.7 or 66.249.64.0/19");

				blocks.add(block);
			}

			if (blocks.isEmpty())
				throw new GuardConfigException("client-ip predicate needs an address, a CIDR block or @file in '" + expr + "'");

			return blocks;
		}

		return loadClientIpFile(arg.substring(1).trim(), expr);
	}

	private static List<Cidr> loadClientIpFile(String name, String expr)
	{
		// A config file names a list to read, not a capability to read the filesystem: the name is
		// resolved under FRONTCACHE_HOME/conf by FCConfig, so anything that could climb out of it is
		// refused before it gets there.
		if (0 == name.length() || name.startsWith("/") || name.startsWith("\\")
				|| -1 < name.indexOf("..") || -1 < name.indexOf(':'))
			throw new GuardConfigException("'@" + name + "' in '" + expr
					+ "' must be a plain path under FRONTCACHE_HOME/conf, e.g. @bot-networks/googlebot.txt");

		List<Cidr> blocks = new ArrayList<Cidr>();
		InputStream is = null;
		BufferedReader reader = null;

		try {
			// FCConfig.getConfigInputStream() signals "file absent" by THROWING - hence the catch of
			// Exception rather than IOException, the same trap the IO servlet's readConfigFile has.
			is = FCConfig.getConfigInputStream(name);
			if (null == is)
				throw new GuardConfigException("can't read conf/" + name + " named by '" + expr + "'");

			reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String line;
			int lineNumber = 0;
			while (null != (line = reader.readLine()))
			{
				lineNumber++;
				String trimmed = line.trim();
				if (0 == trimmed.length() || trimmed.startsWith("#"))
					continue;

				Cidr block = Cidr.parse(trimmed);
				if (null == block)
				{
					// one bad line does not void the list - but it is never silent
					logger.error("Ignoring unparseable entry '" + trimmed + "' (conf/" + name + ":" + lineNumber
							+ ") named by '" + expr + "' - expected an address or address/prefix");
					continue;
				}

				blocks.add(block);
			}
		} catch (GuardConfigException e) {
			throw e;
		} catch (Exception e) {
			throw new GuardConfigException("can't read conf/" + name + " named by '" + expr + "': " + e.getMessage(), e);
		} finally {
			close(reader);
			close(is);
		}

		if (blocks.isEmpty())
			throw new GuardConfigException("conf/" + name + " named by '" + expr + "' holds no usable address blocks"
					+ " - a client-ip rule with an empty list matches nothing, silently");

		return blocks;
	}

	private static void close(java.io.Closeable closeable)
	{
		if (null == closeable)
			return;

		try {
			closeable.close();
		} catch (Exception e) { /* ignored */ }
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
