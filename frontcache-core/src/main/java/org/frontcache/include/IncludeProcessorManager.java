package org.frontcache.include;

import java.util.logging.Logger;

import org.frontcache.FCConfig;
import org.frontcache.include.impl.SerialIncludeProcessor;



public class IncludeProcessorManager {

	private static Logger logger = Logger.getLogger(IncludeProcessorManager.class.getName());
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
		String implStr = FCConfig.getProperty("include_processor.impl");
		try
		{

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
			logger.severe("Cant instantiate " + implStr + ". Default implementation is loaded");
			
			// 
			return new SerialIncludeProcessor();
		}
		
		
		return null;
	}

}
