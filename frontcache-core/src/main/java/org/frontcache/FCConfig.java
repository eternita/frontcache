package org.frontcache;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;


public class FCConfig {

    private static Properties config = loadProperties("front-cache.properties");
	
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
    
    
	private static Properties loadProperties(String fName)
	{
		Properties config = new Properties();
		InputStream is = null;
		try 
		{
			is = FCConfig.class.getClassLoader().getResourceAsStream(fName);
			config.load(is);
		} catch (Exception e) {
			throw new RuntimeException("can't read front-cache.properties " + fName, e);
		} finally {
			if (null != is)
			{
				try {
					is.close();
				} catch (IOException e) { }
			}
		}
		return config;
	}    
}
