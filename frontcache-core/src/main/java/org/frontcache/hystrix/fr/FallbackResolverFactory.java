package org.frontcache.hystrix.fr;


import org.apache.http.client.HttpClient;
import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class FallbackResolverFactory {

	private static Logger logger = LoggerFactory.getLogger(FallbackResolverFactory.class);
	private FallbackResolverFactory() {}
	
	private static FallbackResolver instance;

	public static FallbackResolver getInstance(){
		if (null == instance) {
			throw new RuntimeException("FallbackResolver is not initialized.");
		}
		return instance;
	}
	
	public static FallbackResolver init(HttpClient client){
		if (null == instance) {
			instance = getFallbackResolver();
			instance.init(client);
		}
		return instance;
	}
	
	/**
	 * 
	 * @return
	 */
	private static FallbackResolver getFallbackResolver()
	{
		String implStr = FCConfig.getProperty("front-cache.fallback-resolver.impl");
		try
		{

			@SuppressWarnings("rawtypes")
			Class clazz = Class.forName(implStr);
			Object obj = clazz.newInstance();
			if (null != obj && obj instanceof FallbackResolver)
			{
				logger.info("FallbackResolver implementation loaded: " + implStr);
				FallbackResolver fallbackResolver = (FallbackResolver) obj;

				return fallbackResolver;
			}
		} catch (Exception ex) {
			logger.error("Cant instantiate " + implStr + ". Default implementation is loaded: " + FileBasedFallbackResolver.class.getCanonicalName());
			return new FileBasedFallbackResolver();
		}
		
		return new FileBasedFallbackResolver();
	}

	public static void destroy()
	{
		instance = null;
		return;
	}
}
