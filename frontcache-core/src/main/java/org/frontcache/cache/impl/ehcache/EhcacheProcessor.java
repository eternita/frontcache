package org.frontcache.cache.impl.ehcache;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.WebResponse;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

public class EhcacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private static final String DEFAULT_EHCACHE_CONFIG_FILE = "ehcache-config.xml";
	
	private static final String EHCACHE_CONFIG_FILE_KEY = "front-cache.cache-processor.impl.ehcache.config";
	
	private static final String FRONT_CACHE = "FRONT_CACHE"; // cache name inside config file (e.g. ehcache-config.xml)

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
		InputStream is = FCConfig.getConfigInputStream(ehCacheConfigFile);
		ehCacheMgr = CacheManager.create(is);
		
		if (null != is)
		try {
			is.close();
		} catch (IOException e) {		}
		
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

	/**
	 * 
	 */
	@Override
	public void putToCache(String url, WebResponse component) {
		logger.debug(url);
		cache.put(new Element(url, component));
	}

	/**
	 * 
	 */
	@Override
	public WebResponse getFromCacheImpl(String url) {
		logger.debug(url);
		Element el = cache.get(url);
		if (null == el)
			return null;
		
		WebResponse comp = (WebResponse) el.getObjectValue();
		
		return comp;
	}

	@Override
	public void removeFromCache(String filter) {
		List<Object> removeList = new ArrayList<Object>();
		
		for(Object key : cache.getKeys())
		{
			String str = key.toString();
			if (-1 < str.indexOf(filter))
				removeList.add(key);	
		}
		
		for(Object key : removeList)
			cache.remove(key);
	}

	@Override
	public void removeFromCacheAll() {
		cache.removeAll();
	}
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = super.getCacheStatus();
		
		status.put("impl", this.getClass().getName());

		status.put(CacheProcessor.CACHED_ENTRIES, "" + cache.getKeys().size());
		
		return status;
	}
	
	@Override
	public List<String> getCachedKeys() {
		List<String> keys = new ArrayList<String>();
		for (Object key : cache.getKeys())
			keys.add(key.toString());

		return keys;
	}
	
}

