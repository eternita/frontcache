/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.bots;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.frontcache.FCConfig;
import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.RequestContext;
import org.frontcache.guard.GuardConfigException;
import org.frontcache.guard.GuardPredicate;
import org.frontcache.guard.GuardPredicateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides a request's client type - {@code bot} or {@code guest} - from conf/bots.conf.
 *
 * Evaluation order, first match wins:
 *
 * <ol>
 * <li>built-in {@code no-user-agent} (a request with no User-Agent is a bot);</li>
 * <li>rules from conf/bots.conf, in file order;</li>
 * <li>bare User-Agent keywords from the same file, in file order - so an explicit rule always wins
 *     over a keyword, whichever way round the file is written;</li>
 * <li>built-in {@code default} ({@code guest}), which always matches.</li>
 * </ol>
 *
 * <b>The verdict is a behaviour profile, not a judgement.</b> It selects which branch of a cached
 * WebResponse's expireTimeMap applies - a different TTL, or cache vs. an origin call - and whether
 * an {@code <fc:include client="...">} fragment is resolved. Guard rules read it back through
 * {@code client-type:}, but that is one consumer among several.
 *
 * This is a process-wide singleton rather than a member of FrontCacheEngine, for the same reason
 * {@link ClientIpResolver} is: the management API and FCConfig both need to read it, and neither
 * may boot an engine by asking.
 *
 * Runs on the request thread for every request, so classify() allocates nothing on the common path
 * and never throws: a predicate that blows up is logged once and treated as "did not match", which
 * degrades to the client type the next rule decides rather than to a 500.
 */
public class BotClassifier {

	public static final String BOTS_CONFIG_FILE = "bots.conf";

	/** built-in, evaluated first: no User-Agent header at all */
	public static final String RULE_NO_USER_AGENT = "no-user-agent";

	/** built-in, evaluated last: always matches, so classify() always has an answer */
	public static final String RULE_DEFAULT = "default";

	public static final String RULES_ENABLED_PROPERTY = "front-cache.bots.rules.enabled";
	public static final String EMPTY_USER_AGENT_IS_BOT_PROPERTY = "front-cache.bots.empty-user-agent-is-bot";

	private static Logger logger = LoggerFactory.getLogger(BotClassifier.class);

	private static volatile BotClassifier instance;

	private final List<BotRule> rules;

	// bare keyword lines only, in file order - what get-bots reports as botKeywords, which is the
	// field an older console knows how to render
	private final Set<String> keywords;

	private BotClassifier(List<BotRule> rules, Set<String> keywords) {
		this.rules = Collections.unmodifiableList(rules);
		this.keywords = Collections.unmodifiableSet(keywords);
	}

	/**
	 * @return the classifier built from the current configuration, created on first use
	 */
	public static BotClassifier getInstance()
	{
		BotClassifier current = instance;
		if (null == current)
		{
			synchronized (BotClassifier.class) {
				current = instance;
				if (null == current)
					current = instance = load();
			}
		}
		return current;
	}

	/**
	 * Re-reads conf/bots.conf. Called by the reload-bots management action, and by the engine's
	 * reload - adding a crawler to the list used to mean restarting the node.
	 *
	 * Hit counters start from zero again, because the rules they belong to are new objects.
	 */
	public static void reload()
	{
		BotClassifier reloaded = load();

		if (reloaded.rules.isEmpty())
		{
			// Cannot happen while the built-in default rule exists, but if it ever could: an empty
			// classifier calls every crawler a guest and takes the origin with it. Keep what works.
			logger.error("Reloading " + BOTS_CONFIG_FILE + " produced no usable rules - keeping the previous ones");
			return;
		}

		instance = reloaded;
	}

	/** for tests - installs a classifier without going through FCConfig */
	public static void install(BotClassifier classifier) {
		instance = classifier;
	}

	/** for tests - builds a classifier over an explicit rule list */
	public static BotClassifier of(List<BotRule> rules) {
		return new BotClassifier(new ArrayList<BotRule>(rules), new LinkedHashSet<String>());
	}

	/**
	 * The rules this node is running, in evaluation order, each carrying its hit count. Rendered by
	 * the console's Bot Rules screen.
	 */
	public List<BotRule> getRules() {
		return rules;
	}

	/**
	 * Bare User-Agent keywords from the file, in file order. Reported separately from the rules
	 * because a console older than 2.9.0 knows only this shape.
	 */
	public Set<String> getKeywords() {
		return keywords;
	}

	/**
	 * Classifies one request. Never throws.
	 *
	 * Only the DECIDING rule counts a hit - a rule below it is never evaluated - so the console's
	 * Hits column reads as "share of traffic decided here", and a rule shadowed by a broader one
	 * above it sits at zero, which is the most useful thing that column says. A dry-run rule counts
	 * and evaluation continues past it: measuring a rule before it acts is the whole point of
	 * dry-run.
	 *
	 * @param context current request; may carry a null or recycled servlet request
	 * @return the rule that decided, never null - ask it for {@link BotRule#getClientType()}
	 */
	public BotRule classify(RequestContext context)
	{
		List<BotRule> rulesNow = this.rules;

		for (BotRule rule : rulesNow)
		{
			boolean matched;
			try {
				matched = rule.matches(context);
			} catch (Throwable e) {
				// fail soft: a broken predicate must not decide, and must not fail the request either
				logger.error("Bot rule '" + rule.getName() + "' failed to evaluate - ignoring it for this request", e);
				continue;
			}

			if (!matched)
				continue;

			// A synthetic probe (a guard rule self-check at load time) is not traffic.
			if (!context.isGuardProbe())
				rule.countHit();

			if (rule.isDryRun())
			{
				if (logger.isDebugEnabled())
					logger.debug("Bot rule '" + rule.getName() + "' matched in dry-run - would classify as "
							+ rule.getClientType());
				continue;
			}

			return rule;
		}

		// unreachable while the built-in default rule is in the list
		return defaultRule();
	}

	private static BotClassifier load()
	{
		List<BotRule> loaded = new ArrayList<BotRule>();
		Set<String> keywords = new LinkedHashSet<String>();
		List<BotRule> keywordRules = new ArrayList<BotRule>();

		if (Boolean.parseBoolean(FCConfig.getProperty(EMPTY_USER_AGENT_IS_BOT_PROPERTY, "true")))
			loaded.add(noUserAgentRule());

		boolean rulesEnabled = Boolean.parseBoolean(FCConfig.getProperty(RULES_ENABLED_PROPERTY, "true"));

		readConfigFile(loaded, keywordRules, keywords, rulesEnabled);

		// Keywords last, so an explicit rule wins over a keyword no matter how the file is ordered.
		loaded.addAll(keywordRules);
		loaded.add(defaultRule());

		logger.info("Bot classification rules loaded: " + loaded.size() + " (" + keywords.size() + " from bare keywords)");
		for (BotRule rule : loaded)
			logger.info("   " + rule);

		BotClassifier classifier = new BotClassifier(loaded, keywords);
		classifier.warnOnClientIpWithoutTrustedProxies();
		return classifier;
	}

	private static void readConfigFile(List<BotRule> rules, List<BotRule> keywordRules, Set<String> keywords, boolean rulesEnabled)
	{
		InputStream is = null;
		try {
			// FCConfig.getConfigInputStream() signals "file absent" by THROWING, so this catches
			// Exception rather than IOException - the trap two management actions once shipped with.
			is = FCConfig.getConfigInputStream(BOTS_CONFIG_FILE);
		} catch (Throwable e) {
			logger.info("No " + BOTS_CONFIG_FILE + " found - no user agent is classified as a bot");
			return;
		}

		if (null == is)
			return;

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

				boolean isRule = 1 < org.frontcache.guard.GuardRuleParser.splitFields(trimmed).size();

				if (isRule && !rulesEnabled)
				{
					logger.info("Skipping bot rule (" + BOTS_CONFIG_FILE + ":" + lineNumber + ") - "
							+ RULES_ENABLED_PROPERTY + "=false, bare keywords only");
					continue;
				}

				try {
					BotRule rule = BotRuleParser.parse(trimmed);

					if (isRule)
						rules.add(rule);
					else
					{
						keywords.add(trimmed);
						keywordRules.add(rule);
					}
				} catch (GuardConfigException e) {
					// one bad line must not cost the whole file
					logger.error("Skipping bot rule (" + BOTS_CONFIG_FILE + ":" + lineNumber + "): " + e.getMessage());
				}
			}
		} catch (Exception e) {
			logger.error("Can't read " + BOTS_CONFIG_FILE + " - loaded " + (rules.size() + keywordRules.size())
					+ " rule(s) before the failure", e);
		} finally {
			close(reader);
			close(is);
		}
	}

	/**
	 * Dry-parses a candidate bots.conf without touching anything live, for the {@code set-config}
	 * management action.
	 *
	 * The line-kind decision is the same one {@link #readConfigFile} makes - a line with no {@code |}
	 * outside a group is a bare keyword, anything else is a rule - because a validator that classified
	 * lines differently from the loader would pass files the loader then refuses, which is worse than
	 * no validator.
	 *
	 * @return one message per unusable line, {@code "line N: ..."}, in file order; empty when the
	 *         whole file parses
	 */
	public static List<String> validate(String content)
	{
		List<String> errors = new ArrayList<String>();

		if (null == content)
			return errors;

		String[] lines = content.split("\n", -1);
		for (int i = 0; i < lines.length; i++)
		{
			String trimmed = lines[i].trim();
			if (0 == trimmed.length() || trimmed.startsWith("#"))
				continue;

			try {
				BotRuleParser.parse(trimmed);
			} catch (GuardConfigException e) {
				errors.add("line " + (i + 1) + ": " + e.getMessage());
			}
		}

		return errors;
	}

	/**
	 * Today's hard-coded {@code else} branch, made an explicit rule so it shows up in get-bots with
	 * a hit count of its own and can be switched off where a health checker or an internal client
	 * legitimately sends no User-Agent.
	 */
	static BotRule noUserAgentRule()
	{
		GuardPredicate noUserAgent = new GuardPredicate() {
			@Override
			public boolean test(RequestContext context) {
				return null == context.getUserAgent();
			}

			@Override
			public String describe() {
				return "!header:User-Agent";
			}
		};

		return new BotRule(RULE_NO_USER_AGENT, Arrays.asList(noUserAgent), FCHeaders.REQUEST_CLIENT_TYPE_BOT, false);
	}

	/**
	 * Always matches, so classify() always has an answer and the bottom row of the console's table
	 * counts the requests no rule claimed - the denominator for reading every row above it.
	 */
	static BotRule defaultRule()
	{
		GuardPredicate always = new GuardPredicate() {
			@Override
			public boolean test(RequestContext context) {
				return true;
			}

			@Override
			public String describe() {
				return "(always)";
			}
		};

		return new BotRule(RULE_DEFAULT, Arrays.asList(always), FCHeaders.REQUEST_CLIENT_TYPE_GUEST, false);
	}

	/**
	 * With no trusted proxy configured, ClientIpResolver can only see the socket peer - which behind
	 * nginx or a CDN is the proxy, one address for the whole site. A client-ip rule then matches
	 * either nothing or everything. Warn rather than refuse: the stance everywhere in this pipeline
	 * is that a misconfiguration degrades to "Frontcache as usual", never to an outage.
	 */
	private void warnOnClientIpWithoutTrustedProxies()
	{
		if (ClientIpResolver.getInstance().hasTrustedProxies())
			return;

		for (BotRule rule : rules)
			for (GuardPredicate predicate : rule.getPredicates())
				if (predicate.describe().contains("client-ip:"))
				{
					logger.warn("Bot rule '" + rule.getName() + "' matches on the client IP, but "
							+ ClientIpResolver.TRUSTED_PROXIES_PROPERTY + " is empty, so only the socket peer is"
							+ " visible and every forwarding header is ignored. That is correct only if clients"
							+ " connect to this node DIRECTLY - behind nginx/a CDN the rule sees the proxy's"
							+ " address for every request. Set " + ClientIpResolver.TRUSTED_PROXIES_PROPERTY + ".");
					return;
				}
	}

	private static void close(java.io.Closeable closeable)
	{
		if (null == closeable)
			return;

		try {
			closeable.close();
		} catch (Exception e) { /* ignored */ }
	}

}
