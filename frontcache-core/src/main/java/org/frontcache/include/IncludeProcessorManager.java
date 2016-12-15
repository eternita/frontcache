package org.frontcache.include;


import org.frontcache.FCConfig;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class IncludeProcessorManager {

	private static Logger logger = LoggerFactory.getLogger(IncludeProcessorManager.class);
	private IncludeProcessorManager() {}
	
	private static IncludeProcessor instance;

	public static IncludeProcessor getInstance(){
		if (null == instance) {
			instance = getIncludeProcessor();
		}
		return instance;
	}
	
	
	private static IncludeProcessor getIncludeProcessor()
	{
		String implStr = FCConfig.getProperty("front-cache.include-processor.impl");
		try
		{

			@SuppressWarnings("rawtypes")
			Class clazz = Class.forName(implStr);
			Object obj = clazz.newInstance();
			if (null != obj && obj instanceof IncludeProcessor)
			{
				logger.info("IncludeProcessor implementation loaded: " + implStr);
				IncludeProcessor cacheProcessor = (IncludeProcessor) obj;

				cacheProcessor.init(FCConfig.getProperties());
				logger.info("IncludeProcessor implementation initialized: " + implStr);
				
				return cacheProcessor;
			}
		} catch (Exception ex) {
			logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + ConcurrentIncludeProcessor.class.getCanonicalName());
			
			// 
			return new ConcurrentIncludeProcessor();
		}
		
		
		return null;
	}

}
