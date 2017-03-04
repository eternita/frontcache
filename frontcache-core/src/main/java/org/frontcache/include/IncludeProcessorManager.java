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
		IncludeProcessor includeProcessor = null;
		if (null != implStr)
		{
			try
			{

				@SuppressWarnings("rawtypes")
				Class clazz = Class.forName(implStr);
				Object obj = clazz.newInstance();
				if (null != obj && obj instanceof IncludeProcessor)
				{
					logger.info("IncludeProcessor implementation loaded: " + implStr);
					includeProcessor = (IncludeProcessor) obj;

					includeProcessor.init(FCConfig.getProperties());
					logger.info("IncludeProcessor implementation initialized: " + implStr);
					
					return includeProcessor;
				}
			} catch (Exception ex) {
				logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + ConcurrentIncludeProcessor.class.getCanonicalName());
			}
		}
		
		logger.info("Default include implementation is loaded: " + ConcurrentIncludeProcessor.class.getCanonicalName());
		includeProcessor = new ConcurrentIncludeProcessor();
		try
		{
			// init with properties from config file
			includeProcessor.init(FCConfig.getProperties());
		} catch (Exception ex) {
			logger.error("Cant initalize default include processor. ", ex);
		}
		
		return includeProcessor;
	}

}
