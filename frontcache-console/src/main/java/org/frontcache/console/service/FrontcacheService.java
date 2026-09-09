/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.EdgeConfigFile;
import org.frontcache.console.model.VisitorTypesConfig;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.model.GuardRulesConfig;
import org.frontcache.console.model.DashboardStream;
import org.frontcache.core.WebResponse;
import org.frontcache.guard.GuardRuleEntry;
import org.frontcache.resilience.fr.FallbackConfigEntry;
import org.frontcache.io.BackupActionResponse;
import org.frontcache.io.ConfigFile;
import org.frontcache.io.SetConfigActionResponse;
import org.frontcache.io.DumpKeysActionResponse;
import org.frontcache.replication.CacheReplicator;
import org.frontcache.replication.ReplicationProgressListener;
import org.frontcache.replication.ReplicationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;

@Service("frontcacheService")
public class FrontcacheService {

	private Config fcConsoleConfig = null;

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);

	private ObjectMapper jsonMapper = new ObjectMapper();

	private Set<String> frontcacheAgentURLs = new LinkedHashSet<String>();

	/**
	 * Canonical edge URL -> the cluster it belongs to, from {@code frontcache.console.clusters}.
	 *
	 * Empty on every config file written before that key existed, which is what makes the realtime
	 * page render exactly as it did: no cluster, no aggregate panel. See
	 * docs/circuit-cluster-proposal.md section 4.
	 */
	private Map<String, String> clusterByEdgeURL = new LinkedHashMap<String, String>();

	private String apiKey = "default-api-key";

	/**
	 * The console's single login, from conf/frontcache-console.conf. Both null = authentication is
	 * off, which is what a config file written before this feature existed produces - see
	 * {@link #readCredentials}.
	 */
	private String user = null;
	private String password = null;

	/** The password the distribution ships with. Present = warn, loudly, on every startup. */
	private static final String SHIPPED_DEFAULT_PASSWORD = "admin";

	private static final int THREAD_AMOUNT = 4;

	private ExecutorService executor = Executors.newFixedThreadPool(THREAD_AMOUNT);

	private static final long FRONTCACHE_CLIENT_TIMEOUT = FrontCacheClient.CONNECTION_TIMEOUT + 1000; // slightly more then frontcache client timeout
	private static final long CONSOLE_TIMEOUT = 1000;


	public FrontcacheService() {
		loadConfigs();

		// Deliberately outside loadConfigs(): that method returns early when there is no config path
		// and again when the file is not there, and a console in either of those states is
		// unauthenticated - which is exactly the state that has to be reported. Reporting it from
		// inside would report it on the one path where credentials could have been read.
		logAuthState();
	}

	private void loadConfigs() {

		String frontcacheConsoleConfPath = System.getenv("FRONTCACHE_CONFIG");

		if (null == frontcacheConsoleConfPath)
		{
			// backward compatibility
			frontcacheConsoleConfPath = System.getProperty("org.frontcache.console.config");
		}

		if (null == frontcacheConsoleConfPath)
		{
			logger.info("Neither environment variable 'FRONTCACHE_CONFIG' nor system property 'org.frontcache.console.config' is defined");
			return;
		}

		try
		{
			File configFile = new File(frontcacheConsoleConfPath);
			if (!configFile.exists())
			{
				logger.info("Console config file doesn't exist: " + configFile.getAbsolutePath());
				return;
			}

			fcConsoleConfig = ConfigFactory.parseFile(configFile).getConfig("frontcache").getConfig("console");

		} catch (Exception e) {
			logger.error("Console frontcacheAgentURLs are not loaded from " + frontcacheConsoleConfPath);
			throw new RuntimeException("Can't initialize Frontcache Console", e);
		}

		List<ConfigValue> urls = fcConsoleConfig.getList("urls");
		for (ConfigValue urlValue : urls) {
			String url = urlValue.unwrapped().toString();
			logger.info(" -- Loading url {} from console config", url);
			frontcacheAgentURLs.add(url);
		}
		apiKey = fcConsoleConfig.getString("apiKey");
		logger.info(" -- Loading apiKey {} from console config", apiKey);

		clusterByEdgeURL = parseClusters(fcConsoleConfig, frontcacheAgentURLs);
		for (Map.Entry<String, String> member : clusterByEdgeURL.entrySet())
			logger.info(" -- Edge {} is in cluster '{}'", member.getKey(), member.getValue());

		readCredentials();
	}

	/**
	 * Reads the console's single login - see docs/archive/basic-auth-proposal.md.
	 *
	 * <b>Every key here is read through hasPath first.</b> {@code Config.getString} throws on a
	 * missing key, so reading these the way apiKey is read would make every console whose config
	 * predates this feature fail to start - which is precisely what a config still saying
	 * {@code siteKey} does after the 2.9.0 api-key rename.
	 *
	 * <b>Absent credentials mean authentication is OFF, and say so at WARN.</b> Not info: the
	 * shipped conf/fc-logback.xml puts a ThresholdFilter at WARN on stdout, so an info line is
	 * dropped and the operator of an unprotected console would be told nothing. That silence - not
	 * the permissive default - is what made the api-key rename a hazard rather than a metrics
	 * change.
	 *
	 * Half-configured is the one case that refuses to start. It cannot arise from an un-migrated
	 * file, only from a typo, and resolving a typo to "unprotected" is the worst of the options.
	 */
	private void readCredentials() {

		Credentials credentials = parseCredentials(fcConsoleConfig);

		if (null != credentials)
		{
			user = credentials.user();
			password = credentials.password();
			return;
		}

		if (fcConsoleConfig.hasPath("auth-required") && fcConsoleConfig.getBoolean("auth-required"))
			throw new IllegalStateException("frontcache-console.conf: auth-required = true, but "
					+ "'user'/'password' are not set");
	}

	/** The console's single login, or null when the file configures none. */
	record Credentials(String user, String password) { }

	/**
	 * The parsing half of {@link #readCredentials}, split out so it can be tested without a config
	 * file, an environment variable or a thread pool.
	 *
	 * @return the credentials, or null when the file has neither key - which is every config written
	 *         before this feature existed, and is not an error
	 * @throws IllegalStateException when only one of the two is set, or either is empty
	 */
	static Credentials parseCredentials(Config config) {

		boolean hasUser = config.hasPath("user");
		boolean hasPassword = config.hasPath("password");

		if (hasUser != hasPassword)
			throw new IllegalStateException("frontcache-console.conf: '"
					+ (hasUser ? "user' is set but 'password'" : "password' is set but 'user'")
					+ " is missing (set both to enable authentication, or neither to disable it)");

		if (!hasUser)
			return null;

		String configuredUser = config.getString("user").trim();
		String configuredPassword = config.getString("password");

		// An empty string is a half-finished edit, not a credential. Accepting it would let anyone
		// sending an empty password in.
		if (configuredUser.isEmpty() || configuredPassword.isEmpty())
			throw new IllegalStateException("frontcache-console.conf: 'user' and 'password' must not "
					+ "be empty (remove both to disable authentication)");

		return new Credentials(configuredUser, configuredPassword);
	}

	/**
	 * Says, once at startup, whether this console is protected.
	 *
	 * The protected-and-changed case logs at info, which the shipped fc-logback.xml does not print:
	 * a healthy console is silent, and any line about authentication means something needs doing.
	 * A console that warned on every start would train its operator to ignore the warning.
	 */
	private void logAuthState() {

		if (isAuthEnabled())
		{
			logger.info(" -- Console authentication is ON for user '{}'", user);
			logger.info(" -- Config editing is {}", isConfigEditingEnabled() ? "ON" : "OFF (config-editing = false)");

			if (SHIPPED_DEFAULT_PASSWORD.equals(password))
				logger.warn("Console authentication is ON, but the password is still the shipped "
						+ "default. Change 'password' in frontcache-console.conf."
						+ (isConfigEditingEnabled()
							? " Anyone with it can rewrite the config files of every configured node."
							: ""));

			return;
		}

		logger.warn("Console authentication is OFF - no 'user'/'password' in frontcache-console.conf."
				+ " Anyone who can reach this port can invalidate the cache of every configured node."
				+ " Add:   user = admin / password = <choose one>");
		logger.warn("Config editing is therefore OFF too - the Configs screens stay read-only. An"
				+ " unauthenticated console is a monitoring tool; it is not a fleet configuration tool.");
	}

	/** True when a login is configured. False = every request is served without a check. */
	public boolean isAuthEnabled() {
		return null != user && null != password;
	}

	public String getUser() {
		return user;
	}

	public String getPassword() {
		return password;
	}

	public String getApiKey(){
		return apiKey;
	}

	public boolean isFrontCacheEdgeAvailable(String frontcacheURL)
	{
		boolean available = false;
		FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL, apiKey);
		if (null != fcClient.getCacheState()) // if get state work -> FC is active
			available = true;

		return available;
	}


	/**
	 *
	 * @return
	 */
	public Map<String, FrontCacheStatus> getClusterStatus() {

        List<Future<FrontCacheStatus>> futureList = new ArrayList<Future<FrontCacheStatus>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order).
		Map<String, FrontCacheStatus> clusterStatus = new LinkedHashMap<String, FrontCacheStatus>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new FrontCacheStatusCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<FrontCacheStatus> f : futureList)
        {
            try {
            	FrontCacheStatus result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result);

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some statuses may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	/**
	 * Cluster statuses as a list in console-config edge order - the order used
	 * by the "Edges status" block across the console pages.
	 */
	public List<FrontCacheStatus> getClusterStatusList() {
		return new ArrayList<FrontCacheStatus>(getClusterStatus().values());
	}

	/**
	 * The configured edges, with no numbers and NO NETWORK CALLS - just what the console already knows
	 * from its own config: each edge's name and URL.
	 *
	 * This is what a page renders its "Edges status" rows from. Filling those rows used to mean a
	 * fan-out over the whole fleet before the first byte of HTML, so one unreachable edge held the
	 * page for {@link #FRONTCACHE_CLIENT_TIMEOUT} - on the realtime page, that is a monitoring
	 * dashboard that will not paint because something it monitors is down. The numbers now arrive from
	 * /edges-status.json a moment later, which is the one place that talks to the fleet.
	 *
	 * {@code available} is deliberately left false here and is not rendered as "offline": until the
	 * first poll answers, this console does not know, and a red dot on a healthy node is a worse
	 * answer than an honest "checking".
	 */
	public List<FrontCacheStatus> getEdgeList() {

		List<FrontCacheStatus> edges = new ArrayList<FrontCacheStatus>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
		{
			FrontCacheStatus edge = new FrontCacheStatus();
			edge.setName(fcClient.getName());
			edge.setUrl(fcClient.getFrontCacheURL());
			edge.setCachedAmount(-1);
			edges.add(edge);
		}

		return edges;
	}

	// ---- the polled view ---------------------------------------------------------------------

	/**
	 * How long a fan-out is reused for {@code /edges-status.json}.
	 *
	 * Deliberately a little under the page's poll interval (10s, see fc-edges-status.js) rather than
	 * equal to it: the browser cadence and the fleet cadence must not be one knob. Without this, ten
	 * open dashboards mean ten times the requests to every node, forever - the console would be the
	 * heaviest client its own fleet has.
	 */
	private static final long STATUS_CACHE_MILLIS = 8000;

	private volatile List<FrontCacheStatus> cachedClusterStatus = null;
	private volatile long cachedClusterStatusStamp = 0;
	private final Object clusterStatusLock = new Object();

	/**
	 * The same list {@link #getClusterStatusList()} returns, but reused across callers for a few
	 * seconds. This is what the realtime page polls.
	 *
	 * Page loads deliberately keep using the uncached call: an operator who has just invalidated a
	 * cache and reloaded expects to see it, and one fan-out per page load was never the problem this
	 * cache exists to solve.
	 */
	public List<FrontCacheStatus> getClusterStatusListPolled() {

		long now = System.currentTimeMillis();
		List<FrontCacheStatus> current = cachedClusterStatus;
		if (null != current && now - cachedClusterStatusStamp < STATUS_CACHE_MILLIS)
			return current;

		// One fan-out at a time. Without the lock, every poll that arrives while one is in flight
		// starts its own - which is precisely the multiplication the cache is here to prevent, just
		// moved from "many tabs" to "one slow fleet".
		synchronized (clusterStatusLock) {

			now = System.currentTimeMillis();
			current = cachedClusterStatus;
			if (null != current && now - cachedClusterStatusStamp < STATUS_CACHE_MILLIS)
				return current;

			List<FrontCacheStatus> fresh = getClusterStatusList();
			cachedClusterStatus = fresh;
			cachedClusterStatusStamp = System.currentTimeMillis();

			return fresh;
		}
	}


	/**
	 * How each edge classifies a visitor - the ordered rules and the bare keywords - for the Visitor
	 * Types screen.
	 *
	 * One entry per reachable edge. The value carries both shapes - rules (2.9.0+) and bare keywords -
	 * because the console reads a mixed fleet and the JSP renders whichever the edge answered with.
	 * The raw file behind this is what {@link #getConfigFiles} returns for ConfigFile.BOTS.
	 */
	public Map<String, VisitorTypesConfig> getVisitorTypes() {

        List<Future<VisitorTypesConfig>> futureList = new ArrayList<Future<VisitorTypesConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures below are submitted/consumed in that same order) so the UI
		// tabs appear in the configured order.
		Map<String, VisitorTypesConfig> clusterStatus = new LinkedHashMap<String, VisitorTypesConfig>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new VisitorTypesCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<VisitorTypesConfig> f : futureList)
        {
            try {
            	VisitorTypesConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		clusterStatus.put(result.getName(), result);

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	/**
	 * Guard rules per edge, in evaluation order, with hit counts.
	 *
	 * Read-only: rules are edited in FRONTCACHE_HOME/conf/guard-rules.conf and applied with the
	 * reload-guard-rules action - same story as the other config screens.
	 */
	public Map<String, List<GuardRuleEntry>> getGuardRules() {

        List<Future<GuardRulesConfig>> futureList = new ArrayList<Future<GuardRulesConfig>>();

		// LinkedHashMap preserves the edge order defined in the console config
		Map<String, List<GuardRuleEntry>> clusterStatus = new LinkedHashMap<String, List<GuardRuleEntry>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new GuardRulesCaller(fcClient)));

		// processing timeouts
        boolean timeoutReached = false;
        for (Future<GuardRulesConfig> f : futureList)
        {
            try {
            	GuardRulesConfig result = null;
            	if (timeoutReached)
            		result = f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result && null != result.getConfig())
            		clusterStatus.put(result.getName(), result.getConfig());

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some guard rules may not be retrieved ");
            }
        }

		return clusterStatus;
	}

	/**
	 * One raw config file, from every configured edge, for a Configs screen.
	 *
	 * Replaces five identical fan-outs - {@code getFallbackConfigs}, {@code getBotConfigs},
	 * {@code getDynamicURLsConfigs}, {@code getGuardConfigs}, {@code getResilienceConfigs} - which
	 * differed only in the action they sent.
	 *
	 * <b>A List, not a Map keyed by edge name.</b> That name is not unique (see
	 * {@link EdgeConfigFile}), so the map silently dropped a tab whenever two edges shared a host.
	 * The list is in the console config's edge order, which is the order every other screen uses.
	 *
	 * An edge that times out keeps its tab and is marked unreachable, rather than vanishing from the
	 * page - a missing tab reads as "this node has no such file", which is a different thing.
	 */
	public List<EdgeConfigFile> getConfigFiles(ConfigFile configFile) {

		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		List<Future<EdgeConfigFile>> futureList = new ArrayList<Future<EdgeConfigFile>>();
		for (FrontCacheClient fcClient : fcClients)
			futureList.add(executor.submit(new ConfigFileCaller(fcClient, configFile)));

		List<EdgeConfigFile> result = new ArrayList<EdgeConfigFile>();

		// processing timeouts
		boolean timeoutReached = false;
		for (int i = 0; i < futureList.size(); i++)
		{
			Future<EdgeConfigFile> f = futureList.get(i);
			FrontCacheClient fcClient = fcClients.get(i);

			try {
				EdgeConfigFile edgeConfig = timeoutReached
						? f.get(CONSOLE_TIMEOUT, TimeUnit.MILLISECONDS)
						: f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

				result.add((null != edgeConfig) ? edgeConfig
						: EdgeConfigFile.unreachable(fcClient.getName(), fcClient.getFrontCacheURL()));

			} catch (TimeoutException | InterruptedException | ExecutionException e) {
				f.cancel(true);
				timeoutReached = true;
				logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached for " + fcClient.getFrontCacheURL());
				result.add(EdgeConfigFile.unreachable(fcClient.getName(), fcClient.getFrontCacheURL()));
			}
		}

		return result;
	}

	/**
	 * Whether the Configs screens offer an Edit button.
	 *
	 * <b>Off when the console has no credentials, whatever the property says.</b> An unauthenticated
	 * console is a monitoring tool anyone who can reach it can read; it must not also be a fleet
	 * configuration tool. That is the mirror of the node's own rule - {@code ApiKey.isAuthorizedToWrite}
	 * refuses a write on a node with no API key - and for the same reason: neither permissive default
	 * was chosen, both are what an un-migrated config file produces.
	 */
	public boolean isConfigEditingEnabled() {
		return configEditingEnabled(fcConsoleConfig, isAuthEnabled());
	}

	/**
	 * The decision half of {@link #isConfigEditingEnabled()}, split out so it can be tested without a
	 * config file, an environment variable or a thread pool - the same arrangement as
	 * {@link #parseCredentials}.
	 *
	 * Read through {@code hasPath}, like the credentials are: {@code Config.getString} and friends throw
	 * on a missing key, so reading this the way {@code apiKey} is read would make every console whose
	 * config predates the feature fail to start.
	 */
	static boolean configEditingEnabled(Config config, boolean authEnabled) {

		if (!authEnabled)
			return false;

		if (null != config && config.hasPath(CONFIG_EDITING_KEY))
			return config.getBoolean(CONFIG_EDITING_KEY);

		return true;
	}

	/** HOCON key under frontcache.console - read through hasPath, like the credentials are. */
	private static final String CONFIG_EDITING_KEY = "config-editing";

	/**
	 * Whether the session cookie carries the {@code Secure} attribute - see
	 * docs/archive/console-login-ui.md section 4.4.
	 *
	 * <b>A config key rather than a detection, and it has to be.</b> The attribute is set once per
	 * servlet context at startup, while whether the browser used HTTPS is a per-request fact
	 * ({@code X-Forwarded-Proto}) on exactly the deployments careful enough to put TLS in front -
	 * the same split {@code SameOriginFilter} documents. Defaulting it ON would mean the cookie is
	 * never sent on the plain-HTTP internal consoles that are the common case, and the symptom is a
	 * login page that accepts the password and returns to the login page.
	 */
	public boolean isSessionCookieSecure() {
		return sessionCookieSecure(fcConsoleConfig);
	}

	/** The reading half of {@link #isSessionCookieSecure()}, testable without a config file. */
	static boolean sessionCookieSecure(Config config) {

		if (null != config && config.hasPath(SESSION_COOKIE_SECURE_KEY))
			return config.getBoolean(SESSION_COOKIE_SECURE_KEY);

		return false;
	}

	/** HOCON key under frontcache.console - read through hasPath, like the credentials are. */
	private static final String SESSION_COOKIE_SECURE_KEY = "session-cookie-secure";

	// ---- clusters ------------------------------------------------------------------------------

	/** HOCON key under frontcache.console - read through hasPath, like the credentials are. */
	private static final String CLUSTERS_KEY = "clusters";

	/**
	 * An edge URL as it is compared, keyed and handed to the page.
	 *
	 * <b>The trailing slash is the whole reason this exists.</b> {@link #getDashboardStreams} appends
	 * one, so the stream list carries {@code https://fc-edge.example.com:443/} while {@code urls} - and
	 * therefore anything an operator copies out of it into {@code clusters} - says
	 * {@code https://fc-edge.example.com:443}. Comparing the two raw forms makes every cluster come out
	 * empty on a config file that reads perfectly correctly, with nothing to say so.
	 *
	 * Matching is on the URL and never on {@link FrontCacheClient#getName()}: that name strips the
	 * scheme and everything from the first colon, so {@code localhost:8080} and {@code localhost:9080}
	 * - the e2e topology - are the same string. See {@code EdgeConfigFile}, where keying a fan-out by
	 * it used to drop a tab.
	 */
	static String canonicalEdgeURL(String edgeURL) {

		if (null == edgeURL)
			return null;

		String canonical = edgeURL.trim();
		if (canonical.isEmpty())
			return canonical;

		return canonical.endsWith("/") ? canonical : canonical + "/";
	}

	/**
	 * {@code clusters { "Edge layer" = [url, url, url] }} -> canonical edge URL -> cluster name.
	 *
	 * Read through {@code hasPath}, like every other key added after the first release: absent means
	 * no clusters, which is every existing config file and is not an error.
	 *
	 * <b>Nothing here refuses to start.</b> The console is a monitoring tool and an upgrade must not
	 * take it offline, so each way of getting this wrong is a WARN and a documented resolution - warn,
	 * not info, because the shipped fc-logback.xml filters stdout at WARN and an info line would leave
	 * the operator of a silently-empty cluster with nothing at all:
	 *
	 * <ul>
	 * <li>a member that is not in {@code urls} is kept in the map but never rendered, since the page
	 *     only groups edges the console actually lists - so it also picks up its cluster if it is added
	 *     later on the Edges page;</li>
	 * <li>a URL named by two clusters keeps the FIRST, the same resolution a duplicate client type in a
	 *     {@code maxage} takes;</li>
	 * <li>a cluster whose value is not a list, or whose name is blank, is skipped whole.</li>
	 * </ul>
	 *
	 * A cluster that ends up with fewer than two live members is not decided here - it depends on the
	 * edge list, which changes at runtime. {@link #getDashboardStreams} makes that call.
	 *
	 * @param knownEdgeURLs the configured edges, for the "not in urls" warning only
	 */
	static Map<String, String> parseClusters(Config config, Collection<String> knownEdgeURLs) {

		Map<String, String> clusters = new LinkedHashMap<String, String>();

		if (null == config || !config.hasPath(CLUSTERS_KEY))
			return clusters;

		Set<String> known = new LinkedHashSet<String>();
		if (null != knownEdgeURLs)
			for (String edgeURL : knownEdgeURLs)
				known.add(canonicalEdgeURL(edgeURL));

		ConfigObject configured;
		try {
			configured = config.getObject(CLUSTERS_KEY);
		} catch (ConfigException e) {
			logger.warn("frontcache-console.conf: 'clusters' is not a block of <name> = [url, ...] - "
					+ "no clusters are configured ({})", e.getMessage());
			return clusters;
		}

		for (Map.Entry<String, ConfigValue> entry : configured.entrySet())
		{
			String clusterName = null == entry.getKey() ? "" : entry.getKey().trim();

			if (clusterName.isEmpty())
			{
				logger.warn("frontcache-console.conf: a cluster with a blank name is skipped");
				continue;
			}

			Object value = entry.getValue().unwrapped();
			if (!(value instanceof List))
			{
				logger.warn("frontcache-console.conf: cluster '{}' is not a list of edge urls - skipped",
						clusterName);
				continue;
			}

			for (Object member : (List<?>) value)
			{
				String memberURL = canonicalEdgeURL(null == member ? null : member.toString());

				if (null == memberURL || memberURL.isEmpty())
				{
					logger.warn("frontcache-console.conf: cluster '{}' names an empty edge url - skipped",
							clusterName);
					continue;
				}

				String claimedBy = clusters.get(memberURL);
				if (null != claimedBy)
				{
					logger.warn("frontcache-console.conf: edge {} is in both cluster '{}' and cluster "
							+ "'{}' - keeping '{}'", memberURL, claimedBy, clusterName, claimedBy);
					continue;
				}

				if (!known.contains(memberURL))
					logger.warn("frontcache-console.conf: cluster '{}' names {}, which is not in 'urls'"
							+ " - it is ignored unless that edge is added on the Edges page",
							clusterName, memberURL);

				clusters.put(memberURL, clusterName);
			}
		}

		return clusters;
	}

	/**
	 * Writes one config file to one edge and applies it.
	 *
	 * Addressed by edge URL, never by name - see {@link EdgeConfigFile}.
	 *
	 * @param baseHash sha-256 of the content being replaced, or null/blank to skip the concurrency
	 *                 check (the browser cannot always compute one - see fc-config-editor.js)
	 * @return the node's answer, or a synthetic refusal when the node could not be reached at all
	 */
	public SetConfigActionResponse saveConfigFile(String edgeURL, ConfigFile configFile, String content, String baseHash) {

		if (!isConfigEditingEnabled())
		{
			SetConfigActionResponse refused = new SetConfigActionResponse();
			refused.setFile(configFile.getFileName());
			refused.refuse("config editing is disabled on this console");
			return refused;
		}

		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, apiKey);
		SetConfigActionResponse response = fcClient.setConfig(configFile, content, baseHash, true);

		if (null == response)
		{
			// null means the call itself failed. The screen has to say something per edge, so this
			// becomes a refusal rather than a blank - "nothing happened" and "we don't know" would
			// otherwise look identical.
			SetConfigActionResponse unreachable = new SetConfigActionResponse();
			unreachable.setFile(configFile.getFileName());
			unreachable.refuse("edge " + edgeURL + " is not available");
			return unreachable;
		}

		return response;
	}

	/**
	 * Writes one config file to every configured edge.
	 *
	 * <b>Not all-or-nothing, and it must not pretend to be.</b> Each node is a separate HTTP call to a
	 * separate machine, so a partial result is the normal outcome - the answer is therefore per edge,
	 * in edge order, and the screen shows every line.
	 *
	 * Sequential, not fanned out: this writes config to production nodes, and doing them one at a time
	 * means a file that breaks something breaks it on one node while the operator is still watching,
	 * rather than on all of them at once. The console's own latency is not the thing to optimize here.
	 *
	 * @param baseHash the hash of the content as the console rendered it, which is meaningful only for
	 *                 the edge it was read from - see the note in the caller
	 */
	public Map<String, SetConfigActionResponse> saveConfigFileToAllEdges(ConfigFile configFile, String content, String baseHash, String baseHashEdgeURL) {

		Map<String, SetConfigActionResponse> results = new LinkedHashMap<String, SetConfigActionResponse>();

		for (String edgeURL : frontcacheAgentURLs)
		{
			// The hash guards the file the operator was LOOKING at. Sending it to the other nodes would
			// refuse every one of them whose file differs - which is most of the reason a fleet has more
			// than one node's config in the first place - so those are written unchecked, and the screen
			// says so.
			String hashForEdge = edgeURL.equals(baseHashEdgeURL) ? baseHash : null;

			results.put(edgeURL, saveConfigFile(edgeURL, configFile, content, hashForEdge));
		}

		return results;
	}

	/**
	 * The edges the realtime page subscribes to, as JSON.
	 *
	 * Only names, edge URLs and cluster names - the page hands an edge URL back to
	 * {@code DashboardStreamProxyServlet}, which is where the stream path and the API key live.
	 *
	 * Membership is resolved HERE rather than at startup, because the edge list changes at runtime
	 * from the Edges page. Which also settles the one-member case: a cluster the current edge list
	 * leaves with fewer than two members is reported as no cluster at all, since its "Cluster req / s"
	 * line would repeat the Host line above it - the very thing this feature exists to stop. See
	 * docs/circuit-cluster-proposal.md section 4.3.
	 */
	public String getDashboardStreams()
	{
		// [{"name":"My Super App","url":"http://sg.example.com/","cluster":"Edge layer"}, ... ]

		List<DashboardStream> streams = new ArrayList<DashboardStream>();

		Map<String, Integer> membersPerCluster = new LinkedHashMap<String, Integer>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
		{
			String fcURL = canonicalEdgeURL(fcClient.getFrontCacheURL());

			String cluster = clusterByEdgeURL.get(fcURL);
			if (null != cluster)
			{
				Integer members = membersPerCluster.get(cluster);
				membersPerCluster.put(cluster, null == members ? 1 : members.intValue() + 1);
			}

			streams.add(new DashboardStream(fcClient.getName(), fcURL, cluster));
		}

		for (DashboardStream stream : streams)
		{
			String cluster = stream.getCluster();
			if (null == cluster)
				continue;

			Integer members = membersPerCluster.get(cluster);
			if (null != members && members.intValue() < 2)
			{
				logger.info(" -- Cluster '{}' has one configured edge in this console's edge list, so its"
						+ " members show no cluster rate", cluster);
				stream.setCluster(null);
			}
		}

		try {
			return jsonMapper.writeValueAsString(streams);
		} catch (JsonProcessingException e) {
			logger.error("Can't serialize the dashboard stream list", e);
			return "[]";
		}
	}


	public Set<String> getFrontCacheAgentURLs()
	{
		Set<String> urls = new LinkedHashSet<String>();
		urls.addAll(frontcacheAgentURLs);

		return urls;
	}

	public WebResponse getFromCache(String edgeURL, String key)
	{
		if (null == key)
			return null;

		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, apiKey);
		WebResponse webResponse = fcClient.getFromCache(key.trim());

		return webResponse;
	}


	private List<FrontCacheClient> getFrontCacheAgents()
	{
		return getFrontCacheAgents(false);
	}

	private List<FrontCacheClient> getFrontCacheAgents(boolean activeOnly)
	{
		List<FrontCacheClient> fcClients = new ArrayList<FrontCacheClient>();
		for (String frontcacheURL : frontcacheAgentURLs)
		{
			FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL, apiKey);

			if (activeOnly)
			{
				if (null != fcClient.getCacheState()) // if get state work -> FC is active
					fcClients.add(fcClient);
			} else {
				fcClients.add(fcClient);
			}
		}

		return fcClients;
	}

	public int getEdgesAmount() {
		return frontcacheAgentURLs.size();
	}

	public void addEdge(String edge) {
		if (null != edge)
			frontcacheAgentURLs.add(edge);
	}

	public void removeEdge(String edge) {
		if (null != edge)
			frontcacheAgentURLs.remove(edge);
	}

	public void invalidateEdge(String edgeURL, String filter) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, apiKey);
		fcClient.removeFromCache(filter);
	}

	/**
	 * Triggers purge of expired entries on the given edge.
	 *
	 * @param edgeURL
	 */
	public void purgeEdge(String edgeURL) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, apiKey);
		fcClient.purge();
	}

	/**
	 * Triggers FrontcacheAction.DUMP_KEYS on the given edge - cached keys are
	 * dumped to a file in the edge's ./warmer dir.
	 *
	 * @param edgeURL
	 * @return status message returned by the edge, or an error message if the edge is not available
	 */
	public String dumpKeys(String edgeURL) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, apiKey);
		DumpKeysActionResponse response = fcClient.dumpKeys();
		if (null == response)
			return "ERROR - edge " + edgeURL + " is not available";

		return response.getAction();
	}

	/**
	 * Triggers a backup of the on-disk (L2) index on the given edge. The backup is stored
	 * in a sibling directory of the index dir at the edge (l2-lucene-index.backup-{yyyyMMdd_HHmmss}).
	 *
	 * @param edgeURL
	 * @return status message returned by the edge, or an error message if the edge is not available
	 */
	public String backupEdge(String edgeURL) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, apiKey);
		BackupActionResponse response = fcClient.backup();
		if (null == response)
			return "ERROR - edge " + edgeURL + " is not available";

		return response.getAction();
	}

	// --- Cache Replication ---

	private ExecutorService replicationExecutor = Executors.newSingleThreadExecutor();
	private volatile CacheReplicator activeReplicator = null;

	/**
	 * Starts a cache replication from source edge to target edge.
	 * Runs in a background thread. Only one replication can be active at a time.
	 *
	 * @param sourceUrl the source edge URL
	 * @param targetUrl the target edge URL
	 * @return the initial ReplicationResult (status RUNNING)
	 * @throws IllegalStateException if a replication is already in progress
	 */
	public ReplicationResult startReplication(String sourceUrl, String targetUrl) {
		if (activeReplicator != null && activeReplicator.isRunning()) {
			throw new IllegalStateException("Replication already in progress from "
					+ activeReplicator.getCurrentResult().getSourceUrl()
					+ " to " + activeReplicator.getCurrentResult().getTargetUrl());
		}

		FrontCacheClient source = new FrontCacheClient(sourceUrl, apiKey);
		FrontCacheClient target = new FrontCacheClient(targetUrl, apiKey);

		CacheReplicator replicator = new CacheReplicator(source, target);
		activeReplicator = replicator;

		replicationExecutor.submit(new Runnable() {
			@Override
			public void run() {
				try {
					replicator.run(new ReplicationProgressListener() {
						@Override
						public void onProgress(long transferred, long skipped, long errors, long totalRemaining) {
							logger.debug("Replication progress: transferred={}, skipped={}, errors={}, remaining={}",
									transferred, skipped, errors, totalRemaining);
						}

						@Override
						public void onComplete(ReplicationResult result) {
							logger.info("Replication completed: {}", result);
						}

						@Override
						public void onError(String key, Exception e) {
							logger.warn("Replication error for key {}: {}", key, e.getMessage());
						}
					});
				} finally {
					activeReplicator = null;
				}
			}
		});

		return replicator.getCurrentResult();
	}

	/**
	 * Returns the current replication status, or null if no replication is active.
	 */
	public ReplicationResult getReplicationStatus() {
		CacheReplicator r = activeReplicator;
		return r != null ? r.getCurrentResult() : null;
	}

	/**
	 * Requests cancellation of the running replication.
	 * No-op if no replication is active.
	 */
	public void cancelReplication() {
		CacheReplicator r = activeReplicator;
		if (r != null) {
			r.cancel();
		}
	}

}
