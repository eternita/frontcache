package org.frontcache.hystrix.fr;


import org.frontcache.FCConfig;
import org.frontcache.include.impl.SerialIncludeProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class FallbackResolverFactory {

	private static Logger logger = LoggerFactory.getLogger(FallbackResolverFactory.class);
	private FallbackResolverFactory() {}
	
	private static FallbackResolver instance;

	public static FallbackResolver getInstance(){
		if (null == instance) {
			instance = getFallbackResolver();
		}
		return instance;
	}
	
	
	private static FallbackResolver getFallbackResolver()
	{
		String implStr = FCConfig.getProperty("front-cache.fallback-resolver.impl");
		try
		{

			Class clazz = Class.forName(implStr);
			Object obj = clazz.newInstance();
			if (null != obj && obj instanceof FallbackResolver)
			{
				logger.info("FallbackResolver implementation loaded: " + implStr);
				FallbackResolver fallbackResolver = (FallbackResolver) obj;

//				cacheProcessor.init(FCConfig.getProperties());
//				logger.info("IncludeProcessor implementation initialized: " + implStr);
				
				return fallbackResolver;
			}
		} catch (Exception ex) {
			logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + FileBasedFallbackResolver.class.getCanonicalName());
			
			// 
			return new DefaultFallbackResolver();
		}
		
		
		return null;
	}

}
