package org.frontcache.cache.impl.ehcache;

import java.util.Properties;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.cache.WebComponent;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

public class EhcacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private static final String DEFAULT_EHCACHE_CONFIG_FILE = "ehcache-config.xml";
	
	private static final String EHCACHE_CONFIG_FILE_KEY = "cache.ehcache_config";
	
	private static final String FRONT_CACHE = "FRONT_CACHE";

	CacheManager ehCacheMgr = null;
    Cache cache = null;
	

	@Override
	public void init(Properties properties) {
		
		String ehCacheConfigFile = properties.getProperty(EHCACHE_CONFIG_FILE_KEY);
		if (null == ehCacheConfigFile)
		{
			logger.info(EHCACHE_CONFIG_FILE_KEY + " is required for " + getClass().getName() + " but not defined in config. Default is used: " + DEFAULT_EHCACHE_CONFIG_FILE);
			ehCacheConfigFile = DEFAULT_EHCACHE_CONFIG_FILE;
		}
		
		logger.info("Loading " + ehCacheConfigFile);
		ehCacheMgr = CacheManager.create( EhcacheProcessor.class.getClassLoader().getResource(ehCacheConfigFile));
		
        cache = ehCacheMgr.getCache(FRONT_CACHE);
        if (null == cache)
        {
        	ehCacheMgr.addCache(FRONT_CACHE);
            cache = ehCacheMgr.getCache(FRONT_CACHE);
        }
        
	}
	
	@Override
	public void destroy() {
		
		try
		{
			if (null != cache)
				cache.dispose();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
		try
		{
			if (null != ehCacheMgr)
				ehCacheMgr.shutdown();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}	


	@Override
	protected void putToCache(String url, WebComponent component) {
		logger.info(url);
		cache.put(new Element(url, component));
	}

	@Override
	protected WebComponent getFromCache(String url) {
		logger.info(url);
		Element el = cache.get(url);
		if (null != el)
			return (WebComponent) el.getObjectValue();
		
		return null;
	}	


	
}
