/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FCConfig {

	public static final String FRONT_CACHE_HOME_SYSTEM_KEY = "frontcache.home";

	public static final String FRONT_CACHE_HOME_ENVIRONMENT_KEY = "FRONTCACHE_HOME";

	private static final String FRONT_CACHE_ID_CONFIG = "frontcache.id"; // file where id is stored

	public static final String FRONTCACHE_ID_KEY = "front-cache.id"; // key under which id is stored

	public static final String DEFAULT_FRONTCACHE_ID = "default.frontcache.id"; // default frontcache id

	private static final String FRONT_CACHE_CONFIG = "frontcache.properties";

    private static Properties config;

    public static final String DEFAULT_DOMAIN = "default-domain";

	private static final String CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE = "dynamic-urls.conf";

	private static Set<Pattern> dynamicURLPatterns = new HashSet<Pattern>();

	private static Logger logger = LoggerFactory.getLogger(FCConfig.class);

	static {
		init();
	}

    private static void init()
    {

		// check / set frontcache.home java system variable
		String frontcacheHome = System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY);

		if (null == frontcacheHome)
		{
			frontcacheHome = System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY);
			if (null != frontcacheHome)
				System.setProperty(FRONT_CACHE_HOME_SYSTEM_KEY, frontcacheHome);
		}
		System.out.println("FRONTCACHE_HOME is " + frontcacheHome);
		logger.info("FRONTCACHE_HOME is " + frontcacheHome);

    	config = loadProperties();

		// hystrix.properties used to be installed into Archaius from here, guarded by an
		// isConfigurationInstalled() check because Archaius accepts an install only once per JVM: if
		// anything touched it first (the metrics-stream servlet's static initializer, say) every
		// per-command override in the file was silently dropped. FrontcacheConfigBootstrapListener
		// existed solely to win that race. Resilience4j reads the same file through
		// FcResilienceConfig, as an ordinary Properties object at first use, so both workarounds are
		// gone and the startup-ordering bug they guarded against is no longer possible.

		loadCacheIgnoreURIPatterns();

    	if (null == config)
    		throw new RuntimeException("Can't load " + FRONT_CACHE_CONFIG + " from classpath and " + FRONT_CACHE_HOME_SYSTEM_KEY + "=" + System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY) + " (java system variable) or " + FRONT_CACHE_HOME_ENVIRONMENT_KEY + "=" + System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY) + " (environment variable)");
    }

	/**
	 * Bare bot user-agent keywords from conf/bots.conf. One node serves one site, so there is one set.
	 *
	 * conf/bots.conf holds classification RULES as well as bare keywords as of 2.9.0, and
	 * {@link org.frontcache.bots.BotClassifier} is its only reader - this delegates rather than
	 * parsing the file a second time, because two readers of one config file is how a console ends up
	 * reporting something the engine is not doing.
	 */
	public static Set<String> getBotUserAgentKeywords()
	{
		return org.frontcache.bots.BotClassifier.getInstance().getKeywords();
	}

    public static void destroy()
    {
    	config = null;
    	dynamicURLPatterns.clear();
    }

    public static String getProperty(String key, String defaultValue)
    {
        return (null != config.getProperty(key)) ? config.getProperty(key) : defaultValue;
    }

    public static String getProperty(String key)
    {
        return config.getProperty(key);
    }

    public static void setProperty(String key, String value)
    {
        config.setProperty(key, value);
        return;
    }

    /**
     *
     * @return
     */
    public static Properties getProperties()
    {
    	Properties props = new Properties();

		@SuppressWarnings("rawtypes")
		Enumeration keys = config.keys();
		while (keys.hasMoreElements())
		{
			String key = (String) keys.nextElement();
			String value = config.getProperty(key);
			props.setProperty(key, value);
		}

    	return props;
    }

	/**
	 * The FRONTCACHE_HOME/conf directory, or null when it cannot be resolved.
	 *
	 * Package-private: used by FrontCacheEngine to detect leftover per-domain config directories
	 * (see FrontCacheEngine.findRetiredDomainConfigDirs).
	 */
	static File getConfDir()
	{
		// 1. frontcache.home java system variable
		String frontcacheHome = System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY);

		if (null != frontcacheHome)
		{
			File fcConfig = new File(new File(frontcacheHome), "conf");
			if (fcConfig.exists())
				return fcConfig;
		}

		// 2. FRONTCACHE_HOME environment variable
		frontcacheHome = System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY);

		if (null != frontcacheHome)
		{
			File fcConfig = new File(new File(frontcacheHome), "conf");
			if (fcConfig.exists())
				return fcConfig;
		}

		return null;
	}

	private static Properties loadProperties()
	{
		InputStream is = getConfigInputStream(FRONT_CACHE_CONFIG);

		if (null == is)
			return null;

		Properties config = new Properties();
		try
		{
			config.load(is);
		} catch (Exception e) {
			return null;
		} finally {
			try {
				is.close();
			} catch (IOException e) { }
		}

		try
		{
			// load frontcache.id from different file - different file for deployment convenience
			is = getConfigInputStream(FRONT_CACHE_ID_CONFIG);
		} catch (Throwable ex) {/*ignored*/}

		if (null != is)
		{
			Properties idConfig = new Properties();
			try
			{
				idConfig.load(is);
				Object id = idConfig.get(FRONTCACHE_ID_KEY);
				config.put(FRONTCACHE_ID_KEY, id);
			} catch (Exception e) {
				/*ignored*/
			} finally {
				try {
					is.close();
				} catch (IOException e) { }
			}
		}

		if (null == config.getProperty(FRONTCACHE_ID_KEY))
			config.put(FRONTCACHE_ID_KEY, DEFAULT_FRONTCACHE_ID);

		return config;
	}

	/**
	 *
	 * 1. get input stream from inside jars (/frontcache.properties)
	 * 2. get input stream from system variable frontcache.home
	 * 3. get input stream from environment variable FRONTCACHE_HOME/conf/frontcache.properties
	 *
	 *
	 * @param name
	 * @return
	 */
	public static InputStream getConfigInputStream(String name)
	{

		InputStream is = null;

		// 1. get input stream from system variable frontcache.home
		String frontcacheHome = System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY);

		if (null != frontcacheHome)
		{
			File fcConfig = new File(new File(frontcacheHome), "conf/" + name);
			if (fcConfig.exists())
			{
				try {
					is = new FileInputStream(fcConfig);
				} catch (Exception e) {		}
			}
		}

		if (null != is)
			return is;

		// 2. get input stream from environment variable FRONTCACHE_HOME/conf/frontcache.properties
		frontcacheHome = System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY);

		if (null != frontcacheHome)
		{
			File fcConfig = new File(new File(frontcacheHome), "conf/" + name);
			if (fcConfig.exists())
			{
				try {
					is = new FileInputStream(fcConfig);
				} catch (Exception e) {		}
			}
		}
		if (null != is)
			return is;

		// 3. get input stream from inside jars (/frontcache.properties)
		try
		{
			is = FCConfig.class.getClassLoader().getResourceAsStream(name);
		} catch (Exception e) {		}


    	if (null == is)
    		throw new RuntimeException("Can't load " + name + " from classpath or " + FRONT_CACHE_HOME_SYSTEM_KEY + "=" + System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY) + " (java system variable) or " + FRONT_CACHE_HOME_ENVIRONMENT_KEY + "=" + System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY) + " (environment variable)");

		return is;
	}

	private static void loadCacheIgnoreURIPatterns() {
		dynamicURLPatterns.clear();

		logger.info("Starting dynamic URLs configs loading ...");
		Set<Pattern> dynamicURLsConfig = loadCacheIgnoreURIPatterns(CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
		if (null != dynamicURLsConfig)
		{
			dynamicURLPatterns.addAll(dynamicURLsConfig);
			logger.info("   loaded " + dynamicURLsConfig.size() + " dynamic urls from " + CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
		} else {
			logger.info("   no dynamic urls found in " + CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE + " - every cacheable URL may be cached");
		}
		logger.info("Dynamic URLs configs loading is completed ...");
		return;
	}

	private static Set<Pattern> loadCacheIgnoreURIPatterns(String file) {
		BufferedReader confReader = null;
		InputStream is = null;
		Set<Pattern> dynamicURLPatterns = new HashSet<Pattern>();

		try
		{
			is = FCConfig.getConfigInputStream(file);
			if (null == is)
			{
				logger.info("Dynamic URL patterns are not loaded from " + file);
				return null;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String patternStr;
			while ((patternStr = confReader.readLine()) != null) {
				try {
					if (patternStr.trim().startsWith("#")) // handle comments
						continue;

					if (0 == patternStr.trim().length()) // skip empty
						continue;

					dynamicURLPatterns.add(Pattern.compile(patternStr));
				} catch (PatternSyntaxException ex) {
					logger.info("Dynamic URL pattern - " + patternStr + " is not loaded");
				}
			}

		} catch (Throwable e) {
			logger.info("Dynamic URL patterns are not loaded from " + CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
		} finally {
			if (null != confReader)
			{
				try {
					confReader.close();
				} catch (IOException e) { }
			}
			if (null != is)
			{
				try {
					is.close();
				} catch (IOException e) { }
			}
		}

		return dynamicURLPatterns;
	}


	/**
	 *
	 * @return
	 */
	public static Set<String> getDynamicURLs()
	{
		Set<String> dynamicURLs = new HashSet<String>();

		for (Pattern p : dynamicURLPatterns)
			dynamicURLs.add(p.toString());

		return dynamicURLs;
	}

	/**
	 * URL patterns that are never cached, from conf/dynamic-urls.conf.
	 */
	public static Set<Pattern> getDynamicURLPatterns()
	{
		return dynamicURLPatterns;
	}


}
