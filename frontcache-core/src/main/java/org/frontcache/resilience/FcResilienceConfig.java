/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.frontcache.FCConfig;
import org.frontcache.resilience.FcCommandConfig.Isolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads conf/resilience.properties into plain {@link Properties} and resolves per-command settings from
 * it, replacing the Archaius {@code ConfigurationManager.install()} path.
 *
 * That replacement is most of the point. Archaius accepted an install once per JVM, globally, which is
 * why {@code FCConfig} used to carry an {@code isConfigurationInstalled()} branch and why
 * {@code FrontcacheConfigBootstrapListener} existed at all: whoever touched Archaius first won, so if
 * the dashboard stream was hit before any page request, every per-command override in the file was
 * silently dropped. Here the file is just a Properties object read at first use. Both workarounds are
 * deleted and that entire class of startup-ordering bug is no longer possible. See proposal section 6.5.
 *
 * Key resolution: {@code resilience.command.<key>.<prop>}, then {@code hystrix.command.<key>.<prop>},
 * then the same two at {@code default} scope, then the built-in default. The {@code hystrix.*} prefix is
 * the pre-2.7 spelling and stays accepted indefinitely - it costs one lookup and it is the difference
 * between an upgrade that silently reverts every tuning key to its default and one that does not. The
 * legacy FILE NAME ({@code conf/hystrix.properties}) is read as a fallback too, but only for one
 * release, and it warns.
 */
public class FcResilienceConfig {

	private static final String PROPERTIES_FILE = "resilience.properties";
	private static final String LEGACY_PROPERTIES_FILE = "hystrix.properties";

	private static final String COMMAND_PREFIX = "resilience.command.";
	private static final String THREADPOOL_PREFIX = "resilience.threadpool.";
	private static final String STREAM_MAX_CONNECTIONS_KEY = "resilience.config.stream.maxConcurrentConnections";
	private static final String LEGACY_COMMAND_PREFIX = "hystrix.command.";
	private static final String LEGACY_THREADPOOL_PREFIX = "hystrix.threadpool.";
	private static final String LEGACY_STREAM_MAX_CONNECTIONS_KEY = "hystrix.config.stream.maxConcurrentConnections";
	private static final String DEFAULT_SCOPE = "default";

	// Hystrix built-in defaults, for keys the file does not set.
	static final int DEFAULT_TIMEOUT_MS = 1000;
	static final boolean DEFAULT_INTERRUPT_ON_TIMEOUT = true;
	static final int DEFAULT_SEMAPHORE_MAX_CONCURRENT = 10;
	static final int DEFAULT_FALLBACK_SEMAPHORE_MAX_CONCURRENT = 10;
	static final int DEFAULT_ERROR_THRESHOLD_PERCENTAGE = 50;
	static final int DEFAULT_REQUEST_VOLUME_THRESHOLD = 20;
	static final int DEFAULT_SLEEP_WINDOW_MS = 5000;
	public static final int DEFAULT_ROLLING_STATS_MS = 10000;
	static final int DEFAULT_CORE_SIZE = 10;
	static final int DEFAULT_MAX_QUEUE_SIZE = -1;
	static final int DEFAULT_KEEP_ALIVE_MINUTES = 1;
	static final int DEFAULT_STREAM_MAX_CONCURRENT_CONNECTIONS = 5;

	private static final Logger logger = LoggerFactory.getLogger(FcResilienceConfig.class);

	private static volatile FcResilienceConfig instance;

	private final Properties props;
	private final ConcurrentMap<String, FcCommandConfig> commandConfigs = new ConcurrentHashMap<String, FcCommandConfig>();
	private final AtomicBoolean fallbackSemaphoreWarned = new AtomicBoolean(false);

	/** Which file the settings actually came from, so warnings name the file the operator has to edit. */
	private final String loadedFrom;

	FcResilienceConfig(Properties props) {
		this(props, PROPERTIES_FILE);
	}

	FcResilienceConfig(Properties props, String loadedFrom) {
		this.props = (null == props) ? new Properties() : props;
		this.loadedFrom = loadedFrom;
	}

	public static FcResilienceConfig getInstance()
	{
		FcResilienceConfig local = instance;
		if (null == local)
		{
			synchronized (FcResilienceConfig.class) {
				local = instance;
				if (null == local)
				{
					local = load();
					instance = local;
				}
			}
		}
		return local;
	}

	/** Test seam - drops the cached instance so the next getInstance() re-reads. */
	static void reset()
	{
		instance = null;
	}

	/** Test seam - runs the machinery against an explicit properties set instead of the config file. */
	static void install(Properties properties)
	{
		instance = new FcResilienceConfig(properties);
	}

	/**
	 * Reads conf/resilience.properties, falling back to the pre-2.7 conf/hystrix.properties.
	 *
	 * The fallback is loud on purpose. A silent fallback would be indistinguishable from "the file was
	 * found", so nobody would ever rename it; a hard failure would turn an upgrade into an outage over a
	 * file name. It is scheduled for removal one release after the rename - the warning says so.
	 *
	 * If BOTH files exist the new one wins outright, without merging: two half-populated files with the
	 * same keys resolving to a union is exactly the kind of config nobody can reason about during an
	 * incident. The warning names the file that is being ignored.
	 */
	private static FcResilienceConfig load()
	{
		Properties loaded = read(PROPERTIES_FILE);
		if (null != loaded)
		{
			if (null != read(LEGACY_PROPERTIES_FILE))
				logger.warn("Both conf/" + PROPERTIES_FILE + " and conf/" + LEGACY_PROPERTIES_FILE + " exist."
						+ " Using conf/" + PROPERTIES_FILE + " and IGNORING conf/" + LEGACY_PROPERTIES_FILE
						+ " entirely - the two are not merged. Delete the legacy file.");

			return new FcResilienceConfig(loaded, PROPERTIES_FILE);
		}

		loaded = read(LEGACY_PROPERTIES_FILE);
		if (null != loaded)
		{
			logger.warn("conf/" + PROPERTIES_FILE + " not found - reading the legacy conf/" + LEGACY_PROPERTIES_FILE
					+ " instead. Rename the file; the legacy name is read for one release only. The"
					+ " hystrix.command.* / hystrix.threadpool.* KEY prefixes inside it stay supported"
					+ " regardless of the file name, so renaming the file is enough.");
			return new FcResilienceConfig(loaded, LEGACY_PROPERTIES_FILE);
		}

		logger.warn("Neither conf/" + PROPERTIES_FILE + " nor conf/" + LEGACY_PROPERTIES_FILE
				+ " could be read - every circuit-breaker, timeout, bulkhead and thread-pool setting"
				+ " falls back to its built-in default.");
		return new FcResilienceConfig(new Properties(), PROPERTIES_FILE);
	}

	/**
	 * @return the parsed file, or null when it does not exist or cannot be read.
	 *
	 * Absence has to be a null and not an exception, which is why the catch is Exception and not
	 * IOException: {@code FCConfig.getConfigInputStream} signals "nowhere on disk or classpath" by
	 * THROWING a RuntimeException, so a narrower catch would let a missing conf/resilience.properties
	 * escape before the legacy file name is ever tried - i.e. it would break precisely the upgrade this
	 * fallback exists for.
	 */
	private static Properties read(String fileName)
	{
		InputStream is = null;
		try {
			is = FCConfig.getConfigInputStream(fileName);
			if (null == is)
				return null;

			Properties loaded = new Properties();
			loaded.load(is);
			return loaded;
		} catch (Exception e) {
			logger.debug("Can't read " + fileName, e);
			return null;
		} finally {
			if (null != is)
				try { is.close(); } catch (IOException ignored) { }
		}
	}

	public FcCommandConfig forCommand(final String commandKey, final String threadPoolKey)
	{
		FcCommandConfig cfg = commandConfigs.get(commandKey);
		if (null != cfg)
			return cfg;

		cfg = buildCommandConfig(commandKey, threadPoolKey);
		FcCommandConfig raced = commandConfigs.putIfAbsent(commandKey, cfg);
		return (null != raced) ? raced : cfg;
	}

	private FcCommandConfig buildCommandConfig(String commandKey, String threadPoolKey)
	{
		String strategy = command(commandKey, "execution.isolation.strategy", "THREAD");
		Isolation isolation = "SEMAPHORE".equalsIgnoreCase(strategy.trim()) ? Isolation.SEMAPHORE : Isolation.THREAD;

		int fallbackSemaphore = commandInt(commandKey, "fallback.isolation.semaphore.maxConcurrentRequests",
				DEFAULT_FALLBACK_SEMAPHORE_MAX_CONCURRENT);
		warnIfFallbackSemaphoreConfigured(commandKey);

		return new FcCommandConfig(
				commandKey,
				isolation,
				commandInt(commandKey, "execution.isolation.thread.timeoutInMilliseconds", DEFAULT_TIMEOUT_MS),
				commandBoolean(commandKey, "execution.isolation.thread.interruptOnTimeout", DEFAULT_INTERRUPT_ON_TIMEOUT),
				commandInt(commandKey, "execution.isolation.semaphore.maxConcurrentRequests", DEFAULT_SEMAPHORE_MAX_CONCURRENT),
				fallbackSemaphore,
				commandInt(commandKey, "circuitBreaker.errorThresholdPercentage", DEFAULT_ERROR_THRESHOLD_PERCENTAGE),
				commandInt(commandKey, "circuitBreaker.requestVolumeThreshold", DEFAULT_REQUEST_VOLUME_THRESHOLD),
				commandInt(commandKey, "circuitBreaker.sleepWindowInMilliseconds", DEFAULT_SLEEP_WINDOW_MS),
				commandInt(commandKey, "metrics.rollingStats.timeInMilliseconds", DEFAULT_ROLLING_STATS_MS),
				threadPoolKey);
	}

	/**
	 * The fallback semaphore is parsed and reported but never enforced (proposal section 6.3). Say so
	 * once at startup: an operator who tunes the key would otherwise have no way to find out that
	 * nothing changed.
	 */
	private void warnIfFallbackSemaphoreConfigured(String commandKey)
	{
		boolean configured = null != commandRaw(commandKey, "fallback.isolation.semaphore.maxConcurrentRequests");

		if (configured && fallbackSemaphoreWarned.compareAndSet(false, true))
			logger.warn("fallback.isolation.semaphore.maxConcurrentRequests is set in " + loadedFrom
					+ " but is NOT enforced - it is parsed and reported on the dashboard only. Concurrent fallbacks are"
					+ " already bounded by execution.isolation.semaphore.maxConcurrentRequests, because a call only"
					+ " reaches its fallback while holding that permit. Tuning this key has no effect.");
	}

	// ---- thread pools -------------------------------------------------------

	public int poolCoreSize(String poolKey)
	{
		return threadPoolInt(poolKey, "coreSize", DEFAULT_CORE_SIZE);
	}

	public int poolMaximumSize(String poolKey)
	{
		return threadPoolInt(poolKey, "maximumSize", poolCoreSize(poolKey));
	}

	/** Hystrix default is -1, meaning "no queue" - a SynchronousQueue, not an unbounded one. */
	public int poolMaxQueueSize(String poolKey)
	{
		return threadPoolInt(poolKey, "maxQueueSize", DEFAULT_MAX_QUEUE_SIZE);
	}

	public int poolKeepAliveMinutes(String poolKey)
	{
		return threadPoolInt(poolKey, "keepAliveTimeMinutes", DEFAULT_KEEP_ALIVE_MINUTES);
	}

	public int streamMaxConcurrentConnections()
	{
		String value = props.getProperty(STREAM_MAX_CONNECTIONS_KEY);
		if (null == value)
			value = props.getProperty(LEGACY_STREAM_MAX_CONNECTIONS_KEY);

		return asInt(STREAM_MAX_CONNECTIONS_KEY, value, DEFAULT_STREAM_MAX_CONCURRENT_CONNECTIONS);
	}

	/** Every command key the file mentions - used by tests and by the config dump. */
	public List<String> configuredCommandKeys()
	{
		List<String> keys = new ArrayList<String>();
		for (Object raw : props.keySet())
		{
			String key = raw.toString();
			String rest;
			if (key.startsWith(COMMAND_PREFIX))
				rest = key.substring(COMMAND_PREFIX.length());
			else if (key.startsWith(LEGACY_COMMAND_PREFIX))
				rest = key.substring(LEGACY_COMMAND_PREFIX.length());
			else
				continue;

			int dot = rest.indexOf('.');
			if (dot < 1)
				continue;

			String commandKey = rest.substring(0, dot);
			if (!DEFAULT_SCOPE.equals(commandKey) && !keys.contains(commandKey))
				keys.add(commandKey);
		}
		return keys;
	}

	// ---- lookup helpers -----------------------------------------------------

	private String command(String commandKey, String property, String defaultValue)
	{
		String value = commandRaw(commandKey, property);
		return (null == value) ? defaultValue : value;
	}

	/**
	 * Specific scope beats default scope; within one scope the new prefix beats the legacy one. Getting
	 * that order the other way round would mean a file carrying both spellings resolved
	 * {@code hystrix.command.default.*} over {@code resilience.command.<key>.*}, i.e. a legacy default
	 * silently overriding a new per-command override.
	 */
	private String commandRaw(String commandKey, String property)
	{
		String value = props.getProperty(COMMAND_PREFIX + commandKey + "." + property);
		if (null == value)
			value = props.getProperty(LEGACY_COMMAND_PREFIX + commandKey + "." + property);
		if (null == value)
			value = props.getProperty(COMMAND_PREFIX + DEFAULT_SCOPE + "." + property);
		if (null == value)
			value = props.getProperty(LEGACY_COMMAND_PREFIX + DEFAULT_SCOPE + "." + property);
		return value;
	}

	private int commandInt(String commandKey, String property, int defaultValue)
	{
		return asInt(COMMAND_PREFIX + commandKey + "." + property, command(commandKey, property, null), defaultValue);
	}

	private boolean commandBoolean(String commandKey, String property, boolean defaultValue)
	{
		String value = command(commandKey, property, null);
		return (null == value) ? defaultValue : Boolean.parseBoolean(value.trim());
	}

	private int threadPoolInt(String poolKey, String property, int defaultValue)
	{
		String value = props.getProperty(THREADPOOL_PREFIX + poolKey + "." + property);
		if (null == value)
			value = props.getProperty(LEGACY_THREADPOOL_PREFIX + poolKey + "." + property);
		if (null == value)
			value = props.getProperty(THREADPOOL_PREFIX + DEFAULT_SCOPE + "." + property);
		if (null == value)
			value = props.getProperty(LEGACY_THREADPOOL_PREFIX + DEFAULT_SCOPE + "." + property);
		return asInt(THREADPOOL_PREFIX + poolKey + "." + property, value, defaultValue);
	}

	/**
	 * A malformed number falls back to the default and logs, rather than failing startup. The file is
	 * operator-edited and a typo in one tuning key should not take the node down - but it must not
	 * pass silently either, since the symptom would be "my timeout is ignored".
	 */
	private static int asInt(String key, String value, int defaultValue)
	{
		if (null == value)
			return defaultValue;

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			logger.warn("Ignoring malformed value '" + value + "' for " + key + " - using " + defaultValue);
			return defaultValue;
		}
	}
}
