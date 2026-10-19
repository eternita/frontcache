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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.frontcache.FCConfig;
import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.ratelimit.GuardRateLimiterRegistry;
import org.frontcache.guard.ratelimit.RateLimitPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the guard rules for every request, before cache and origin.
 *
 * Rules are evaluated in order, first match wins:
 *
 *   1. built-ins (uri-too-long, bad-request) - always first, they are the structural checks
 *   2. rules from FRONTCACHE_HOME/conf/guard-rules.conf, in file order
 *
 * The rule list is global - there is no per-domain guard-rules.conf (a rule targets a domain with
 * a host~ predicate). Guards run on the request thread for every request, so evaluate() is written
 * to allocate nothing on the common no-match path and to never throw: any unexpected failure is
 * logged and treated as "no match", leaving Frontcache behaving exactly as it does without guards.
 */
public class GuardRuleEngine {

	public static final String GUARD_RULES_CONFIG_FILE = "guard-rules.conf";

	public static final String RULE_URI_TOO_LONG = "uri-too-long";
	public static final String RULE_BAD_REQUEST = "bad-request";

	private static Logger logger = LoggerFactory.getLogger(GuardRuleEngine.class);

	private final int maxRequestURILength;

	private volatile List<GuardRule> rules = Collections.emptyList();

	// Same set the parser validates against at load time, kept for the runtime check - a placeholder
	// puts a host into the target that no load-time check can see. Rebuilt by load(), so
	// reload-guard-rules picks up an edited allowlist.
	private volatile Set<String> redirectHostAllowlist = Collections.emptySet();

	// "<rule>#<host>" pairs already warned about. The trigger for a bad host is a crawler loop or a
	// misrouted probe, i.e. thousands of identical requests, so warning per request is how error.log
	// reaches gigabytes. Capped so a host-rotating client cannot turn the throttle itself into the leak.
	private static final int MAX_REMEMBERED_WARNINGS = 256;
	private final Set<String> warnedRedirectHosts =
			Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	public GuardRuleEngine(int maxRequestURILength) {
		this.maxRequestURILength = maxRequestURILength;
		load();
	}

	/**
	 * Re-reads guard-rules.conf. Built-ins are rebuilt too, so a changed
	 * front-cache.max-request-uri-length is picked up by the next FrontCacheEngine reload.
	 */
	public void reload() {
		load();
	}

	public List<GuardRule> getRules() {
		return rules;
	}

	private void load()
	{
		// Before the rules, because a rate: predicate is bound while they parse and the resolver
		// decides what its counter is keyed on.
		ClientIpResolver.reload();

		// Before the rules too: loadConfiguredRules() hands this to the parser, and evaluate() reads
		// it on every redirect whether or not configured rules were loaded at all.
		this.redirectHostAllowlist = Collections.unmodifiableSet(allowedRedirectHosts());

		List<GuardRule> loaded = new ArrayList<GuardRule>();

		loaded.addAll(buildInRules());

		if (Boolean.parseBoolean(FCConfig.getProperty("front-cache.guard-rules.enabled", "true")))
			loaded.addAll(loadConfiguredRules());
		else
			logger.info("Guard rules from " + GUARD_RULES_CONFIG_FILE + " are disabled (front-cache.guard-rules.enabled=false); built-in rules stay active");

		this.rules = Collections.unmodifiableList(loaded);

		logger.info("Guard rules loaded: " + this.rules.size() + " (" + buildInRules().size() + " built-in)");
		for (GuardRule rule : this.rules)
			logger.info("   " + rule);

		releaseUnusedRateLimitBuckets();
		warnOnMissingExemptions();
		warnOnRateLimitWithoutTrustedProxies();
	}

	/**
	 * Gives back the memory of rate-limit buckets no longer named by any rule. Runs after the new
	 * rule list is live, so a bucket that survived the reload keeps the counts it already had.
	 */
	private void releaseUnusedRateLimitBuckets()
	{
		Set<String> inUse = new TreeSet<String>();

		for (GuardRule rule : this.rules)
			for (GuardPredicate predicate : rule.getPredicates())
				if (predicate instanceof RateLimitPredicate)
					inUse.add(((RateLimitPredicate) predicate).getBucket());

		GuardRateLimiterRegistry.retain(inUse);
	}

	/**
	 * Rate limiting behind a reverse proxy with no trusted proxies configured keys EVERY request on
	 * the proxy's own address - which in the documented deployment (TLS terminated upstream,
	 * proxied to 127.0.0.1) is one address for the whole site, so the first request over the limit
	 * takes everyone down with it. Warn loudly rather than refuse: the pipeline's stance is that a
	 * guard misconfiguration must degrade to "Frontcache as usual", not to an outage.
	 */
	private void warnOnRateLimitWithoutTrustedProxies()
	{
		if (ClientIpResolver.getInstance().hasTrustedProxies())
			return;

		for (GuardRule rule : this.rules)
			for (GuardPredicate predicate : rule.getPredicates())
				if (predicate instanceof RateLimitPredicate)
				{
					logger.warn("Guard rule '" + rule.getName() + "' rate-limits per client IP, but "
							+ ClientIpResolver.TRUSTED_PROXIES_PROPERTY + " is empty, so the limiter keys on the socket"
							+ " peer and ignores every forwarding header. That is correct only if clients connect to"
							+ " this node DIRECTLY - behind nginx/a CDN it counts the whole site as one client."
							+ " Set " + ClientIpResolver.TRUSTED_PROXIES_PROPERTY + " to the proxy's address.");
					return;
				}
	}

	/**
	 * The two checks that used to be hard-coded in FrontCacheEngine.processRequest. They stay in
	 * Java - they are structural, cheap, and want no regex - but report through the same rule model
	 * so logging, hit counters and dashboards are uniform.
	 */
	private List<GuardRule> buildInRules()
	{
		List<GuardRule> builtIns = new ArrayList<GuardRule>(2);

		// 1) URI too long (path + query) -> 414. <= 0 disables the check.
		if (0 < maxRequestURILength)
		{
			GuardPredicate tooLong = new GuardPredicate() {
				@Override
				public boolean test(RequestContext context) {
					String uri = context.getRequestURI();
					String query = context.getRequestQueryString();
					int length = ((null != uri) ? uri.length() : 0) + ((null != query) ? query.length() : 0);
					return length > maxRequestURILength;
				}

				@Override
				public String describe() {
					return "uri-length>" + maxRequestURILength;
				}
			};

			builtIns.add(new GuardRule(RULE_URI_TOO_LONG, Arrays.asList(tooLong),
					GuardAction.reject(414, "414 Request-URI Too Long"), false));
		}

		// 2) structurally-invalid query params (amp;-mangled) -> 400
		if (Boolean.parseBoolean(FCConfig.getProperty("front-cache.guard-rules.bad-request.enabled", "true")))
		{
			GuardPredicate malformed = new GuardPredicate() {
				@Override
				public boolean test(RequestContext context) {
					return FCUtils.hasMalformedQueryParams(context.getRequestQueryString());
				}

				@Override
				public String describe() {
					return "malformed-query-params";
				}
			};

			builtIns.add(new GuardRule(RULE_BAD_REQUEST, Arrays.asList(malformed),
					GuardAction.reject(400, "400 Bad Request"), false));
		}

		return builtIns;
	}

	/**
	 * Dry-parses a candidate guard-rules.conf without touching anything live, for the
	 * {@code set-config} management action.
	 *
	 * <b>Nothing here mutates state.</b> No rule swap, no {@code ClientIpResolver.reload()}, no hit
	 * counters reset, no rate-limit buckets bound to the live registry - the parsed rules are thrown
	 * away. The allowlist used is the live one, which is correct: it comes from
	 * frontcache.properties, not from the file being edited.
	 *
	 * @return one message per unusable line, {@code "line N: ..."}, in file order; empty when the
	 *         whole file parses
	 */
	public List<String> validate(String content)
	{
		List<String> errors = new ArrayList<String>();

		if (null == content)
			return errors;

		GuardRuleParser parser = new GuardRuleParser(this.redirectHostAllowlist);

		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++)
		{
			String trimmed = lines[i].trim();
			if (0 == trimmed.length() || trimmed.startsWith("#"))
				continue;

			try {
				checkNoSelfRedirect(parser.parse(trimmed));
			} catch (GuardConfigException e) {
				errors.add("line " + (i + 1) + ": " + e.getMessage());
			}
		}

		return errors;
	}

	private List<GuardRule> loadConfiguredRules()
	{
		List<GuardRule> loaded = new ArrayList<GuardRule>();

		InputStream is = null;
		try {
			is = FCConfig.getConfigInputStream(GUARD_RULES_CONFIG_FILE);
		} catch (Throwable e) {
			// the file is optional - FCConfig.getConfigInputStream() throws when it is absent
			logger.info("No " + GUARD_RULES_CONFIG_FILE + " found - only built-in guard rules are active");
			return loaded;
		}

		if (null == is)
			return loaded;

		GuardRuleParser parser = new GuardRuleParser(this.redirectHostAllowlist);

		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String line;
			int lineNumber = 0;
			while (null != (line = reader.readLine()))
			{
				lineNumber++;
				String trimmed = line.trim();
				if (0 == trimmed.length() || trimmed.startsWith("#"))
					continue;

				try {
					GuardRule rule = parser.parse(trimmed);
					checkNoSelfRedirect(rule);
					loaded.add(rule);
				} catch (GuardConfigException e) {
					// one bad line must not cost the whole file
					logger.error("Skipping guard rule (" + GUARD_RULES_CONFIG_FILE + ":" + lineNumber + "): " + e.getMessage());
				}
			}
		} catch (Exception e) {
			logger.error("Can't read " + GUARD_RULES_CONFIG_FILE + " - loaded " + loaded.size() + " rule(s) before the failure", e);
		} finally {
			close(reader);
			close(is);
		}

		return loaded;
	}

	/**
	 * Hosts an absolute redirect target may point at, from front-cache.default-domain and
	 * front-cache.guard-rules.allowed-redirect-hosts.
	 *
	 * This used to also add front-cache.domains. That key is retired (a node configured with it
	 * refuses to start), so the read is gone.
	 *
	 * front-cache.default-domain IS included, as of 2.9.0 - settling
	 * the follow-up left open by the multi-domain retirement, where the javadoc here claimed it was
	 * and the code never did it. It has to be now: ${url} and ${host} emit that hostname, so a rule
	 * redirecting to your own site would otherwise be refused at load time against an allowlist not
	 * containing the one name the node calls itself. It widens the allowlist by exactly the site the
	 * node already serves.
	 */
	private Set<String> allowedRedirectHosts()
	{
		Set<String> hosts = new HashSet<String>();

		addHosts(hosts, publicDomain());
		addHosts(hosts, FCConfig.getProperty("front-cache.guard-rules.allowed-redirect-hosts", ""));

		return hosts;
	}

	/**
	 * This site's public hostname, or null.
	 *
	 * Read from config rather than from FrontCacheEngine: the engine builds its GuardRuleEngine from
	 * its own constructor, so getFrontCache() would recurse or hand back a half-built instance.
	 * Mirrors FrontCacheEngine.getPublicDomain(), including its refusal to treat the
	 * FCConfig.DEFAULT_DOMAIN placeholder as a real hostname.
	 */
	private static String publicDomain()
	{
		String domain = FCConfig.getProperty("front-cache.default-domain", FCConfig.DEFAULT_DOMAIN);
		if (null == domain)
			return null;

		String trimmed = domain.trim();
		if (0 == trimmed.length() || FCConfig.DEFAULT_DOMAIN.equals(trimmed))
			return null;

		return trimmed;
	}

	private void addHosts(Set<String> hosts, String commaSeparated)
	{
		if (null == commaSeparated)
			return;

		for (String host : commaSeparated.split(","))
		{
			String trimmed = host.trim().toLowerCase();
			if (0 < trimmed.length())
				hosts.add(trimmed);
		}
	}

	/**
	 * Redirect-loop protection at load time: if a rule's own condition matches the URL it redirects
	 * to, every visit bounces forever. Best effort - a target carrying ${...} placeholders is
	 * checked with the placeholders removed.
	 */
	private void checkNoSelfRedirect(GuardRule rule)
	{
		if (GuardAction.Type.REDIRECT != rule.getAction().getType())
			return;

		String target = rule.getAction().getTarget();
		if (null == target)
			return;

		RequestContext probe = probeContext(stripPlaceholders(target));
		if (null == probe)
			return;

		if (rule.matches(probe))
			throw new GuardConfigException("rule '" + rule.getName() + "' redirects to a URL its own condition matches ("
					+ target + ") - that is an infinite redirect loop");
	}

	/**
	 * Warns when a rule can terminate requests to the paths that must always work: the GSLB health
	 * check, the management servlet, the dashboard stream and the Prometheus scrape. All of them are
	 * commonly addressed by IP, which is exactly what an ip-access rule matches - a missing exemption
	 * turns that rule into an outage.
	 *
	 * The URL patterns come from web.xml (frontcache-server and the e2e webapps both map the IO
	 * servlet at /frontcache-io).
	 */
	private void warnOnMissingExemptions()
	{
		checkReachable("http://127.0.0.1/fc-ping.jsp", "GSLB health check (/fc-ping.jsp)");
		checkReachable("http://127.0.0.1/frontcache-io?action=ping", "management API (/frontcache-io) used by frontcache-agent, the console and replication");
		// Both paths, because the servlet answers on both and a rule that blocks only the legacy one
		// still breaks every console still asking for it.
		checkReachable("http://127.0.0.1/fc-dashboard.stream", "dashboard stream (/fc-dashboard.stream) read by the console");
		checkReachable("http://127.0.0.1/hystrix.stream", "dashboard stream on its pre-2.7 path (/hystrix.stream)");
		checkReachable("http://127.0.0.1/fc-metrics", "Prometheus scrape endpoint (/fc-metrics)");
	}

	private void checkReachable(String url, String what)
	{
		RequestContext probe = probeContext(url);
		if (null == probe)
			return;

		// countHits=false: a startup self-check must not show up as traffic in the hit counters the
		// console reports
		GuardOutcome outcome = evaluate(probe, false);
		if (outcome.isTerminal())
			logger.warn("Guard rule '" + outcome.getRule().getName() + "' would " + outcome.getAction()
					+ " the " + what + " - add an 'allow' rule for it ABOVE that rule in " + GUARD_RULES_CONFIG_FILE);
	}

	/**
	 * Minimal RequestContext for a URL, with no servlet request behind it - used for load-time loop
	 * detection and the exemption warnings. Predicates that need the servlet request (cookie,
	 * header, method) are null-safe and simply do not match here.
	 */
	static RequestContext probeContext(String url)
	{
		if (null == url)
			return null;

		int schemeIdx = url.indexOf("://");
		if (-1 == schemeIdx)
			return null;

		String protocol = url.substring(0, schemeIdx);
		String rest = url.substring(schemeIdx + 3);

		int pathIdx = rest.indexOf('/');
		String hostPort = (-1 < pathIdx) ? rest.substring(0, pathIdx) : rest;
		String pathAndQuery = (-1 < pathIdx) ? rest.substring(pathIdx) : "/";

		int portIdx = hostPort.lastIndexOf(':');
		String host = (-1 < portIdx && -1 == hostPort.indexOf(']')) ? hostPort.substring(0, portIdx) : hostPort;

		int queryIdx = pathAndQuery.indexOf('?');
		String uri = (-1 < queryIdx) ? pathAndQuery.substring(0, queryIdx) : pathAndQuery;
		String query = (-1 < queryIdx) ? pathAndQuery.substring(queryIdx) : "";

		RequestContext context = new RequestContext();
		// A probe is not traffic: predicates with side effects (the rate limiter) must do nothing
		// for it. evaluate()'s countHits flag cannot carry this - rule.matches() never sees it, and
		// checkNoSelfRedirect() calls matches() directly.
		context.setGuardProbe();
		context.setFrontCacheProtocol(protocol);
		context.setFrontCacheHost(host);
		context.setRequestURI(uri);
		context.setRequestQueryString(query);
		return context;
	}

	private static String stripPlaceholders(String target)
	{
		if (null == target || -1 == target.indexOf("${"))
			return target;

		return target.replaceAll("\\$\\{[^}]*\\}", "");
	}

	/**
	 * Runs the rules for one request. Never throws.
	 *
	 * @return a terminal outcome (reject/redirect) or CONTINUE, carrying any dry-run rules that
	 *         matched so the caller can log them
	 */
	public GuardOutcome evaluate(RequestContext context)
	{
		return evaluate(context, true);
	}

	/**
	 * @param countHits false for synthetic probes (load-time self-checks), so they do not pollute
	 *                  the hit counters reported by the management API
	 */
	GuardOutcome evaluate(RequestContext context, boolean countHits)
	{
		List<GuardRule> rulesNow = this.rules;
		if (rulesNow.isEmpty())
			return GuardOutcome.CONTINUE;

		List<GuardRule> dryRunMatches = null;

		for (GuardRule rule : rulesNow)
		{
			boolean matched;
			try {
				matched = rule.matches(context);
			} catch (Throwable e) {
				// fail open: a broken predicate degrades to "Frontcache as usual", never to a 500
				logger.error("Guard rule '" + rule.getName() + "' failed to evaluate - ignoring it for this request", e);
				continue;
			}

			if (!matched)
				continue;

			if (countHits)
				rule.countHit();

			if (rule.isDryRun())
			{
				if (null == dryRunMatches)
					dryRunMatches = new ArrayList<GuardRule>(2);
				dryRunMatches.add(rule);
				continue;
			}

			if (GuardAction.Type.ALLOW == rule.getAction().getType())
				return GuardOutcome.cont(dryRunMatches);

			String resolvedTarget = null;
			if (GuardAction.Type.REDIRECT == rule.getAction().getType())
			{
				String expanded = rule.getAction().resolveTarget(context);
				resolvedTarget = sanitizeTarget(rule, expanded);

				// sanitizeTarget() refused the target's own host - redirecting there would be an open
				// redirect, so the rule does not act and the request goes on to cache/origin
				if (null == resolvedTarget && null != expanded)
					continue;

				// runtime loop guard: redirecting a URL to itself is a browser-side infinite loop.
				// Load-time detection catches the static cases; this catches the ones that only
				// appear once placeholders are expanded.
				if (null != resolvedTarget && resolvedTarget.equals(GuardAction.requestURL(context)))
				{
					logger.warn("Guard rule '" + rule.getName() + "' resolved to the request's own URL (" + resolvedTarget + ") - skipping it");
					continue;
				}
			}

			return GuardOutcome.terminal(rule, resolvedTarget, dryRunMatches);
		}

		return GuardOutcome.cont(dryRunMatches);
	}

	/**
	 * Last line of defence for the host in a redirect we are about to send, applied AFTER the
	 * placeholders are expanded.
	 *
	 * GuardRuleParser.checkRedirectTarget validates the target as written in the config file, which
	 * is a load-time check on a static string. It cannot see a host that only appears at runtime -
	 * and ${url} / ${host} put one there, on every request. With front-cache.default-domain set that
	 * host is already the site's own (GuardAction.publicHost), so this normally finds nothing; it is
	 * what protects a node that names no public host, which is what a default FRONTCACHE_HOME does
	 * and what every example-site edge did when this was written.
	 *
	 * Two places a host can appear, handled differently because the consequences differ:
	 *
	 * <ul>
	 * <li>the target's OWN host - a bad one is an open redirect, so the rule is skipped and the
	 *     request carries on to cache/origin;</li>
	 * <li>an absolute URL inside a query parameter (return=, next=, from=) - a bad one leaks an
	 *     internal hostname and sends the visitor there after login. The parameter is rewritten onto
	 *     the public host when there is one, and dropped when there is not. NOT skipped: dropping
	 *     the guard means the abusive request reaches origin, which is the thing the rule exists to
	 *     stop, and losing a return= only costs the visitor their place.</li>
	 * </ul>
	 *
	 * Never throws - a failure here degrades to the unsanitized target, which is what shipped before.
	 *
	 * @return the target to send, or null to skip this rule
	 */
	String sanitizeTarget(GuardRule rule, String target)
	{
		if (null == target || 0 == target.length())
			return target;

		try {
			Set<String> allowed = this.redirectHostAllowlist;

			String targetHost = hostOf(target);
			if (null != targetHost && !allowed.contains(targetHost.toLowerCase()))
			{
				warnOnce(rule, targetHost, "redirect target host '" + targetHost + "' is not allowed"
						+ " - skipping the rule for requests that resolve to it");
				return null;
			}

			int queryIdx = target.indexOf('?');
			if (-1 == queryIdx)
				return target;

			String query = target.substring(queryIdx + 1);
			int fragmentIdx = query.indexOf('#');
			String fragment = "";
			if (-1 < fragmentIdx)
			{
				fragment = query.substring(fragmentIdx);
				query = query.substring(0, fragmentIdx);
			}

			String sanitizedQuery = sanitizeQuery(rule, query, allowed);
			if (sanitizedQuery.equals(query))
				return target;

			if (0 == sanitizedQuery.length())
				return target.substring(0, queryIdx) + fragment;

			return target.substring(0, queryIdx + 1) + sanitizedQuery + fragment;

		} catch (Exception e) {
			logger.error("Guard rule '" + rule.getName() + "': could not check the redirect target - sending it unchanged", e);
			return target;
		}
	}

	/**
	 * Rewrites or drops query parameters whose value is an absolute URL pointing somewhere we do not
	 * vouch for. Parameters that are not absolute URLs - the overwhelming majority - are passed
	 * through byte for byte, so a value this method does not understand is never mangled.
	 */
	private String sanitizeQuery(GuardRule rule, String query, Set<String> allowed)
	{
		StringBuilder sb = new StringBuilder(query.length());
		boolean first = true;

		// -1 keeps trailing empty segments, and an empty segment is passed through rather than
		// dropped: "unchanged" has to mean byte-identical, or the caller's equals() check turns a
		// no-op into a rewritten URL.
		for (String pair : query.split("&", -1))
		{
			int eq = pair.indexOf('=');
			String name = (-1 < eq) ? pair.substring(0, eq) : pair;
			String rawValue = (-1 < eq) ? pair.substring(eq + 1) : null;

			String replacement = pair;
			if (null != rawValue && 0 < rawValue.length())
			{
				String decoded = decode(rawValue);
				String embeddedHost = hostOf(decoded);

				if (null != embeddedHost && !allowed.contains(embeddedHost.toLowerCase()))
				{
					String publicDomain = publicDomain();
					if (null == publicDomain)
					{
						warnOnce(rule, embeddedHost, "redirect parameter '" + name + "' points at '" + embeddedHost
								+ "', which is not an allowed redirect host, and front-cache.default-domain names no"
								+ " public host to put there instead - dropping the parameter."
								+ " See docs/release-notes/2.9.0.md");
						continue; // drop it
					}

					warnOnce(rule, embeddedHost, "redirect parameter '" + name + "' points at '" + embeddedHost
							+ "', which is not an allowed redirect host - rewriting it onto '" + publicDomain + "'."
							+ " See docs/release-notes/2.9.0.md");

					replacement = name + "=" + encode(replaceHost(decoded, embeddedHost, publicDomain));
				}
			}

			if (!first)
				sb.append('&');
			sb.append(replacement);
			first = false;
		}

		return sb.toString();
	}

	/**
	 * @return the host of an absolute http(s) URL, without port or userinfo; null if the text is not
	 *         one (a relative path, a plain value, a mailto:, anything else)
	 */
	static String hostOf(String url)
	{
		if (null == url)
			return null;

		String lower = url.toLowerCase();
		int schemeEnd;
		if (lower.startsWith("http://"))
			schemeEnd = 7;
		else if (lower.startsWith("https://"))
			schemeEnd = 8;
		else
			return null;

		int hostEnd = url.length();
		for (int i = schemeEnd; i < url.length(); i++)
		{
			char c = url.charAt(i);
			if ('/' == c || '?' == c || '#' == c)
			{
				hostEnd = i;
				break;
			}
		}

		String authority = url.substring(schemeEnd, hostEnd);

		// userinfo - "https://www.example-site.com@evil.example.com/" is a URL whose host is
		// evil.example.com, and reading the part before the '@' as the host is the classic way to be
		// fooled by one
		int at = authority.lastIndexOf('@');
		if (-1 < at)
			authority = authority.substring(at + 1);

		int portIdx = authority.lastIndexOf(':');
		if (-1 < portIdx && -1 == authority.indexOf(']')) // strip :port, keep IPv6 literals intact
			authority = authority.substring(0, portIdx);

		return (0 < authority.length()) ? authority : null;
	}

	/**
	 * Swaps the host of an absolute URL, leaving scheme, port, path, query and fragment alone. The
	 * host is matched at its exact position rather than by string replacement, so a hostname that
	 * also occurs in the path or query is untouched.
	 */
	private static String replaceHost(String url, String host, String replacement)
	{
		int idx = url.toLowerCase().indexOf(host.toLowerCase());
		if (-1 == idx)
			return url;

		return url.substring(0, idx) + replacement + url.substring(idx + host.length());
	}

	private static String decode(String value)
	{
		try {
			return java.net.URLDecoder.decode(value, "UTF-8");
		} catch (Exception e) {
			return value; // malformed %-escape: treat it as opaque, which means hostOf() returns null
		}
	}

	private static String encode(String value)
	{
		try {
			return java.net.URLEncoder.encode(value, "UTF-8");
		} catch (Exception e) {
			return value;
		}
	}

	/**
	 * One WARN per rule/host pair, not per request. See MAX_REMEMBERED_WARNINGS.
	 */
	private void warnOnce(GuardRule rule, String host, String message)
	{
		String key = rule.getName() + "#" + host.toLowerCase();

		if (!warnedRedirectHosts.add(key))
			return;

		if (MAX_REMEMBERED_WARNINGS < warnedRedirectHosts.size())
			warnedRedirectHosts.clear(); // start over rather than grow without bound

		logger.warn("Guard rule '" + rule.getName() + "': " + message);
	}

	private void close(java.io.Closeable closeable)
	{
		if (null == closeable)
			return;

		try {
			closeable.close();
		} catch (IOException e) { }
	}

}
