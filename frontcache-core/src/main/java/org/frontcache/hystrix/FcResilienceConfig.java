/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.frontcache.FCConfig;
import org.frontcache.hystrix.FcCommandConfig.Isolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads conf/hystrix.properties into plain {@link Properties} and resolves per-command settings from
 * it, replacing the Archaius {@code ConfigurationManager.install()} path.
 *
 * That replacement is most of the point. Archaius accepts an install once per JVM, globally, which is
 * why {@code FCConfig} carries an {@code isConfigurationInstalled()} branch and why
 * {@code FrontcacheConfigBootstrapListener} exists at all: whoever touches Archaius first wins, so if
 * /hystrix.stream is hit before any page request, every per-command override in the file is silently
 * dropped. Here the file is just a Properties object read at first use, and that entire class of
 * startup-ordering bug stops being possible. See proposal section 6.5.
 *
 * Key resolution matches Hystrix: {@code hystrix.command.<key>.<prop>}, then
 * {@code hystrix.command.default.<prop>}, then the Hystrix built-in default.
 */
public class FcResilienceConfig {

	private static final String HYSTRIX_PROPERTIES_FILE = "hystrix.properties";

	private static final String COMMAND_PREFIX = "hystrix.command.";
	private static final String THREADPOOL_PREFIX = "hystrix.threadpool.";
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

	FcResilienceConfig(Properties props) {
		this.props = (null == props) ? new Properties() : props;
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
					local = new FcResilienceConfig(load());
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

	private static Properties load()
	{
		Properties loaded = new Properties();
		InputStream is = null;
		try {
			is = FCConfig.getConfigInputStream(HYSTRIX_PROPERTIES_FILE);
			if (null != is)
				loaded.load(is);
		} catch (IOException e) {
			logger.error("Can't read " + HYSTRIX_PROPERTIES_FILE + " - falling back to built-in defaults", e);
		} finally {
			if (null != is)
				try { is.close(); } catch (IOException ignored) { }
		}
		return loaded;
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
		boolean configured = null != props.getProperty(COMMAND_PREFIX + commandKey + ".fallback.isolation.semaphore.maxConcurrentRequests")
				|| null != props.getProperty(COMMAND_PREFIX + DEFAULT_SCOPE + ".fallback.isolation.semaphore.maxConcurrentRequests");

		if (configured && fallbackSemaphoreWarned.compareAndSet(false, true))
			logger.warn("fallback.isolation.semaphore.maxConcurrentRequests is set in " + HYSTRIX_PROPERTIES_FILE
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
		return asInt("hystrix.config.stream.maxConcurrentConnections",
				props.getProperty("hystrix.config.stream.maxConcurrentConnections"),
				DEFAULT_STREAM_MAX_CONCURRENT_CONNECTIONS);
	}

	/** Every command key the file mentions - used by tests and by the config dump. */
	public List<String> configuredCommandKeys()
	{
		List<String> keys = new ArrayList<String>();
		for (Object raw : props.keySet())
		{
			String key = raw.toString();
			if (!key.startsWith(COMMAND_PREFIX))
				continue;

			String rest = key.substring(COMMAND_PREFIX.length());
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
		String value = props.getProperty(COMMAND_PREFIX + commandKey + "." + property);
		if (null == value)
			value = props.getProperty(COMMAND_PREFIX + DEFAULT_SCOPE + "." + property);
		return (null == value) ? defaultValue : value;
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
			value = props.getProperty(THREADPOOL_PREFIX + DEFAULT_SCOPE + "." + property);
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
