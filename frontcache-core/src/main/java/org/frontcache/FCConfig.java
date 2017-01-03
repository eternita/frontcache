package org.frontcache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.config.ConcurrentMapConfiguration;
import com.netflix.config.ConfigurationManager;


public class FCConfig {

	public static final String FRONT_CACHE_HOME_SYSTEM_KEY = "frontcache.home"; 
	
	public static final String FRONT_CACHE_HOME_ENVIRONMENT_KEY = "FRONTCACHE_HOME";
	
	private static final String FRONT_CACHE_ID_CONFIG = "frontcache.id"; // file where id is stored
	
	public static final String FRONTCACHE_ID_KEY = "front-cache.id"; // key under which id is stored
	
	public static final String DEFAULT_FRONTCACHE_ID = "default.frontcache.id"; // default frontcache id

	private static final String FRONT_CACHE_CONFIG = "frontcache.properties"; 

    private static Properties config;
    
	private static final String BOT_CONIF_FILE = "bots.conf";
	
	private static Set<String> botUserAgentKeywords = new LinkedHashSet<String>();

	private static final String CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE = "dynamic-urls.conf";
	
	private static List <Pattern> dynamicURLPatterns = new ArrayList<Pattern>();
	
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
    	
    	
    	Properties hystrixProperties = new Properties();
    	try {
    		hystrixProperties.load(FCConfig.getConfigInputStream("hystrix.properties"));
		} catch (IOException e) {
			e.printStackTrace();
		}
    	
    	Map<String, Object> hystrixConfigMap = new HashMap<String, Object>();
    	
		logger.info("Hystrix.properties : ");
		for (Object key : hystrixProperties.keySet())
		{
			logger.info("-- " + key + " - " + hystrixProperties.getProperty(key.toString()));
			hystrixConfigMap.put(key.toString(), hystrixProperties.getProperty(key.toString()));
		}
			
		// enable configuration from "hystrix.properties"
		ConcurrentMapConfiguration systemProps = new ConcurrentMapConfiguration(hystrixConfigMap);

		// you can install hystrix config one time only
		// in case of reloading Frontcache - previous hystrix config is keeping
		if (!ConfigurationManager.isConfigurationInstalled()) 
			ConfigurationManager.install(systemProps);
		

//    	System.getProperties().putAll(hystrixProperties);
		
		loadBotConfigs();
		loadCacheIgnoreURIPatterns();
		
    	if (null == config)
    		throw new RuntimeException("Can't load " + FRONT_CACHE_CONFIG + " from classpath and " + FRONT_CACHE_HOME_SYSTEM_KEY + "=" + System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY) + " (java system variable) or " + FRONT_CACHE_HOME_ENVIRONMENT_KEY + "=" + System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY) + " (environment variable)");
    }
    
	public static Set<String> getBotUserAgentKeywords()
	{
		return botUserAgentKeywords;
	}
    
    public static void destroy()
    {
    	config = null;
    	botUserAgentKeywords.clear();
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
	
	private static void loadBotConfigs() {		
		logger.info("Loading list of bots from " + BOT_CONIF_FILE);
		BufferedReader confReader = null;
		InputStream is = null;
		
		botUserAgentKeywords.clear();
				
		try 
		{
			is = FCConfig.getConfigInputStream(BOT_CONIF_FILE);
			if (null == is)
			{
				logger.info("List of bots is not loaded from " + BOT_CONIF_FILE);
				return;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String botStr;
			int botConfigCounter = 0;
			while ((botStr = confReader.readLine()) != null) {
				if (botStr.trim().startsWith("#")) // handle comments
					continue;
				
				if (0 == botStr.trim().length()) // skip empty
					continue;
				
				botUserAgentKeywords.add(botStr);
				botConfigCounter++;
			}
			logger.info("Successfully loaded " + botConfigCounter +  " User-Agent keywords for bots");					
			
		} catch (Exception e) {
			logger.info("List of bots is not loaded from " + BOT_CONIF_FILE, e);
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
		
		return;
	}
	
	private static void loadCacheIgnoreURIPatterns() {
		BufferedReader confReader = null;
		InputStream is = null;
		dynamicURLPatterns.clear();
		
		try 
		{
			is = FCConfig.getConfigInputStream(CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
			if (null == is)
			{
				logger.info("Dynamic URL patterns are not loaded from " + CACHE_IGNORE_URI_PATTERNS_CONFIG_FILE);
				return;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String patternStr;
			int patternCounter = 0;
			while ((patternStr = confReader.readLine()) != null) {
				try {
					if (patternStr.trim().startsWith("#")) // handle comments
						continue;
					
					if (0 == patternStr.trim().length()) // skip empty
						continue;
					
					dynamicURLPatterns.add(Pattern.compile(patternStr));
					patternCounter++;
				} catch (PatternSyntaxException ex) {
					logger.info("Dynamic URL pattern - " + patternStr + " is not loaded");					
				}
			}
			logger.info("Successfully loaded " + patternCounter +  " dynamic URL patterns");					
			
		} catch (Exception e) {
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
		
	}
	
	
		
	public static Set<String> getDynamicURLs()
	{
		Set<String> dynamicURLs = new HashSet<String>();
		
		for (Pattern p : dynamicURLPatterns)
			dynamicURLs.add(p.toString());
		
		return dynamicURLs;
	}
	
	public static List<Pattern> getDynamicURLPatterns()
	{
		return dynamicURLPatterns;
	}
    
	
}
