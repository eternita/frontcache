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
package org.frontcache;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.hc.core5.http.HeaderElement;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.protocol.RedirectStrategy;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.cookie.StandardCookieSpec;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.message.MessageSupport;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.frontcache.bots.BotClassifier;
import org.frontcache.bots.BotRule;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.ClientIpResolver;
import org.frontcache.core.EdgeToken;
import org.frontcache.core.FCHeaders;
import org.frontcache.guard.GuardAction;
import org.frontcache.guard.GuardOutcome;
import org.frontcache.guard.GuardRule;
import org.frontcache.guard.GuardRuleEngine;
import org.frontcache.core.FCUtils;
import org.frontcache.core.ApacheFcHttpClient;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.FC_BypassCache;
import org.frontcache.resilience.FC_Total;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorManager;
import org.frontcache.metrics.FcMetricsExport;
import org.frontcache.reqlog.RequestLogger;
import org.frontcache.module.CoreModuleContext;
import org.frontcache.module.FrontcacheModules;
import org.frontcache.warmer.WarmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheEngine {


	// This node's site. One node serves one site; multi-domain config is retired.
	private String domain;          // front-cache.default-domain
	private String apiKey;          // front-cache.api-key
	private String originHost;      // front-cache.origin-host
	private String originHttpPort;  // front-cache.origin-http-port
	private String originHttpsPort; // front-cache.origin-https-port

	// The scheme this site is served on publicly, when the node cannot work it out from the request.
	// null = fall back to X-Forwarded-Proto (trusted peers only), then to the incoming connection.
	private String publicScheme;    // front-cache.public-scheme

	private String frontcacheHttpPort = null;

	private String frontcacheHttpsPort = null;

	private String fcHostId = null;	// used to determine which front cache processed request (forwarded by GEO Load Balancer e.g. route53 AWS)

    private boolean logToHeadersConfig = false; // log requests/includes performance stat to HTTP headers

	public final static String DEFAULT_FRONTCACHE_HOST_NAME_VALUE = "undefined-front-cache-host";

	// Connection pool must be at least as large as the pools that draw from it: the
	// OriginPool (resilience.threadpool.OriginPool.coreSize) plus the include-processor threads
	// (front-cache.include-processor.impl.concurrent.thread-amount). Practically all traffic goes to a
	// single origin, i.e. ONE route - so a per-route cap below that count guarantees the surplus
	// threads block on the pool lease under load, regardless of how healthy the origin is. Hence
	// max-per-route defaults to max-total rather than HttpClient's own default of 2/20.
	private static final int DEFAULT_CONNECTIONS_MAX_TOTAL = 400;
	private int fcConnectionsMaxTotal = DEFAULT_CONNECTIONS_MAX_TOTAL;
	private int fcConnectionsMaxPerRoute = DEFAULT_CONNECTIONS_MAX_TOTAL;

	// How long a thread may wait for a connection from the pool. Without it the wait is UNBOUNDED:
	// a starved request parks in AbstractConnPool.getPoolEntryBlocking holding its command thread for
	// the whole command timeout, so a brief connection shortage escalates into thread-pool rejections
	// and open circuits. Failing fast here turns that into a normal fallback instead.
	private static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT = 2000;
	private int fcConnectionRequestTimeout = DEFAULT_CONNECTION_REQUEST_TIMEOUT;

	// fraction of the pool in use above which the periodic stats line also warns on the engine logger
	private static final double POOL_SATURATION_WARN_RATIO = 0.8;

	// how often the connection-pool snapshot is written
	private static final long POOL_STATS_LOG_PERIOD_MS = 5 * 60 * 1000;

	// Dedicated logger for the pool snapshot, routed by fc-logback.xml to
	// ${frontcache.home}/logs/http-pool.info (additivity=false keeps it out of info.log).
	// Named, not class-based, so the destination is a config decision - same approach as
	// the 'frontcache.failed-requests' logger.
	private static final Logger httpPoolLogger = LoggerFactory.getLogger("frontcache.http-pool");

	// grace period before a replaced HTTP client is closed on reload - longer than the largest
	// command timeout so in-flight origin calls finish rather than being cut off
	private static final long SHUTDOWN_GRACE_PERIOD_MS = 30000;

	private static final int DEFAULT_SOCKET_TIMEOUT = 10000;
	private int fcSocketTimeout = DEFAULT_SOCKET_TIMEOUT;

	private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
	private int fcConnectTimeout = DEFAULT_CONNECT_TIMEOUT;

	// Requests whose URI (path + query) exceeds this length are rejected with 414 before touching
	// cache or origin. Guards against absurdly long URLs (e.g. a crawler's compounding &amp;-mangled
	// query) that would otherwise each be a unique cache key hitting origin. Tunable via
	// front-cache.max-request-uri-length; <= 0 disables the check.
	// Compiled once. String.replaceAll() compiles its pattern on every call - only reached when the URI
	// actually carries a jsessionid, but there is no reason to pay for it when it does.
	private static final Pattern JSESSIONID_PATTERN = Pattern.compile(";jsessionid=.*?(?=\\?|$)");

	private static final int DEFAULT_MAX_REQUEST_URI_LENGTH = 4096;
	private static int maxRequestURILength = DEFAULT_MAX_REQUEST_URI_LENGTH;

	// Guard rules run before cache and origin: the two built-in checks above plus whatever
	// conf/guard-rules.conf adds (reject / redirect / allow). See
	// docs/guard-getting-started.md.
	private GuardRuleEngine guardRuleEngine = null;

	/**
	 * Whether a bypassed (non-cacheable) response keeps the origin's Content-Encoding.
	 *
	 * Off by default, and deliberately so for one release - it changes what goes on the wire.
	 *
	 * Frontcache forces {@code Accept-Encoding: gzip} to the origin and lets HttpClient transparently
	 * decompress. On the CACHEABLE path that is required: the cached bytes have to be plaintext, or
	 * the include processor cannot scan them and an encoding we cannot decode (br, zstd) would be
	 * cached and served to clients that never asked for it. On the BYPASS path none of that applies -
	 * the body is streamed straight through, never parsed and never cached - so the effect is that
	 * Frontcache gunzips a response (CPU) and then ships it to the client uncompressed (bandwidth),
	 * even when the client asked for gzip and the origin was willing to send it.
	 *
	 * Left off by default because an edge or CDN in front of Frontcache may be compressing today on
	 * the assumption that what reaches it is identity; turning this on without checking that would
	 * mean double compression.
	 */
	private static final String BYPASS_PASSTHROUGH_ENCODING_KEY = "front-cache.bypass.passthrough-encoding";
	private boolean bypassPassthroughEncoding = false;

	/** Static override for the public scheme, for a deployment with no trusted-proxy list. */
	private static final String PUBLIC_SCHEME_KEY = "front-cache.public-scheme";

	/**
	 * Whether origin calls carry X-Forwarded-Host / X-Forwarded-Proto naming the public site.
	 *
	 * Off by default and deliberately so: an origin app with Spring's ForwardedHeaderFilter (or any
	 * equivalent) enabled changes every absolute URL it emits the moment these appear, which is the
	 * point of the option but is not a thing to do silently underneath a running deployment.
	 */
	public static final String FORWARD_PUBLIC_HOST_KEY = "front-cache.origin.forward-public-host";
	private boolean forwardPublicHostToOrigin = false;

	/** @return true when a bypassed response should keep the origin's Content-Encoding */
	public boolean isBypassPassthroughEncoding() {
		return bypassPassthroughEncoding;
	}

	private IncludeProcessor includeProcessor = null;

	private CacheProcessor cacheProcessor = null;

	protected Logger logger = null;

	private final Timer connectionManagerTimer = new Timer("FrontCacheEngine.connectionManagerTimer", true);

	private PoolingHttpClientConnectionManager connectionManager;

	private CloseableHttpClient httpClient;

	// The FcHttpClient view of the same client. This is what the three reflection-loaded extension
	// points (CacheProcessor / IncludeProcessor / FallbackResolver) are handed - they no longer see
	// an Apache type, so an HttpClient upgrade cannot break them.
	private FcHttpClient fcHttpClient;

	public static boolean debugComments = false; // if true - appends debug comments (for includes) to output

	// volatile + double-checked locking - see CacheManager.getInstance(): the unsynchronized
	// check-then-act let concurrent first requests build two engines, each initializing its own
	// cache processor / Lucene writer on one index dir.
	private static volatile FrontCacheEngine instance;

    private static final String INCLUDE_LEVEL_TOP_LEVEL = "0";

	/**
	 * The engine if one has been built, without building one.
	 *
	 * {@link #getFrontCache()} constructs the engine on first call, which is right for the request
	 * path and wrong for anything that merely observes: a Prometheus scrape must never be what starts
	 * a node. Mirrors {@code CacheManager.existingInstance()}, and for the same reason.
	 *
	 * @return the current engine, or null if none has been created yet
	 */
	public static FrontCacheEngine existingInstance() {
		return instance;
	}

	public static FrontCacheEngine getFrontCache() {
		FrontCacheEngine localInstance = instance;
		if (null == localInstance) {
			synchronized (FrontCacheEngine.class) {
				localInstance = instance;
				if (null == localInstance) {
//					FCConfig.init();

					//Logs config ->  !!! -Dlogback.configurationFile=/opt/frontcache/conf/fc-logback.xml

					String debugCommentsStr = FCConfig.getProperty("front-cache.debug-comments", "false");
					if ("true".equalsIgnoreCase(debugCommentsStr))
						debugComments = true;

					localInstance = new FrontCacheEngine();
					instance = localInstance;
				}
			}
		}
		return localInstance;
	}

	/**
	 * The site this node serves (front-cache.default-domain). Stamped on every request context and
	 * on every cache entry this node writes.
	 */
	public String getDomain()
	{
		return domain;
	}

	/**
	 * The site's public hostname, or null when the node has none configured.
	 *
	 * Same value as {@link #getDomain()} with the two non-answers mapped onto null. The property
	 * defaults to {@link FCConfig#DEFAULT_DOMAIN}, whose value is the literal string
	 * {@code "default-domain"}, and both test FRONTCACHE_HOMEs leave it unset - so this is a live
	 * path, not a defensive one. Emitting {@code https://default-domain/...} into a browser's
	 * address bar would be a worse bug than the one this exists to fix, so a node that cannot name
	 * itself says so and every caller falls back to the Host that arrived.
	 *
	 * @return the public hostname, or null
	 */
	/**
	 * @return true when origin calls should carry X-Forwarded-Host / X-Forwarded-Proto naming the
	 *         public site (front-cache.origin.forward-public-host)
	 */
	public boolean isForwardPublicHostToOrigin()
	{
		return forwardPublicHostToOrigin;
	}

	public String getPublicDomain()
	{
		if (null == domain)
			return null;

		String trimmed = domain.trim();
		if (0 == trimmed.length() || FCConfig.DEFAULT_DOMAIN.equals(trimmed))
			return null;

		return trimmed;
	}

	/**
	 * @return true when {@code apiKey} is this node's configured management API key
	 */
	public boolean isNodeApiKey(String apiKey)
	{
		return null != apiKey && 0 < apiKey.length() && apiKey.equals(this.apiKey);
	}

	// synchronized on the same monitor as getFrontCache() so a shutdown/reload can't interleave with
	// a lazy init and leave two engines (and two Lucene writers) alive.
	public static void destroy() {
		destroy(true);
	}

	private static void destroy(boolean shutdown) {
		synchronized (FrontCacheEngine.class) {
			if (null != instance) {
				instance.stop(shutdown);
				instance = null;
			}
		}
	}

	/**
	 * Rebuilds the engine - the {@code reload} management action.
	 *
	 * It re-reads what the engine owns (guard-rules.conf, fallbacks.conf, the HTTP client) and bots.conf,
	 * and nothing FCConfig holds: frontcache.properties and dynamic-urls.conf are read once, by FCConfig's
	 * static initializer. The cache and include processors are kept - see {@link #stop(boolean)}.
	 */
	public static void reload() {

		destroy(false);

		// The guard rules and the fallback resolver are rebuilt with the engine that owns them; the
		// bot classifier is a process-wide singleton (like ClientIpResolver, and for the same reason -
		// FCConfig and the management API read it without an engine), so it has to be told.
		BotClassifier.reload();

		getFrontCache(); // recreate

		return;
	}

	private FrontCacheEngine() {

		initialize();
	}

	// used by FrontCacheIOServlet
	public FcHttpClient getFcHttpClient()
	{
		return fcHttpClient;
	}

	// used by FrontCacheIOServlet
	public HttpClient getHttpClient()
	{
		return httpClient;
	}

	// Retired multi-domain config. One node serves one site; these keys used to make a node serve
	// several, and a node configured with them refuses to start.
	private static final String RETIRED_DOMAIN_LIST_KEY = "front-cache.domains";
	private static final String RETIRED_DOMAIN_KEY_PREFIX = "front-cache.domain.";

	// Config that conf/<domain>/ subdirectories used to layer over the top-level files. Those
	// subdirectories are no longer read at all, so a leftover one must not be ignored in silence.
	private static final String[] RETIRED_DOMAIN_CONFIG_FILES = { "bots.conf", "dynamic-urls.conf", "fallbacks.conf" };

	// The engine is rebuilt on every getFrontCache() until construction succeeds, so a rejected
	// config would otherwise re-log this whole message once per request.
	private static final AtomicBoolean retiredDomainConfigLogged = new AtomicBoolean(false);

	/**
	 * Retired multi-domain keys that are set to a non-blank value, sorted, {@code front-cache.domains}
	 * first. A blank value counts as absent, so an operator can neutralize a key by emptying it.
	 *
	 * Per-domain overrides are reported even without {@code front-cache.domains}: on their own they
	 * were silently ignored, so whoever set them never learned they did nothing.
	 */
	// package-private for FrontCacheEngineRetiredDomainConfigTest
	static List<String> findRetiredDomainKeys()
	{
		List<String> retiredKeys = new ArrayList<String>();

		String domainList = FCConfig.getProperty(RETIRED_DOMAIN_LIST_KEY);
		if (isSet(domainList))
			retiredKeys.add(RETIRED_DOMAIN_LIST_KEY + "=" + domainList.trim());

		List<String> overrideKeys = new ArrayList<String>();
		Properties props = FCConfig.getProperties();
		for (String key : props.stringPropertyNames())
			if (key.startsWith(RETIRED_DOMAIN_KEY_PREFIX) && isSet(props.getProperty(key)))
				overrideKeys.add(key);

		Collections.sort(overrideKeys);
		retiredKeys.addAll(overrideKeys);

		return retiredKeys;
	}

	private static boolean isSet(String value)
	{
		return null != value && 0 < value.trim().length();
	}

	/**
	 * Leftover per-domain config files, as {@code conf/<domain>/<file>} paths, sorted.
	 *
	 * conf/ subdirectories used to be scanned as domains, each able to override bots.conf,
	 * dynamic-urls.conf and fallbacks.conf. They are no longer read. A subdirectory on its own is
	 * nobody's business - only one holding a file that USED to be layered is reported, since that
	 * config would now be silently ignored.
	 */
	// package-private for FrontCacheEngineRetiredDomainConfigTest
	static List<String> findRetiredDomainConfigDirs()
	{
		List<String> retired = new ArrayList<String>();

		File confDir = FCConfig.getConfDir();
		if (null == confDir)
			return retired;

		File[] subDirs = confDir.listFiles(new FileFilter() {
			public boolean accept(File file) {
				return file.isDirectory();
			}
		});
		if (null == subDirs)
			return retired;

		for (File subDir : subDirs)
			for (String configFile : RETIRED_DOMAIN_CONFIG_FILES)
				if (new File(subDir, configFile).isFile())
					retired.add("conf/" + subDir.getName() + "/" + configFile);

		Collections.sort(retired);

		return retired;
	}

	// package-private for FrontCacheEngineRetiredDomainConfigTest
	static String retiredDomainConfigMessage(List<String> retiredKeys)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Multi-domain configuration is no longer supported - refusing to start.")
		  .append("\n\nRetired multi-domain configuration found (keys in conf/frontcache.properties,")
		  .append("\nand per-domain config directories under conf/):");

		for (String key : retiredKeys)
			sb.append("\n    ").append(key);

		sb.append("\n\nA node serving several domains shared one cache and one set of circuit breakers")
		  .append("\nacross all of them: cache entries are keyed by request path only, so a request to one")
		  .append("\ndomain could be served another domain's cached page, and one domain's failing origin")
		  .append("\nopened the circuit breaker for every other domain on the node.")
		  .append("\n\nFix: remove the entries above and run one Frontcache node per site. Set this node's")
		  .append("\nown domain with front-cache.default-domain and its origin with front-cache.origin-host")
		  .append("\n(front-cache.origin-http-port / front-cache.origin-https-port). Per-domain config")
		  .append("\nfiles move to the top-level conf/bots.conf, conf/dynamic-urls.conf, conf/fallbacks.conf.")
		  .append("\n\nSee docs/release-notes/2.6.0.md");

		return sb.toString();
	}

	/**
	 * Refuses to start a node configured for multi-domain.
	 *
	 * Serving several domains from one node was never sound: L2 cache entries are keyed by request
	 * PATH only (see LuceneIndexManager.toPathKey), so a request to one domain can be served another
	 * domain's cached page, and Hystrix keys circuit breakers by command name only, so one domain's
	 * failing origin opens the breaker for every other domain on the node.
	 *
	 * Failing closed rather than ignoring the keys: they ask for per-domain origin routing, and a node
	 * that silently sent every domain to one origin would be a worse outcome than one that does not
	 * start. Removing the keys is a config edit and a restart.
	 *
	 * @throws IllegalStateException when any retired key is set
	 */
	private void rejectRetiredDomainConfig()
	{
		List<String> retiredKeys = findRetiredDomainKeys();
		retiredKeys.addAll(findRetiredDomainConfigDirs());
		if (retiredKeys.isEmpty())
			return;

		String message = retiredDomainConfigMessage(retiredKeys);

		if (retiredDomainConfigLogged.compareAndSet(false, true))
			logger.error(message);

		throw new IllegalStateException(message);
	}

	private void loadOriginConfig()
	{
		rejectRetiredDomainConfig();

		domain          = FCConfig.getProperty("front-cache.default-domain", FCConfig.DEFAULT_DOMAIN);
		apiKey          = FCConfig.getProperty("front-cache.api-key", "");
		originHost      = FCConfig.getProperty("front-cache.origin-host", "localhost");
		originHttpPort  = FCConfig.getProperty("front-cache.origin-http-port", "80");
		originHttpsPort = FCConfig.getProperty("front-cache.origin-https-port", "443");

		// Built here, immediately after the values they depend on - so a bad origin-host fails at
		// startup with a clear message rather than on the first request.
		buildOriginUrls();

		logger.info("Serving domain " + domain + " from origin " + originHost
				+ " (http " + originHttpPort + " / https " + originHttpsPort + ")");

		loadPublicIdentityConfig();

		EdgeToken.logConfiguration(logger);

		return;
	}

	/**
	 * Reads front-cache.public-scheme and says, once, what this node will put in a URL it hands to a
	 * client. Both halves of that answer have a silent failure mode - a node emitting its own name
	 * or "http" where the site is "https" produces redirects that look fine in the log and are wrong
	 * in the browser - so the resolved answer is stated at startup rather than left to be inferred.
	 */
	private void loadPublicIdentityConfig()
	{
		String configured = FCConfig.getProperty(PUBLIC_SCHEME_KEY, "");
		if (null != configured)
			configured = configured.trim().toLowerCase();

		if (null == configured || 0 == configured.length())
			publicScheme = null;
		else if ("http".equals(configured) || "https".equals(configured))
			publicScheme = configured;
		else
		{
			publicScheme = null;
			logger.error(PUBLIC_SCHEME_KEY + "=" + configured + " is not 'http' or 'https' - ignoring it."
					+ " The scheme of an emitted URL will come from X-Forwarded-Proto (trusted peers only)"
					+ " or from the incoming connection.");
		}

		String publicDomain = getPublicDomain();
		if (null == publicDomain)
		{
			logger.warn("front-cache.default-domain is not set to this site's public hostname"
					+ " (it is " + ((null == domain) ? "null" : "'" + domain + "'") + ")."
					+ " Guard-rule ${url} / ${host} and origin-redirect rewriting will echo whatever Host"
					+ " each request arrives under, which is not always the public one"
					+ " - see docs/release-notes/2.9.0.md");
		} else {
			String scheme = (null != publicScheme) ? publicScheme : "<from X-Forwarded-Proto or the incoming connection>";
			logger.info("Public identity for emitted URLs (guard-rule ${url} / ${host}, origin-redirect"
					+ " rewriting): " + scheme + "://" + publicDomain);
		}

		return;
	}

	private void initialize() {

		logger = LoggerFactory.getLogger(FrontCacheEngine.class);

		loadOriginConfig();

		frontcacheHttpPort = FCConfig.getProperty("front-cache.http-port", "80");
		frontcacheHttpsPort = FCConfig.getProperty("front-cache.https-port", "443");

		fcHostId = FCConfig.getProperty(FCConfig.FRONTCACHE_ID_KEY);
		if (null == fcHostId)
			fcHostId = DEFAULT_FRONTCACHE_HOST_NAME_VALUE;

        logToHeadersConfig = "true".equals(FCConfig.getProperty("front-cache.log-to-headers", "false")) ? true : false;

        maxRequestURILength = getIntProperty("front-cache.max-request-uri-length", DEFAULT_MAX_REQUEST_URI_LENGTH);

        forwardPublicHostToOrigin = "true".equalsIgnoreCase(
                FCConfig.getProperty(FORWARD_PUBLIC_HOST_KEY, "false"));
        if (forwardPublicHostToOrigin)
        {
            if (null == getPublicDomain())
                logger.error(FORWARD_PUBLIC_HOST_KEY + "=true but front-cache.default-domain names no"
                        + " public host - origin calls will carry no X-Forwarded-Host");
            else
                logger.info("Origin calls will carry X-Forwarded-Host: " + getPublicDomain()
                        + " (" + FORWARD_PUBLIC_HOST_KEY + "=true)");
        }

        bypassPassthroughEncoding = "true".equalsIgnoreCase(
                FCConfig.getProperty(BYPASS_PASSTHROUGH_ENCODING_KEY, "false"));
        if (bypassPassthroughEncoding)
            logger.info("Bypass (non-cacheable) responses will pass the origin's Content-Encoding "
                    + "through untouched (" + BYPASS_PASSTHROUGH_ENCODING_KEY + "=true)");

        // built from the current config; reload() rebuilds the engine, so it picks up both a changed
        // max-request-uri-length and an edited guard-rules.conf
        guardRuleEngine = new GuardRuleEngine(maxRequestURILength);

		fcConnectionsMaxTotal = getIntProperty("front-cache.http-client.connections-max-total", DEFAULT_CONNECTIONS_MAX_TOTAL);
		fcConnectionsMaxPerRoute = getIntProperty("front-cache.http-client.connections-max-per-route", fcConnectionsMaxTotal);
		fcConnectionRequestTimeout = getIntProperty("front-cache.http-client.connection-request-timeout", DEFAULT_CONNECTION_REQUEST_TIMEOUT);
		fcSocketTimeout = getIntProperty("front-cache.http-client.socket-timeout", DEFAULT_SOCKET_TIMEOUT);
		fcConnectTimeout = getIntProperty("front-cache.http-client.connect-timeout", DEFAULT_CONNECT_TIMEOUT);

		cacheProcessor = CacheManager.getInstance();

		includeProcessor = IncludeProcessorManager.getInstance();

		this.httpClient = newClient();
		this.fcHttpClient = new ApacheFcHttpClient(this.httpClient);

		logger.info("HTTP client pool - max total " + fcConnectionsMaxTotal + ", max per route " + fcConnectionsMaxPerRoute
				+ ", connection request timeout " + fcConnectionRequestTimeout + "ms, connect timeout " + fcConnectTimeout
				+ "ms, socket timeout " + fcSocketTimeout + "ms");

		connectionManagerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (connectionManager == null) {
					return;
				}
				connectionManager.closeExpired();
			}
		}, 30000, 5000);

		// Periodic connection-pool snapshot. Two destinations on purpose:
		//  - every tick goes to the dedicated http-pool logger, giving a continuous history to read
		//    back after an incident (the pool was invisible during the 2026-08-12 outage);
		//  - a near-exhausted pool additionally warns on the engine logger, so it lands in error.log
		//    where alerting looks, instead of only in a file nobody watches.
		connectionManagerTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (connectionManager == null) {
					return;
				}
				PoolStats poolStats = connectionManager.getTotalStats();

				httpPoolLogger.info("leased " + poolStats.getLeased()
						+ ", pending " + poolStats.getPending()
						+ ", available " + poolStats.getAvailable()
						+ ", max " + poolStats.getMax());

				if (poolStats.getPending() > 0 || poolStats.getLeased() >= POOL_SATURATION_WARN_RATIO * poolStats.getMax())
					logger.warn("HTTP connection pool is close to exhaustion - " + poolStats);
			}
		}, POOL_STATS_LOG_PERIOD_MS, POOL_STATS_LOG_PERIOD_MS);


		// Created here, not on the thread below: this reads conf/fallbacks.conf and needs no server, so
		// doing it now means a fallback is answerable from the very first request and a bad config line
		// is logged at startup. Only the origin warm-up has to wait for the node to be listening.
		FallbackResolverFactory.getInstance();

		Thread t = new Thread(new Runnable() {

			public void run() {
				try {
					Thread.sleep(3000); // wait server is started to load fallbacks from URLs
				} catch (Exception e) {
					e.printStackTrace();
				}

				FallbackResolverFactory.init(fcHttpClient);
			}
		});
		t.start();

		// Modules from their own jars (the Pro module: licensing, the warmer's orchestration), found
		// through FrontcacheModule's ServiceLoader. After the cache and before metrics export, so a
		// module's meters bind to a started module. Never on the request path, and a module that throws is
		// dropped rather than stopping the node.
		FrontcacheModules.start(new CoreModuleContext(getPublicDomain()));

		// Prometheus / OTLP export. A no-op unless front-cache.metrics.export says otherwise, which is
		// why this is called unconditionally: the node's lifecycle owner is the right place to start a
		// background push loop, and gating the call here would just duplicate the config read.
		FcMetricsExport.start();

		return;
	}

	/**
	 * Closes a replaced HTTP client once the requests still running on it have had time to finish.
	 * Runs on a daemon thread so it can never hold up JVM shutdown.
	 */
	private void closeQuietlyAfterGracePeriod(final CloseableHttpClient client) {

		if (null == client)
			return;

		Thread closer = new Thread(new Runnable() {
			public void run() {
				try {
					Thread.sleep(SHUTDOWN_GRACE_PERIOD_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt(); // JVM is going down - close now
				}
				try {
					client.close(); // also shuts down the connection manager it owns
					logger.info("Replaced HTTP client closed, its connection pool released");
				} catch (IOException e) {
					logger.error("Can't close replaced HTTP client", e);
				}
			}
		}, "FrontCacheEngine.httpClientCloser");
		closer.setDaemon(true);
		closer.start();
	}

	private int getIntProperty(String key, int defaultValue) {
		try {
			return Integer.parseInt(FCConfig.getProperty(key, "" + defaultValue).trim());
		} catch (NumberFormatException e) {
			logger.warn("Invalid " + key + " - using default " + defaultValue);
			return defaultValue;
		}
	}

	/**
	 * The origin's base URL, one per scheme, built once at startup.
	 *
	 * This depends on exactly one property of a request - {@code isSecure()} - and otherwise only on
	 * config that cannot change without a reload (which rebuilds the engine). It used to be rebuilt per
	 * call: a StringBuilder, then {@code new URL(...)}, which is a string parse plus a
	 * URLStreamHandler lookup. And it was called TWICE per request - init() stored it on the context
	 * and processRequestInternal() computed it again and discarded the stored one.
	 */
	private URL originUrlHttp;
	private URL originUrlHttps;

	private void buildOriginUrls() {
		originUrlHttp = parseOriginUrl(false, originHttpPort);
		originUrlHttps = parseOriginUrl(true, originHttpsPort);
	}

	private URL parseOriginUrl(boolean isSecure, String port) {
		String urlStr = makeURL(isSecure, originHost, port);
		try {
			return new URL(urlStr);
		} catch (MalformedURLException e) {
			throw new RuntimeException("Invalid front-cache.origin-host (" + originHost + ")", e);
		}
	}

	private URL getOriginUrl(RequestContext context) {
		return context.getRequest().isSecure() ? originUrlHttps : originUrlHttp;
	}

	/**
	 * The scheme the client used, as opposed to the scheme of the connection this process accepted.
	 * They differ wherever TLS is terminated in front of us - which on a typical edge is every
	 * request, since nginx proxies to a plaintext port.
	 *
	 * Precedence: the static override, then X-Forwarded-Proto, then the connection.
	 *
	 * X-Forwarded-Proto is honoured ONLY from a trusted proxy, the same rule ClientIpResolver applies
	 * to X-Forwarded-For and for a sharper reason: this value goes into a redirect a browser will
	 * follow, so a client that could set it could choose the scheme of a URL we vouch for. With no
	 * trusted-proxy list configured the header is ignored outright - front-cache.public-scheme is the
	 * way to state the answer in that deployment.
	 *
	 * @param fallback scheme of the incoming connection, already normalized to http/https
	 */
	private String resolvePublicProtocol(HttpServletRequest servletRequest, String fallback)
	{
		if (null != publicScheme)
			return publicScheme;

		try {
			if (ClientIpResolver.getInstance().isTrustedPeer(servletRequest))
			{
				String forwarded = servletRequest.getHeader(FCHeaders.X_FORWARDED_PROTO);
				if (null != forwarded)
				{
					// "X-Forwarded-Proto: https, http" - the leftmost entry is what the client used
					int comma = forwarded.indexOf(',');
					if (-1 < comma)
						forwarded = forwarded.substring(0, comma);

					forwarded = forwarded.trim();
					if ("https".equalsIgnoreCase(forwarded) || "http".equalsIgnoreCase(forwarded))
						return forwarded.toLowerCase();
				}
			}
		} catch (Exception e) {
			// recycled request, or a resolver that cannot be built - the connection's own scheme is
			// always available and is never worse than what we have today
		}

		return fallback;
	}

	private String makeURL(boolean isSecure, String host, String port)
	{
		StringBuilder str = new StringBuilder(32);
		if (isSecure)
			str.append("https");
		else
			str.append("http");

		str.append("://").append(host);
		if (isSecure) {
			if (!"443".equals(port))
				str.append(":").append(port);
		} else {
			if (!"80".equals(port))
				str.append(":").append(port);
		}

		return str.toString();
	}


	/**
	 * @param shutdown true when the process is going away; false when {@link #reload()} is about to build
	 *        a replacement engine
	 */
	private void stop(boolean shutdown) {

		FrontcacheModules.stop(shutdown);

		// FCConfig is deliberately NOT torn down here. It used to be (FCConfig.destroy() nulled the
		// properties and emptied dynamic-urls.conf), but FCConfig is initialized by its static
		// initializer and nowhere else, so the engine reload() builds next - and BotClassifier.reload()
		// before it - dereferenced a null config: the reload action took the node down. FCConfig is
		// process-wide config, like BotClassifier, and outlives any one engine. FrontCacheEngineReloadTest.

		FallbackResolverFactory.destroy();

		connectionManagerTimer.cancel();

		// Close the client (and with it the connection manager) - reload() destroys this engine and
		// builds a new one, so without this every reload orphans a whole pool: up to
		// front-cache.http-client.connections-max-total sockets kept alive with no reference left to
		// close them, and no timer still running to expire them.
		// Deferred: close() shuts the pool down immediately, killing origin calls still in flight on
		// this (now replaced) client. Requests are bounded by the command timeouts, so waiting
		// out SHUTDOWN_GRACE_PERIOD_MS lets them drain and keeps a config reload non-disruptive.
		closeQuietlyAfterGracePeriod(httpClient);
		httpClient = null;
		fcHttpClient = null;
		connectionManager = null;

		// The cache and include processors are JVM-wide singletons: CacheManager and
		// IncludeProcessorManager hand the SAME instance to the engine reload() builds next, so
		// destroying them here on a reload gave that engine a disposed Ehcache, a closed Lucene writer and
		// shut-down executors. They are built from FCConfig, which a reload does not re-read, so there is
		// nothing a rebuild could apply - a reload keeps them (and the L1 cache with them).
		if (!shutdown)
			return;

		if (null != includeProcessor)
			includeProcessor.destroy();

		if (null != cacheProcessor)
			cacheProcessor.destroy();

	}


	private CloseableHttpClient newClient() {
		// HttpClient 5 moved the socket and connect timeouts off RequestConfig: the socket read
		// timeout lives on SocketConfig and the connect timeout on ConnectionConfig, both applied to
		// the connection manager (see newConnectionManager()). RequestConfig.setResponseTimeout is
		// NOT the old socket timeout - it bounds the wait for the response head, not inactivity on
		// each read - so mapping onto it would have quietly changed what the config key means.
		final RequestConfig requestConfig = RequestConfig.custom()
				// bounded wait for a pooled connection - never block a worker thread indefinitely
				.setConnectionRequestTimeout(Timeout.ofMilliseconds(fcConnectionRequestTimeout))
				.setCookieSpec(StandardCookieSpec.IGNORE)
				.build();

	    ConnectionKeepAliveStrategy keepAliveStrategy = FCUtils.keepAliveStrategy();

		return HttpClients.custom()
				.setConnectionManager(newConnectionManager())
				.setDefaultRequestConfig(requestConfig)
//				.setSSLHostnameVerifier(new NoopHostnameVerifier()) // for SSL do not verify certificate's host
				// Retry idempotent (GET) origin calls once. Pooled keep-alive
				// connections can be closed by the origin between requests; reusing a
				// stale one throws NoHttpResponseException. A single retry transparently
				// re-issues on a fresh connection. (Jetty 12's connection-reuse timing
				// surfaces this stale-connection race that Jetty 9.4 masked.)
				//
				// DefaultHttpRequestRetryStrategy does cover this: NoHttpResponseException extends
				// IOException directly, not the non-retriable ConnectionClosedException, so it is
				// retried for idempotent requests. TimeValue.ZERO_MILLISECONDS is load-bearing - the no-arg
				// constructor is (1, TimeValue.ofSeconds(1)) and HttpRequestRetryExec honours that
				// interval by sleeping, which would park a command worker for a full second before a
				// retry that is instant on a fresh connection. 4.5's handler retried immediately.
				.setRetryStrategy(new DefaultHttpRequestRetryStrategy(1, TimeValue.ZERO_MILLISECONDS))
				.setKeepAliveStrategy(keepAliveStrategy)
				// Frontcache relays the origin's redirect to the client (rewriting Location itself in
				// FCUtils.transformRedirectURL); it must never follow one on the origin's behalf.
				// 5.x says that in one call - 4.5 needed a no-op RedirectStrategy.
				.disableRedirectHandling()
				.build();
	}

	private PoolingHttpClientConnectionManager newConnectionManager() {
		try {
//		        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
//		        trustStore.load(new FileInputStream(keyStorePath), keyStorePassword.toCharArray());

//		        MySSLSocketFactory sf = new MySSLSocketFactory(trustStore);
//		        sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);


//			final Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
//					.register("http", PlainConnectionSocketFactory.INSTANCE)
//					.register("https", sf)
//					.build();

//			connectionManager = new PoolingHttpClientConnectionManager(registry);
			connectionManager = new PoolingHttpClientConnectionManager();

			connectionManager.setMaxTotal(fcConnectionsMaxTotal);
			connectionManager.setDefaultMaxPerRoute(fcConnectionsMaxPerRoute);

			// Origin socket read timeout. In 4.5 this was RequestConfig.setSocketTimeout; in 5.x it
			// belongs to the connection manager. Keep it below the command timeouts so a slow origin
			// is ended by the HTTP client - which releases the connection - rather than by the
			// command timeout, which only sets an interrupt flag a blocking socket read ignores
			// while the connection stays leased. This is what front-cache.http-client.socket-timeout
			// configures, and it must keep meaning exactly that.
			connectionManager.setDefaultSocketConfig(SocketConfig.custom()
					.setSoTimeout(Timeout.ofMilliseconds(fcSocketTimeout))
					.build());

			// Connect timeout. Also accepted (deprecated) on RequestConfig in 5.3 - ConnectionConfig
			// is the non-deprecated home and is where it will stay.
			connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.ofMilliseconds(fcConnectTimeout))
					.build());

			return connectionManager;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Works for Servlets and ServletFilters
	 *
	 * @param servletRequest
	 * @param servletResponse
	 * @param filterChain
	 */
    private RequestContext init(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FilterChain filterChain) {

        RequestContext context = new RequestContext();
        context.setFrontCacheId(fcHostId);
        context.setRequest(servletRequest);
        context.setResponse(servletResponse);
		String uri = FCUtils.buildRequestURI(servletRequest);
		if (-1 < uri.indexOf(";jsessionid=")) // remove JSESSIONID from URI
			uri = JSESSIONID_PATTERN.matcher(uri).replaceAll("");

		context.setRequestURI(uri);

		String queryString =  servletRequest.getQueryString(); // FCUtils.getQueryString(servletRequest, context);
		queryString = ( null == queryString) ? "" : "?" + queryString;

		context.setRequestQueryString(queryString);
		context.setFrontCacheHost(servletRequest.getServerName());
		context.setFrontCacheHttpPort(frontcacheHttpPort);
		context.setFrontCacheHttpsPort(frontcacheHttpsPort);

		context.setDomain(domain);

		String protocol = "https".equalsIgnoreCase(servletRequest.getScheme()) ? "https" : "http";
		context.setFrontCacheProtocol(protocol);

		// The public identity, for URLs we hand to a client. Kept beside the two above rather than
		// replacing them: frontCacheHost/frontCacheProtocol describe the connection this process
		// actually got, which is what predicates match and what the cache key is built from.
		context.setPublicHost(getPublicDomain());
		context.setPublicProtocol(resolvePublicProtocol(servletRequest, protocol));

        context.setOriginURL(getOriginUrl(context));

        if (logToHeadersConfig || "true".equalsIgnoreCase(servletRequest.getHeader(FCHeaders.X_FRONTCACHE_TRACE)))
        {
            context.setLogToHTTPHeaders();
        }

        String requestId = servletRequest.getHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID);
        String requestType = FCHeaders.COMPONENT_INCLUDE;

        String includeLevelStr = servletRequest.getHeader(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL);

        if (null == requestId)
        {
        	requestId = UUID.randomUUID().toString();
        	requestType = FCHeaders.COMPONENT_TOPLEVEL;
        } else {

        	// can be include
        	// or top_level (e.g. FC1 in scenario browser -> FC2 -> FC1 -> app )

        	if ("0".equals(includeLevelStr)) // it's second level FC (e.g. FC1 in scenario browser -> FC2 -> FC1 -> app )
        	{
            	requestType = FCHeaders.COMPONENT_TOPLEVEL;
        	} else {
            	if (null != servletRequest.getHeader(FCHeaders.X_FRONTCACHE_ASYNC_INCLUDE))
                	requestType = FCHeaders.COMPONENT_ASYNC_INCLUDE;
            	else
            		requestType = FCHeaders.COMPONENT_INCLUDE;
        	}

            context.setRequestFromFrontcache();
        }

        if (null == includeLevelStr)
        	includeLevelStr = INCLUDE_LEVEL_TOP_LEVEL; // default (top level)

        context.setIncludeLevel(includeLevelStr); // top level

        context.setRequestId(requestId);
        context.setRequestType(requestType);

        // Did this come from a Frontcache of the same chain? Before the guard rules, whose edge-token
        // predicate reads it, and on the request thread, while the header is still readable.
        context.setEdgeTokenValid(EdgeToken.isValid(servletRequest));

        // Resolved here, on the request thread, for two reasons: it is a 13-header scan that every log
        // line and the origin call would otherwise repeat, and it is the only point at which the answer
        // is still knowable - an async include reads it after the container has recycled the request.
        context.setClientIP(FCUtils.getClientIP(servletRequest));

        // The cache warmer's own requests carry the run's client type and skip bots.conf - which would
        // otherwise classify the loopback peer, 127.0.0.1. Before setClientType, which leaves a warm
        // request's type alone, and before the guard rules, which a warm request skips.
        WarmRequest.resolve(servletRequest, context);

        // client type = bot | guest, from conf/bots.conf. After setRequest/setRequestURI above and
        // after the client IP: a rule may read any of them, and the servlet request is still live here.
        setClientType(context);

        if (null != filterChain)
            context.setFilterChain(filterChain);

		return context;
    }

	/**
	 * Client type - bot | guest - from conf/bots.conf.
	 *
	 * Until 2.9.0 this was a case-sensitive substring scan of the User-Agent and nothing else;
	 * {@link BotClassifier} now evaluates an ordered rule list that can also read the client IP,
	 * cookies, headers and the request shape. The output vocabulary is unchanged and must stay that
	 * way: it keys the expireTimeMap of every entry in the L2 index.
	 *
	 * Called from init(), BEFORE the guard rules run - their client-type: predicate reads what this
	 * writes, and that ordering must not drift.
	 */
	/** what RequestContext.getClientTypeRule() reports when the decision came from the page's node */
	static final String CLIENT_TYPE_PROPAGATED = "propagated";

	private void setClientType(RequestContext context)
	{
		// Decided by the warm run, not by bots.conf - see WarmRequest. Like a propagated type, no rule
		// counts a hit for it.
		if (context.isWarmRequest())
			return;

		String propagated = FCUtils.propagatedClientType(context);
		if (null != propagated)
		{
			context.setClientType(propagated);
			context.setClientTypeRule(CLIENT_TYPE_PROPAGATED);
			return;
		}

		BotRule rule = BotClassifier.getInstance().classify(context);
		context.setClientType(rule.getClientType());
		context.setClientTypeRule(rule.getName());
	}


    private boolean ignoreCache(String uri)
    {
    	for (Pattern p : FCConfig.getDynamicURLPatterns())
    		if (p.matcher(uri).find())
    			return true;

    	return false;

    }

	public void processRequest(HttpServletRequest servletRequest, HttpServletResponse servletResponse, FilterChain filterChain) throws Exception
	{
		RequestContext context = init(servletRequest, servletResponse, filterChain);

		// The warmer is the operator, authenticated twice (the API key that started the run, and the
		// token), and its peer is this node. A rate: rule keyed on the client IP would throttle it and a
		// client-ip: block could refuse it outright - the guard protects the origin from clients.
		if (context.isWarmRequest())
		{
			new FC_Total(this, context).execute();
			return;
		}

		// Guard rules: reject or redirect abusive/pointless requests before they reach cache or
		// origin. An abusive request is a unique cache key that misses the cache and hits origin -
		// the source of the origin overload from a broken crawler's compounding &amp;-mangled query
		// strings - while a scanner or a logged-out visitor on an account-only page costs a full
		// origin render for a response nobody wanted.
		GuardOutcome guardOutcome = guardRuleEngine.evaluate(context);

		// dry-run rules only observe: they are logged, then the request carries on
		for (GuardRule dryRunRule : guardOutcome.getDryRunMatches())
			logGuardAction(context, dryRunRule.getName() + ":dry-run", 0, GuardAction.DISPOSITION_DRY_RUN);

		if (guardOutcome.isTerminal())
		{
			applyGuardAction(context, guardOutcome);
			return;
		}

		new FC_Total(this, context).execute();
//		processRequestInternal();
		return;
	}

	/**
	 * Serves a guard rule's response - a rejection (e.g. 400 / 414) or a redirect - without touching
	 * cache or origin, and records it in the failed-requests log (not error.log, to avoid a flood).
	 *
	 * @param outcome terminal outcome from the guard engine, carrying the rule that matched
	 */
	private void applyGuardAction(RequestContext context, GuardOutcome outcome)
	{
		GuardRule rule = outcome.getRule();
		GuardAction action = outcome.getAction();
		HttpServletResponse servletResponse = context.getResponse();
		int status = action.getStatus();

		try {
			servletResponse.setStatus(status);

			// Never let a browser or CDN cache a guard response - REJECTS INCLUDED. The decision
			// depends on the request (host, cookies) or on how fast this client is sending, and must
			// be re-made at the edge on every visit: a cached 429 would pin a client to a rejection
			// long after its window ended.
			servletResponse.setHeader(FCHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0, must-revalidate");

			// Set by the rate-limit predicate: seconds until the client's window rolls over. RFC 6585
			// expects it on a 429, and a well-behaved crawler backs off when it sees one.
			int retryAfterSeconds = context.getGuardRetryAfterSeconds();
			if (0 < retryAfterSeconds)
				servletResponse.setHeader(FCHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds));

			if (GuardAction.Type.REDIRECT == action.getType())
			{
				servletResponse.setHeader("Location", outcome.getResolvedTarget());
			} else {
				servletResponse.setContentType("text/plain; charset=UTF-8");
				servletResponse.getWriter().write(action.getBody());
			}
		} catch (Exception e) {
			logger.debug("Error writing guard rule '" + rule.getName() + "' response (" + status + "): " + e);
		}

		logGuardAction(context, guardReason(context, rule), status, action.getDisposition());
		return;
	}

	/**
	 * The reason column of the guard log line: the rule name, and for a rate-limit rule the address
	 * that was actually counted, as "&lt;rule&gt;#&lt;address&gt;".
	 *
	 * The limiter resolves the client address differently from FCUtils.getClientIP() - it reads a
	 * forwarding header only from a configured trusted proxy - so the two CAN disagree, and the log's
	 * own client-IP column may name an address the limiter refused to trust. "Which address was
	 * counted?" is the first question anyone debugging a 429 asks, so it is recorded rather than left
	 * to be inferred.
	 */
	private String guardReason(RequestContext context, GuardRule rule)
	{
		if (0 == context.getGuardRetryAfterSeconds())
			return rule.getName();

		String limiterKey = context.getRateLimitKey();
		return (null != limiterKey) ? rule.getName() + "#" + limiterKey : rule.getName();
	}

	/**
	 * @return the guard pipeline this node is running - used by the management API
	 *         (get-guard-rules / reload-guard-rules) and, through it, by the console
	 */
	public GuardRuleEngine getGuardRuleEngine() {
		return guardRuleEngine;
	}

	private void logGuardAction(RequestContext context, String reason, int status, String disposition)
	{
		RequestLogger.logGuardedRequest(
				context.getFrontCacheHost() + context.getRequestURI() + context.getRequestQueryString(),
				reason,
				status,
				disposition,
				context);
		return;
	}

	/**
	 * do it outside init() because it's for cacheable requests only
	 *
	 * @param context
	 * @return
	 */
	private void getCurrentRequestURL2Context(RequestContext context)
	{
		HttpServletRequest httpRequest = context.getRequest();
		String portStr = "";
		int port = httpRequest.getServerPort();
		if (80 == port && "http".equals(context.getFrontCacheProtocol()))
			portStr = "";
		else if (443 == port && "https".equals(context.getFrontCacheProtocol()))
			portStr = "";
		else
			portStr = ":" + port;

		String currentRequestURL = context.getFrontCacheProtocol() + "://" + context.getFrontCacheHost() + portStr + context.getRequestURI() + context.getRequestQueryString();
		context.setCurrentRequestURL(currentRequestURL);
		logger.debug("currentRequestURL: " + currentRequestURL);

		return;
	}
    /**
     *
     * @throws Exception
     */
	public void processRequestInternal(RequestContext context) throws Exception
	{
		HttpServletRequest httpRequest = context.getRequest();
		// The context already carries this - init() resolved it. Recomputing it here was the second of
		// the two per-request URL parses.
		String originRequestURL = context.getOriginURL() + context.getRequestURI() + context.getRequestQueryString();
		logger.debug("originRequestURL: " + originRequestURL);

		boolean isSecure = ("https".equalsIgnoreCase(context.getFrontCacheProtocol())) ? true : false;
		String currentRequestBaseURL = makeURL(isSecure, context.getFrontCacheHost(), "" + httpRequest.getServerPort());
		logger.debug("currentRequestBaseURL: " + currentRequestBaseURL);

		boolean dynamicRequest = ("true".equals(httpRequest.getHeader(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST))) ? true : false;

		if (!dynamicRequest && context.isCacheableRequest() && !ignoreCache(context.getRequestURI())) // GET method without jsessionid
		{
			long start = System.currentTimeMillis();

			Map<String, List<String>> requestHeaders = FCUtils.buildRequestHeaders(httpRequest);
			getCurrentRequestURL2Context(context);

			WebResponse webResponse = null;
			try
			{
				webResponse = cacheProcessor.processRequest(originRequestURL, requestHeaders, fcHttpClient, context);
			} catch (FrontCacheException fce) {
				// content/response is not cacheable (e.g. response type is not text)
				fce.printStackTrace();
			}

			if (null != webResponse)
			{
				// include processor
				// don't process includes if request from Frontcache (e.g. Browser -> FC -> [FC] -> Origin)
				if (!context.getRequestFromFrontcache() && null != webResponse.getContent())
				{
					// check process includes with recursion (resolve includes up to deepest level defined in includeProcessor)
					int recursionLevel = 0;
					while (includeProcessor.hasIncludes(webResponse, recursionLevel++))
					{
						// include processor return new webResponse with processed includes and merged headers
						WebResponse incWebResponse = includeProcessor.processIncludes(webResponse, currentRequestBaseURL, requestHeaders, fcHttpClient, context, recursionLevel);

						// copy content only (cache setting use this (parent), headers are merged inside IncludeProcessor )
						webResponse.setContent(incWebResponse.getContent());
					}
				}


				if (INCLUDE_LEVEL_TOP_LEVEL.equals(context.getIncludeLevel()))
				{
					RequestLogger.logRequestToHeader(
							currentRequestBaseURL + context.getRequestURI() + context.getRequestQueryString(),
							context.getRequestType(),
							context.isToplevelCached(), // isCached
							false, // soft refresh
							System.currentTimeMillis() - start,
							webResponse.getContentLenth(), // lengthBytes
							context,
							INCLUDE_LEVEL_TOP_LEVEL); // includeLevel
				}

				addResponseHeaders(webResponse, context);
				try {
					writeResponse(webResponse, context);
				} finally {
					// close in finally: writeResponse streams the origin body to the client and can throw
					// (e.g. broken pipe on client disconnect) - without finally the pooled connection leaks.
					if (null != context.getHttpClientResponse())
						context.getHttpClientResponse().close();
				}

				return;
			}

		}

		// do dynamic call to origin (all methods except GET + listed in ignore list)
		{
			long start = System.currentTimeMillis();
			boolean isRequestCacheable = false;
			boolean isCached = false;
			long lengthBytes = -1; // TODO: set/get content length from context or just keep -1 ?

//			forwardToOrigin();
			new FC_BypassCache(httpClient, context).execute();

			try {
				// A Frontcache that marked its OWN call dynamic logs that leg itself, on the requesting
				// side - a cache-missing include fetched straight from the origin in filter mode (where
				// the origin is this same node, so the fetch necessarily comes back here), or a combine
				// envelope. Logging it again here would double every such leg in the include counts, and
				// do it under the origin-facing url with no byte count, where the requesting side has
				// the public url and the real length. Whoever set the flag owns the line.
				boolean loggedByCaller = dynamicRequest && context.getRequestFromFrontcache();

				if (!loggedByCaller)
					RequestLogger.logRequest(originRequestURL, isRequestCacheable, isCached, System.currentTimeMillis() - start, lengthBytes, context);

				// Exactly one writer. When the command fell back, FC_BypassCache parked the fallback
				// rather than writing it, so serve that and ignore whatever the abandoned origin call
				// may have left in the context - writing both concatenated two bodies under a status
				// code (default 500) that overwrote the fallback's own.
				WebResponse fallbackWebResponse = context.getFallbackWebResponse();
				if (null != fallbackWebResponse)
					writeFallbackResponse(fallbackWebResponse, context);
				else {
					addResponseHeaders(context);
					writeResponse(context);
				}
			} finally {
				// release in finally: FC_BypassCache stashed the open origin response in the context, and
				// writeResponse streams its body to the client - which can throw (e.g. broken pipe on
				// client disconnect). Without finally the pooled connection leaks and the pool exhausts.
				// Don't close directly - on a command TIMEOUT the worker thread is still opening the
				// response and would stash it after we got here; releaseHttpClientResponse() hands the
				// close to whichever of the two threads finishes last. See RequestContext.
				context.releaseHttpClientResponse();
			}
		}
		return;
	}


	/**
	 * The charset the response declares, falling back to UTF-8. Only used for the String-bodied
	 * branch of {@link #writeResponse(RequestContext)}.
	 */
	private static Charset responseCharset(RequestContext context) {
		try {
			HttpServletResponse servletResponse = context.getResponse();
			String encoding = (null == servletResponse) ? null : servletResponse.getCharacterEncoding();
			if (null != encoding && 0 < encoding.length())
				return Charset.forName(encoding);
		} catch (Exception e) {
			// unknown / unsupported charset name - fall through to UTF-8
		}
		return StandardCharsets.UTF_8;
	}

	private void writeResponse(RequestContext context) throws Exception {
		// there is no body to send
		if (context.getResponseBody() == null && context.getResponseDataStream() == null) {
			return;
		}
		HttpServletResponse servletResponse = context.getResponse();
		OutputStream outStream = servletResponse.getOutputStream();
		InputStream is = null;
		try {
			if (context.getResponseBody() != null) {
				// Nothing in the codebase calls setResponseBody() today, so this branch is currently
				// unreachable - but it is public API on RequestContext. Written directly rather than
				// streamed through a ByteArrayInputStream, and with an explicit charset: getBytes()
				// used the platform default, which is not necessarily the encoding the response
				// declares.
				outStream.write(context.getResponseBody().getBytes(responseCharset(context)));
				return;
			}

			is = context.getResponseDataStream();
			InputStream inputStream = is;
			if (is != null) {
				FCUtils.writeResponse(inputStream, outStream);
			}

		}
		finally {
			try {
				if (is != null) {
					is.close();
				}
				outStream.flush();
				outStream.close();
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}

	}

	/**
	 * Writes the fallback resolved by FC_BypassCache. Headers first, body second, on the request
	 * thread only - the fallback carries its own status and content type, so unlike the origin path
	 * it must not have the context's status code applied over it.
	 */
	private void writeFallbackResponse(WebResponse webResponse, RequestContext context) throws Exception {

		HttpServletResponse servletResponse = context.getResponse();

		FCUtils.applyFallbackHeaders(servletResponse, webResponse);
		addFrontCacheMarkerHeaders(context);

		byte[] content = webResponse.getContent();
		if (null == content)
			return;

		OutputStream outStream = servletResponse.getOutputStream();
		try {
			// The body is already a byte[] - write it. Streaming it through a ByteArrayInputStream
			// only to have it copied back out 16 KB at a time is a second pass over the whole page.
			outStream.write(content);
		}
		finally {
			try {
				outStream.flush();
				outStream.close();
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}

	private void writeResponse(WebResponse webResponse, RequestContext context) throws Exception {

		// there is no body to send
		if (null == webResponse.getContent()) {
			return;
		}

		HttpServletResponse servletResponse = context.getResponse();
		servletResponse.setCharacterEncoding("UTF-8");
		OutputStream outStream = servletResponse.getOutputStream();
		try {
			// This is the cache-hit path: the body is a fully materialised byte[], so write it in one
			// call. It used to be wrapped in a ByteArrayInputStream and copied back out through
			// FCUtils.writeResponse's buffer - a whole extra pass over every page served from cache.
			outStream.write(webResponse.getContent());
		}
		finally {
			try {
				outStream.flush();
				outStream.close();
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}

		return;
	}

	/**
	 * Writes the buffered X-frontcache-trace-* headers onto the response. Called on the request thread
	 * only - the include-processor worker threads must never touch the servlet response themselves
	 * (HttpServletResponse / Jetty's HttpFields are not thread safe: two threads in setHeader() race on
	 * the field list and one of them reads a null slot -> NPE in HttpFields$Mutable$Wrapper.put).
	 * Best effort: an include that overran its timeout may still be resolving, its trace header is then
	 * simply not part of the response - the request itself is still in the log files.
	 */
	private void flushTraceHeaders(RequestContext context) {
		HttpServletResponse servletResponse = context.getResponse();
		if (null == servletResponse || servletResponse.isCommitted())
			return;

		try {
			for (Map.Entry<String, String> traceHeader : context.getTraceHeaders().entrySet())
				servletResponse.setHeader(traceHeader.getKey(), traceHeader.getValue());
		} catch (IllegalStateException e) {
			logger.debug("skipped trace headers for {} - response already committed/recycled", context.getRequestURI());
		}
	}

	/**
	 * Frontcache's own tracing headers - stamped on every response, whether it came from the origin,
	 * the cache or a fallback.
	 */
	private void addFrontCacheMarkerHeaders(RequestContext context) {
		HttpServletResponse servletResponse = context.getResponse();

		flushTraceHeaders(context);

		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_ID, fcHostId);
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());

		if (context.isFallbackUsed())
			servletResponse.addHeader(FCHeaders.X_FRONTCACHE_FALLBACK_IS_USED, "true");

		// The bypass path: never stored, so "not-cacheable" unless a fallback was served instead
		addWarmResultHeader(context, context.isFallbackUsed() ? WarmRequest.RESULT_FALLBACK : WarmRequest.RESULT_NOT_CACHEABLE);
	}

	/**
	 * {@code x-frontcache-warm-result}, on a warm request only. What the cache processor recorded wins;
	 * {@code defaultResult} answers for a path that never reached it. Beside it, the key the request
	 * was cached under ({@code x-frontcache-warm-key}), when it got as far as building one.
	 */
	private void addWarmResultHeader(RequestContext context, String defaultResult) {
		if (!context.isWarmRequest())
			return;

		String result = context.getWarmResult();
		if (null == result)
			result = defaultResult;

		context.getResponse().setHeader(FCHeaders.X_FRONTCACHE_WARM_RESULT, result);

		if (null != context.getCurrentRequestURL())
			context.getResponse().setHeader(FCHeaders.X_FRONTCACHE_WARM_KEY, context.getCurrentRequestURL());
	}

	private void addResponseHeaders(RequestContext context) {
		HttpServletResponse servletResponse = context.getResponse();
		Map<String, List<String>> originResponseHeaders = context.getOriginResponseHeaders();
		// process redirects
		if (null != originResponseHeaders.get("Location") && 0 < originResponseHeaders.get("Location").size())
		{
			String originLocation = originResponseHeaders.remove("Location").iterator().next();

			String fcLocation = FCUtils.transformRedirectURL(originLocation, context);
			originResponseHeaders.put("Location",  Arrays.asList(new String[]{fcLocation}));
		}

		addFrontCacheMarkerHeaders(context);

		servletResponse.setStatus(context.getResponseStatusCode());

		if (originResponseHeaders != null) {
			for (String key : originResponseHeaders.keySet()) {
				for (String value : originResponseHeaders.get(key)) {
					servletResponse.addHeader(key, value);
				}
			}
		}

		Long contentLength = context.getOriginContentLength();
		// Only inserts Content-Length if origin provides it and origin response is not
		// gzipped
//		if (SET_CONTENT_LENGTH.get()) {
			if (contentLength != null && !context.getResponseGZipped()) {
				servletResponse.setContentLength(contentLength.intValue());
			}
//		}
	}

	private void addResponseHeaders(WebResponse webResponse, RequestContext context) {
		HttpServletResponse servletResponse = context.getResponse();

		servletResponse.setStatus(webResponse.getStatusCode());

		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_ID, fcHostId);
		servletResponse.addHeader(FCHeaders.X_FRONTCACHE_REQUEST_ID, context.getRequestId());

		flushTraceHeaders(context);

		addWarmResultHeader(context, WarmRequest.RESULT_NOT_CACHEABLE);

		if (webResponse.getHeaders() != null) {
			for (String name : webResponse.getHeaders().keySet()) {
				for (String value : webResponse.getHeaders().get(name)) {

					if (null == servletResponse.getHeader(name)) // if header already exist (e.g. in case of WebFilter) - do not duplicate
						servletResponse.addHeader(name, value);
				}
			}
		}
	}


}
