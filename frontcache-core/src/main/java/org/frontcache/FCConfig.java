package org.frontcache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FCConfig {

	public static final String FRONT_CACHE_HOME_SYSTEM_KEY = "frontcache.home"; 
	
	public static final String FRONT_CACHE_HOME_ENVIRONMENT_KEY = "FRONTCACHE_HOME";
	
	private static final String FRONT_CACHE_CONFIG = "front-cache.properties"; 

    private static Properties config;
    
	private static Logger logger = LoggerFactory.getLogger(FCConfig.class);
    
	
    public static void init()
    {
    	
		// check / set frontcache.home java system variable
		String frontcacheHome = System.getProperty(FRONT_CACHE_HOME_SYSTEM_KEY);
		
		if (null == frontcacheHome)
		{
			frontcacheHome = System.getenv().get(FRONT_CACHE_HOME_ENVIRONMENT_KEY);
			if (null != frontcacheHome)
				System.setProperty(FRONT_CACHE_HOME_SYSTEM_KEY, frontcacheHome);
		}
		
		logger.info("FRONTCACHE_HOME is " + frontcacheHome);
    	
    	config = loadProperties();
    	
    	
    	Properties hystrixProperties = new Properties();
    	try {
    		hystrixProperties.load(FCConfig.getConfigInputStream("hystrix.properties"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		logger.info("Hystrix.properties : ");
		for (Object key : hystrixProperties.keySet())
			logger.info("-- " + key + " - " + hystrixProperties.getProperty(key.toString()));
			

    	System.getProperties().putAll(hystrixProperties);
    	
    	if (null == config)
    		throw new RuntimeException("Can't load " + FRONT_CACHE_CONFIG + " from classpath and " + FRONT_CACHE_HOME_SYSTEM_KEY + " (java system variable) or " + FRONT_CACHE_HOME_ENVIRONMENT_KEY + " (environment variable)");
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
		return config;
	}
    
	/**
	 * 
	 * 1. get input stream from inside jars (/front-cache.properties)
	 * 2. get input stream from system variable frontcache.home
	 * 3. get input stream from environment variable FRONTCACHE_HOME/conf/front-cache.properties
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
		
		// 2. get input stream from environment variable FRONTCACHE_HOME/conf/front-cache.properties
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
		
		// 3. get input stream from inside jars (/front-cache.properties)
		try 
		{
			is = FCConfig.class.getClassLoader().getResourceAsStream(name);
		} catch (Exception e) {		}
		
		
    	if (null == is)
    		throw new RuntimeException("Can't load " + name + " from classpath and " + FRONT_CACHE_HOME_SYSTEM_KEY + " (java system variable) or " + FRONT_CACHE_HOME_ENVIRONMENT_KEY + " (environment variable)");
		
		return is;
	}
	
}
