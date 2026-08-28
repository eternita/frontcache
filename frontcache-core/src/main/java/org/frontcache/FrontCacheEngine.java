/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
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
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheEngine {


	// This node's site. One node serves one site - see docs/archive/multi-domain-retirement-proposal.md.
	private String domain;          // front-cache.default-domain
	private String siteKey;         // front-cache.site-key
	private String originHost;      // front-cache.origin-host
	private String originHttpPort;  // front-cache.origin-http-port
	private String originHttpsPort; // front-cache.origin-https-port

	private String frontcacheHttpPort = null;

	private String frontcacheHttpsPort = null;

	private String fcHostId = null;	// used to determine which front cache processed request (forwarded by GEO Load Balancer e.g. route53 AWS)

    private boolean logToHeadersConfig = false; // log requests/includes performance stat to HTTP headers

	public final static String DEFAULT_FRONTCACHE_HOST_NAME_VALUE = "undefined-front-cache-host";

	// Connection pool must be at least as large as the pools that draw from it: the
	// OriginHitsPool (resilience.threadpool.OriginHitsPool.coreSize) plus the include-processor threads
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
	// docs/frontcache-protection-tech-design.md.
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
	// an Apache type. See docs/httpclient5-upgrade-proposal.md section 5.1.
	private FcHttpClient fcHttpClient;

	public static boolean debugComments = false; // if true - appends debug comments (for includes) to output

	// volatile + double-checked locking - see CacheManager.getInstance(): the unsynchronized
	// check-then-act let concurrent first requests build two engines, each initializing its own
	// cache processor / Lucene writer on one index dir.
	private static volatile FrontCacheEngine instance;

    private static final String INCLUDE_LEVEL_TOP_LEVEL = "0";

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
	 * @return true when {@code siteKey} is this node's configured management site key
	 */
	public boolean isNodeSiteKey(String siteKey)
	{
		return null != siteKey && 0 < siteKey.length() && siteKey.equals(this.siteKey);
	}

	// synchronized on the same monitor as getFrontCache() so a shutdown/reload can't interleave with
	// a lazy init and leave two engines (and two Lucene writers) alive.
	public static void destroy() {
		synchronized (FrontCacheEngine.class) {
			if (null != instance) {
				instance.stop();
				instance = null;
			}
		}
	}

	/**
	 *
	 */
	public static void reload() {

		destroy();

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
	// several. See docs/archive/multi-domain-retirement-proposal.md.
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
		  .append("\n\nSee docs/archive/multi-domain-retirement-proposal.md");

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
		siteKey         = FCConfig.getProperty("front-cache.site-key", "");
		originHost      = FCConfig.getProperty("front-cache.origin-host", "localhost");
		originHttpPort  = FCConfig.getProperty("front-cache.origin-http-port", "80");
		originHttpsPort = FCConfig.getProperty("front-cache.origin-https-port", "443");

		// Built here, immediately after the values they depend on - so a bad origin-host fails at
		// startup with a clear message rather than on the first request.
		buildOriginUrls();

		logger.info("Serving domain " + domain + " from origin " + originHost
				+ " (http " + originHttpPort + " / https " + originHttpsPort + ")");

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


	private void stop() {

		FCConfig.destroy();

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
		// See docs/httpclient5-upgrade-proposal.md section 4.1.
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
				// See docs/httpclient5-upgrade-proposal.md section 4.3.1.
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

        context.setClientType(getClientType(servletRequest)); // client type = bot | browser based on User-Agent Header and bots.conf

        // Resolved here, on the request thread, for two reasons: it is a 13-header scan that every log
        // line and the origin call would otherwise repeat, and it is the only point at which the answer
        // is still knowable - an async include reads it after the container has recycled the request.
        context.setClientIP(FCUtils.getClientIP(servletRequest));

        if (null != filterChain)
            context.setFilterChain(filterChain);

		return context;
    }

	private String getClientType(HttpServletRequest request)
	{

		String userAgent = request.getHeader("User-Agent");
		if (null != userAgent)
		{
			for (String botKeyword : FCConfig.getBotUserAgentKeywords())
				if (userAgent.contains(botKeyword))
					return FCHeaders.REQUEST_CLIENT_TYPE_BOT;
		} else {
			return FCHeaders.REQUEST_CLIENT_TYPE_BOT; // no user-agent -> bot
		}

		return FCHeaders.REQUEST_CLIENT_TYPE_BROWSER;
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
			if (GuardAction.Type.REDIRECT == action.getType())
			{
				servletResponse.setStatus(status);
				servletResponse.setHeader("Location", outcome.getResolvedTarget());
				// never let a browser or CDN cache the decision: it depends on the request (host,
				// cookies) and must be re-made at the edge on every visit
				servletResponse.setHeader(FCHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0, must-revalidate");
			} else {
				servletResponse.setStatus(status);
				servletResponse.setContentType("text/plain; charset=UTF-8");
				servletResponse.getWriter().write(action.getBody());
			}
		} catch (Exception e) {
			logger.debug("Error writing guard rule '" + rule.getName() + "' response (" + status + "): " + e);
		}

		logGuardAction(context, rule.getName(), status, action.getDisposition());
		return;
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
