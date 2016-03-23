package org.frontcache.cache;


import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class CacheManager {

	private static Logger logger = LoggerFactory.getLogger(CacheManager.class);
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
			ex.printStackTrace();
			logger.error("Cant instantiate " + cacheImplStr + " Fallback - " + NoCacheProcessor.class.getName());
		}
		
		return new NoCacheProcessor();
	}

}
