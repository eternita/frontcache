/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
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

import org.frontcache.FCConfig;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
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

		warnOnMissingExemptions();
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

		GuardRuleParser parser = new GuardRuleParser(allowedRedirectHosts());

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
	 * Hosts an absolute redirect target may point at, from
	 * front-cache.guard-rules.allowed-redirect-hosts.
	 *
	 * This used to also add front-cache.domains. That key is retired (a node configured with it
	 * refuses to start - see docs/archive/multi-domain-retirement-proposal.md), so the read is gone. Note
	 * that front-cache.default-domain has never been added here either, despite what this javadoc
	 * used to claim: a redirect to your own site must be listed explicitly.
	 */
	private Set<String> allowedRedirectHosts()
	{
		Set<String> hosts = new HashSet<String>();

		addHosts(hosts, FCConfig.getProperty("front-cache.guard-rules.allowed-redirect-hosts", ""));

		return hosts;
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
				resolvedTarget = rule.getAction().resolveTarget(context);

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

	private void close(java.io.Closeable closeable)
	{
		if (null == closeable)
			return;

		try {
			closeable.close();
		} catch (IOException e) { }
	}

}
