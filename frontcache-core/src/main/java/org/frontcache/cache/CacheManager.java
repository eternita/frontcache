package org.frontcache.cache;

import java.util.logging.Logger;

import org.frontcache.FCConfig;



public class CacheManager {

	private static Logger logger = Logger.getLogger(CacheManager.class.getName());
	private CacheManager() {}
	
	private static CacheProcessor instance;

	public static CacheProcessor getInstance(){
		if (null == instance) {
			instance = getCacheProcessor();
		}
		return instance;
	}
	
	
	private static CacheProcessor getCacheProcessor()
	{
		String cacheImplStr = FCConfig.getProperty("front-cache.cache-processor.impl");

		try
		{
			Class clazz = Class.forName(cacheImplStr);
			Object obj = clazz.newInstance();
			if (null != obj && obj instanceof CacheProcessor)
			{
				logger.info("Cache implementation loaded: " + cacheImplStr);
				CacheProcessor cacheProcessor = (CacheProcessor) obj;
				
				// init with properties from config file
				cacheProcessor.init(FCConfig.getProperties());
				logger.info("Cache implementation initialized: " + cacheImplStr);
				
				return cacheProcessor;
			}
		} catch (Exception ex) {
			logger.severe("Cant instantiate " + cacheImplStr + " Fallback - " + NoCacheProcessor.class.getName());
		}
		
		return new NoCacheProcessor();
	}

}
