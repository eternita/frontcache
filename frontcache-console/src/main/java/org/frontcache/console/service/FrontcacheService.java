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
package org.frontcache.console.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.auth.EdgeScope;
import org.frontcache.console.model.EdgeConfigFile;
import org.frontcache.console.model.VisitorTypesConfig;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.model.GuardRulesConfig;
import org.frontcache.console.model.DashboardStream;
import org.frontcache.console.plugin.ConsolePlugins;
import org.frontcache.core.WebResponse;
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

	/** The frontcache-console.conf this console was started with, or null - the usage log's default home. */
	private File consoleConfigFile = null;

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);

	private ObjectMapper jsonMapper = new ObjectMapper();

	/**
	 * The configured edges, in config order. Copy-on-write: the Edges screen adds and removes while requests read
	 * it through {@link ConfiguredEdgeScope}.
	 */
	private final Set<String> frontcacheAgentURLs = new CopyOnWriteArraySet<String>();

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
			consoleConfigFile = configFile.getAbsoluteFile();

		} catch (Exception e) {
			logger.error("Console frontcacheAgentURLs are not loaded from " + frontcacheConsoleConfPath);
			throw new RuntimeException("Can't initialize Frontcache Console", e);
		}

		// Through hasPath since 2.12.0: a console whose edges a plugin's scope decides (a multi-tenant console's
		// tenants, each site with its own key) has no urls and no console-wide key - and must not need one.
		if (fcConsoleConfig.hasPath("urls"))
		{
			List<ConfigValue> urls = fcConsoleConfig.getList("urls");
			for (ConfigValue urlValue : urls) {
				String url = urlValue.unwrapped().toString();
				logger.info(" -- Loading url {} from console config", url);
				frontcacheAgentURLs.add(url);
			}
		}
		if (fcConsoleConfig.hasPath("apiKey"))
		{
			apiKey = fcConsoleConfig.getString("apiKey");
			// never the key itself: a log is read by more people than the config file
			logger.info(" -- Loading apiKey from console config");
		}

		readCredentials();

		// a key only a console plugin reads, set on a console without that plugin, warns rather than
		// being silently ignored
		ConsolePlugins.warnUnclaimedKeys(fcConsoleConfig);
	}

	/**
	 * Reads the console's single login.
	 *
	 * <b>Every key here is read through hasPath first.</b> {@code Config.getString} throws on a
	 * missing key, so reading these without it (as apiKey was, before 2.12.0) would make every console whose config
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

		if (hasUsersOrSso(fcConsoleConfig))
			return; // a console plugin signs in; ConsoleSecurity refuses to start when none does

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

		if (isAuthEnabled() && null == user)
		{
			logger.info(" -- Console authentication is ON, by a console plugin ({} block)",
					fcConsoleConfig.hasPath("sso") ? "sso" : "users");
			logger.info(" -- Config editing is {}", isConfigEditingEnabled() ? "ON" : "OFF (config-editing = false)");
			return;
		}

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
		// a console plugin's write controls say so themselves (the Pro plugin's WarmerService)
		logger.warn("Config editing is therefore OFF too - the Configs screens stay read-only. An"
				+ " unauthenticated console is a monitoring tool; it is not a fleet configuration tool.");
	}

	/**
	 * True when a request must be signed in: a login is configured, or - as of 2.12.0 - a {@code users} or
	 * {@code sso} block, which a console plugin's identity provider reads. False = every request is served without
	 * a check.
	 */
	public boolean isAuthEnabled() {
		return (null != user && null != password) || hasUsersOrSso(fcConsoleConfig);
	}

	/** A {@code users} or {@code sso} block - read through hasPath, like the credentials are. */
	static boolean hasUsersOrSso(Config config) {
		return null != config && (config.hasPath("users") || config.hasPath("sso"));
	}

	public String getUser() {
		return user;
	}

	public String getPassword() {
		return password;
	}

	/** The configured edges, as configured - for {@link ConfiguredEdgeScope} only: requests reach edges through a scope. */
	List<String> configuredEdgeURLs() {
		return new ArrayList<String>(frontcacheAgentURLs);
	}

	/** The configured API key - for {@link ConfiguredEdgeScope} only. */
	String configuredApiKey() {
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
	 * Every edge's numbers, keyed by canonical edge URL, in console-config order.
	 */
	public Map<String, FrontCacheStatus> getClusterStatus(EdgeScope scope) {

		List<FrontCacheClient> clients = scope.clients();
		List<Future<FrontCacheStatus>> futureList = new ArrayList<Future<FrontCacheStatus>>();

		for (FrontCacheClient fcClient : clients)
            futureList.add(executor.submit(new FrontCacheStatusCaller(fcClient)));

		return byEdge(clients, futureList, "statuses");
	}

	/**
	 * Cluster statuses as a list in console-config edge order - the order used
	 * by the "Edges status" block across the console pages.
	 */
	public List<FrontCacheStatus> getClusterStatusList(EdgeScope scope) {
		return new ArrayList<FrontCacheStatus>(getClusterStatus(scope).values());
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
	public List<FrontCacheStatus> getEdgeList(EdgeScope scope) {

		List<FrontCacheStatus> edges = new ArrayList<FrontCacheStatus>();

		for (FrontCacheClient fcClient : scope.clients())
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

	/** One scope's polled answer and when it was taken. */
	private record Polled(List<FrontCacheStatus> statuses, long stamp) { }

	/**
	 * By {@link EdgeScope#id()}: a poll taken for one scope is never another's answer - a cache keyed by nothing is
	 * how one tenant would read another's edges with no edge parameter in sight.
	 */
	private final Map<String, Polled> polledByScope = new ConcurrentHashMap<String, Polled>();
	private final Map<String, Object> pollLocks = new ConcurrentHashMap<String, Object>();

	/**
	 * The same list {@link #getClusterStatusList} returns, but reused across callers of one scope for a few
	 * seconds. This is what the realtime page polls.
	 *
	 * Page loads deliberately keep using the uncached call: an operator who has just invalidated a
	 * cache and reloaded expects to see it, and one fan-out per page load was never the problem this
	 * cache exists to solve.
	 */
	public List<FrontCacheStatus> getClusterStatusListPolled(EdgeScope scope) {

		Polled current = polledByScope.get(scope.id());
		if (null != current && System.currentTimeMillis() - current.stamp() < STATUS_CACHE_MILLIS)
			return current.statuses();

		// One fan-out at a time per scope. Without the lock, every poll that arrives while one is in
		// flight starts its own - which is precisely the multiplication the cache is here to prevent,
		// just moved from "many tabs" to "one slow fleet".
		synchronized (pollLocks.computeIfAbsent(scope.id(), id -> new Object())) {

			current = polledByScope.get(scope.id());
			if (null != current && System.currentTimeMillis() - current.stamp() < STATUS_CACHE_MILLIS)
				return current.statuses();

			List<FrontCacheStatus> fresh = getClusterStatusList(scope);
			polledByScope.put(scope.id(), new Polled(fresh, System.currentTimeMillis()));

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
	public Map<String, VisitorTypesConfig> getVisitorTypes(EdgeScope scope) {

		List<FrontCacheClient> clients = scope.clients();
		List<Future<VisitorTypesConfig>> futureList = new ArrayList<Future<VisitorTypesConfig>>();

		for (FrontCacheClient fcClient : clients)
            futureList.add(executor.submit(new VisitorTypesCaller(fcClient)));

		return byEdge(clients, futureList, "configs");
	}

	/**
	 * Guard rules per edge, in evaluation order, with hit counts.
	 *
	 * Read-only: rules are edited in FRONTCACHE_HOME/conf/guard-rules.conf and applied with the
	 * reload-guard-rules action - same story as the other config screens.
	 */
	public Map<String, GuardRulesConfig> getGuardRules(EdgeScope scope) {

		List<FrontCacheClient> clients = scope.clients();
		List<Future<GuardRulesConfig>> futureList = new ArrayList<Future<GuardRulesConfig>>();

		for (FrontCacheClient fcClient : clients)
            futureList.add(executor.submit(new GuardRulesCaller(fcClient)));

		Map<String, GuardRulesConfig> guardRules = byEdge(clients, futureList, "guard rules");
		guardRules.values().removeIf(rules -> null == rules.getConfig());
		return guardRules;
	}

	/**
	 * One fan-out's answers, keyed by the canonical URL of the edge each came from - the client at the
	 * same index - in console-config order. <b>Never by {@link FrontCacheClient#getName()}</b>: it
	 * strips the scheme and everything from the first colon, so two edges on one host
	 * ({@code localhost:8080}, {@code localhost:9080}) have one name, and keying by it kept one of
	 * them and silently dropped the other - from the Edges status, Visitor Types and Guard screens.
	 *
	 * An edge that answers nothing within the timeout is left out; once one edge has timed out, the
	 * rest get the shorter {@link #CONSOLE_TIMEOUT}, so one slow edge does not multiply the wait.
	 */
	static <T> Map<String, T> byEdge(List<FrontCacheClient> clients, List<Future<T>> futures, String what) {

		// LinkedHashMap preserves the edge order defined in the console config
		// (futures are submitted/consumed in that same order).
		Map<String, T> byEdge = new LinkedHashMap<String, T>();

        boolean timeoutReached = false;
        for (int i = 0; i < futures.size(); i++)
        {
            Future<T> f = futures.get(i);
            try {
            	T result = f.get(timeoutReached ? CONSOLE_TIMEOUT : FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);

            	if (null != result)
            		byEdge.put(canonicalEdgeURL(clients.get(i).getFrontCacheURL()), result);

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached. Some " + what + " may not be retrieved ");
            }
        }

		return byEdge;
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
	public List<EdgeConfigFile> getConfigFiles(EdgeScope scope, ConfigFile configFile) {

		List<FrontCacheClient> fcClients = scope.clients();

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
	 * on a missing key, so reading this without it would make every console whose
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
	 * Whether the Edges screen may add and remove edges, as of 2.12.0. Default true; false for a
	 * console whose edge list is decided elsewhere - a deployment that renders {@code urls} from its
	 * own inventory, where an edge added by hand would be monitored, sent the API key and then silently
	 * dropped at the next restart. It also stops {@code /} adding a local edge by itself on a console
	 * with none.
	 *
	 * Not forced off without credentials, unlike {@link #isConfigEditingEnabled()}: the list is the
	 * console's own state, and the request's {@code EdgeScope} already keeps the API key to the edges in
	 * it.
	 */
	public boolean isEdgeEditingEnabled() {
		return edgeEditingEnabled(fcConsoleConfig);
	}

	/** The decision half of {@link #isEdgeEditingEnabled()}, testable without a config file. */
	static boolean edgeEditingEnabled(Config config) {

		if (null != config && config.hasPath(EDGE_EDITING_KEY))
			return config.getBoolean(EDGE_EDITING_KEY);

		return true;
	}

	/** HOCON key under frontcache.console - read through hasPath, like the credentials are. */
	private static final String EDGE_EDITING_KEY = "edge-editing";

	/**
	 * A client for {@code edgeURL} - but only when it is in the request's scope, and then built from the
	 * CONFIGURED spelling with that edge's key ({@link EdgeScope#client}).
	 *
	 * Every call a client makes carries an API key, and on every screen that names one edge - Cache View,
	 * Invalidation, Other, Replication, Config editing, the Cache Warmer - the edge arrives as a request parameter.
	 * A client built from whatever URL the request named would send that key to any host a caller chooses, which a
	 * crafted POST from an authenticated session is enough to do. {@code DashboardStreamProxyServlet} only relays
	 * edges in the scope for the same reason.
	 *
	 * @throws UnknownEdgeException when the URL is not in the scope - before any client exists. Not in the scope and
	 *         not configured at all are one answer.
	 */
	private static FrontCacheClient requireEdgeClient(EdgeScope scope, String edgeURL) {

		FrontCacheClient client = scope.client(edgeURL);
		if (null == client)
			throw new UnknownEdgeException(edgeURL);

		return client;
	}

	/**
	 * Whether the session cookie carries the {@code Secure} attribute.
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

	// ---- for console plugins ----------------------------------------------------------------------

	/**
	 * The {@code frontcache.console} block of frontcache-console.conf, or null when the console was
	 * started without one. For a console plugin's beans, which read their own keys from it - through
	 * {@code hasPath}, like every key added after the first release.
	 */
	public Config getConsoleConfig() {
		return fcConsoleConfig;
	}

	/** The frontcache-console.conf this console was started with, or null. */
	public File getConsoleConfigFile() {
		return consoleConfigFile;
	}

	/**
	 * An edge URL as it is compared, keyed and handed to the page.
	 *
	 * <b>The trailing slash is the whole reason this exists.</b> {@link #getDashboardStreamList} appends
	 * one, so the stream list carries {@code https://fc-edge.example.com:443/} while {@code urls} - and
	 * therefore anything an operator copies out of it into the Pro plugin's {@code clusters} - says
	 * {@code https://fc-edge.example.com:443}. Comparing the two raw forms makes every cluster come out
	 * empty on a config file that reads perfectly correctly, with nothing to say so.
	 *
	 * Matching is on the URL and never on {@link FrontCacheClient#getName()}: that name strips the
	 * scheme and everything from the first colon, so {@code localhost:8080} and {@code localhost:9080}
	 * - the e2e topology - are the same string. See {@code EdgeConfigFile}, where keying a fan-out by
	 * it used to drop a tab.
	 */
	public static String canonicalEdgeURL(String edgeURL) {
		return EdgeScope.canonical(edgeURL);
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
	public SetConfigActionResponse saveConfigFile(EdgeScope scope, String edgeURL, ConfigFile configFile, String content, String baseHash) {

		if (!isConfigEditingEnabled())
		{
			SetConfigActionResponse refused = new SetConfigActionResponse();
			refused.setFile(configFile.getFileName());
			refused.refuse("config editing is disabled on this console");
			return refused;
		}

		FrontCacheClient fcClient = scope.client(edgeURL);
		if (null == fcClient)
		{
			SetConfigActionResponse refused = new SetConfigActionResponse();
			refused.setFile(configFile.getFileName());
			refused.refuse(UnknownEdgeException.messageFor(edgeURL));
			return refused;
		}

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
	public Map<String, SetConfigActionResponse> saveConfigFileToAllEdges(EdgeScope scope, ConfigFile configFile, String content, String baseHash, String baseHashEdgeURL) {

		Map<String, SetConfigActionResponse> results = new LinkedHashMap<String, SetConfigActionResponse>();

		for (String edgeURL : scope.edgeURLs())
		{
			// The hash guards the file the operator was LOOKING at. Sending it to the other nodes would
			// refuse every one of them whose file differs - which is most of the reason a fleet has more
			// than one node's config in the first place - so those are written unchecked, and the screen
			// says so.
			String hashForEdge = edgeURL.equals(baseHashEdgeURL) ? baseHash : null;

			results.put(edgeURL, saveConfigFile(scope, edgeURL, configFile, content, hashForEdge));
		}

		return results;
	}

	/**
	 * The edges the realtime page subscribes to: names and edge URLs only - the page hands an edge URL
	 * back to {@code DashboardStreamProxyServlet}, which is where the stream path and the API key live.
	 *
	 * Every stream's {@code cluster} is null here; a console plugin's
	 * {@link org.frontcache.console.plugin.DashboardStreamContributor} fills it in (the Pro cluster rate).
	 */
	public List<DashboardStream> getDashboardStreamList(EdgeScope scope)
	{
		List<DashboardStream> streams = new ArrayList<DashboardStream>();
		for (FrontCacheClient fcClient : scope.clients())
			streams.add(new DashboardStream(fcClient.getName(), canonicalEdgeURL(fcClient.getFrontCacheURL()), null));
		return streams;
	}

	/** {@code [{"name":"My Super App","url":"http://sg.example.com/","cluster":"Edge layer"}, ... ]} for the page. */
	public String toJson(List<DashboardStream> streams)
	{
		try {
			return jsonMapper.writeValueAsString(streams);
		} catch (JsonProcessingException e) {
			logger.error("Can't serialize the dashboard stream list", e);
			return "[]";
		}
	}



	/** @throws UnknownEdgeException if the URL is not one of this console's edges */
	public WebResponse getFromCache(EdgeScope scope, String edgeURL, String key)
	{
		if (null == key)
			return null;

		FrontCacheClient fcClient = requireEdgeClient(scope, edgeURL);
		WebResponse webResponse = fcClient.getFromCache(key.trim());

		return webResponse;
	}


	/** Nothing when {@link #isEdgeEditingEnabled()} is off - the controller says so, this keeps it so. */
	public void addEdge(String edge) {
		if (null != edge && isEdgeEditingEnabled())
			frontcacheAgentURLs.add(edge);
	}

	public void removeEdge(String edge) {
		if (null != edge && isEdgeEditingEnabled())
			frontcacheAgentURLs.remove(edge);
	}

	/** @throws UnknownEdgeException if the URL is not one of this console's edges */
	public void invalidateEdge(EdgeScope scope, String edgeURL, String filter) {
		FrontCacheClient fcClient = requireEdgeClient(scope, edgeURL);
		fcClient.removeFromCache(filter);
	}

	/**
	 * Triggers purge of expired entries on the given edge.
	 *
	 * @param edgeURL
	 * @throws UnknownEdgeException if the URL is not one of this console's edges
	 */
	public void purgeEdge(EdgeScope scope, String edgeURL) {
		FrontCacheClient fcClient = requireEdgeClient(scope, edgeURL);
		fcClient.purge();
	}

	/**
	 * Triggers FrontcacheAction.DUMP_KEYS on the given edge - cached keys are
	 * dumped to a file in the edge's ./warmer dir.
	 *
	 * @param edgeURL
	 * @return status message returned by the edge, or an error message if the edge is not available
	 * @throws UnknownEdgeException if the URL is not one of this console's edges
	 */
	public String dumpKeys(EdgeScope scope, String edgeURL) {
		FrontCacheClient fcClient = requireEdgeClient(scope, edgeURL);
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
	 * @throws UnknownEdgeException if the URL is not one of this console's edges
	 */
	public String backupEdge(EdgeScope scope, String edgeURL) {
		FrontCacheClient fcClient = requireEdgeClient(scope, edgeURL);
		BackupActionResponse response = fcClient.backup();
		if (null == response)
			return "ERROR - edge " + edgeURL + " is not available";

		return response.getAction();
	}

	// --- Cache Replication ---

	private final ExecutorService replicationExecutor = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "fc-console-replication");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * The running replication of each scope, by {@link EdgeScope#id()}. One per scope, as there used to be one per
	 * console: a run started in one scope is never another's to see, nor to cancel.
	 */
	private final Map<String, CacheReplicator> activeReplicators = new ConcurrentHashMap<String, CacheReplicator>();

	/**
	 * Starts a cache replication from source edge to target edge, both in the scope.
	 * Runs in a background thread. Only one replication can be active at a time in one scope.
	 *
	 * @param sourceUrl the source edge URL
	 * @param targetUrl the target edge URL
	 * @return the initial ReplicationResult (status RUNNING)
	 * @throws IllegalStateException if a replication is already in progress in this scope
	 * @throws UnknownEdgeException if either URL is not in the scope
	 * @throws IllegalArgumentException if both name the same edge
	 */
	public ReplicationResult startReplication(EdgeScope scope, String sourceUrl, String targetUrl) {

		FrontCacheClient source = requireEdgeClient(scope, sourceUrl);
		FrontCacheClient target = requireEdgeClient(scope, targetUrl);

		// Compared on the configured spelling: "http://edge:9080" and "http://edge:9080/" are one edge,
		// and replicating an edge onto itself would skip every key while reporting success.
		if (source.getFrontCacheURL().equals(target.getFrontCacheURL()))
			throw new IllegalArgumentException("Source and target edge must be different");

		CacheReplicator replicator = new CacheReplicator(source, target);

		CacheReplicator running = activeReplicators.compute(scope.id(),
				(id, active) -> null != active && active.isRunning() ? active : replicator);
		if (running != replicator)
			throw new IllegalStateException("Replication already in progress from "
					+ running.getCurrentResult().getSourceUrl()
					+ " to " + running.getCurrentResult().getTargetUrl());

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
					activeReplicators.remove(scope.id(), replicator);
				}
			}
		});

		return replicator.getCurrentResult();
	}

	/**
	 * Returns the scope's current replication status, or null if none is active in it.
	 */
	public ReplicationResult getReplicationStatus(EdgeScope scope) {
		CacheReplicator r = activeReplicators.get(scope.id());
		return r != null ? r.getCurrentResult() : null;
	}

	/**
	 * Requests cancellation of the scope's running replication.
	 * No-op if none is active in it.
	 */
	public void cancelReplication(EdgeScope scope) {
		CacheReplicator r = activeReplicators.get(scope.id());
		if (r != null) {
			r.cancel();
		}
	}

}
