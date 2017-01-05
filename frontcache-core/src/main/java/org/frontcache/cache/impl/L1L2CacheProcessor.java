package org.frontcache.cache.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;


/**
 * Cache processor based on 
 * 
 * L1 - ehCache.
 * L2 - Apache Lucene.
 *
 */
public class L1L2CacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private static final Logger logger = LoggerFactory.getLogger(L1L2CacheProcessor.class);

	// L1
	private static final String DEFAULT_EHCACHE_CONFIG_FILE = "fc-l1-ehcache-config.xml";
	
	private static final String EHCACHE_CONFIG_FILE_KEY = "front-cache.cache-processor.impl.ehcache-config";
	
	private static final String FRONT_CACHE = "FRONT_CACHE"; // cache name inside config file (e.g. ehcache-config.xml)

	private CacheManager ehCacheManager = null;
	
	private Cache ehCache = null;

    // L2 
	private LuceneIndexManager luceneIndexManager;
	
	private static String CACHE_BASE_DIR_DEFAULT = "/tmp/cache/";
	
	private static final String CACHE_BASE_DIR_KEY = "front-cache.cache-processor.impl.cache-dir"; // to override path in configs
	
	private static String CACHE_RELATIVE_DIR = "cache/l2-lucene-index/";
	
	private static String INDEX_BASE_DIR = CACHE_BASE_DIR_DEFAULT + CACHE_RELATIVE_DIR;


	@Override
	public void init(Properties properties) {
		 
		Objects.requireNonNull(properties, "Properties should not be null");
		super.init(properties);
		
		// L1 - ehCache
		String ehCacheConfigFile = properties.getProperty(EHCACHE_CONFIG_FILE_KEY);
		if (null == ehCacheConfigFile)
		{
			logger.info(EHCACHE_CONFIG_FILE_KEY + " is required for " + getClass().getName() + " but not defined in config. Default is used: " + DEFAULT_EHCACHE_CONFIG_FILE);
			ehCacheConfigFile = DEFAULT_EHCACHE_CONFIG_FILE;
		}
		
		logger.info("Loading " + ehCacheConfigFile);
		InputStream is = FCConfig.getConfigInputStream(ehCacheConfigFile);
		ehCacheManager = CacheManager.create(is);
		
		if (null != is)
		try {
			is.close();
		} catch (IOException e) {		}
		
        ehCache = ehCacheManager.getCache(FRONT_CACHE);
        if (null == ehCache)
        {
        	ehCacheManager.addCache(FRONT_CACHE);
            ehCache = ehCacheManager.getCache(FRONT_CACHE);
        }
		
		
		// L2 - Lucene
		if (null != properties.getProperty(CACHE_BASE_DIR_KEY))
		{
			CACHE_BASE_DIR_DEFAULT = properties.getProperty(CACHE_BASE_DIR_KEY);
			INDEX_BASE_DIR = CACHE_BASE_DIR_DEFAULT + CACHE_RELATIVE_DIR;
		} else {
			// get from FRONTCACHE_HOME
			String frontcacheHome = System.getProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY);
			File fsBaseDir = new File(new File(frontcacheHome), CACHE_RELATIVE_DIR);
			
			INDEX_BASE_DIR = fsBaseDir.getAbsolutePath();
			if (!INDEX_BASE_DIR.endsWith("/"))
				INDEX_BASE_DIR += "/";
			
		}
		
		luceneIndexManager = new LuceneIndexManager(INDEX_BASE_DIR);
	}
	
	@Override
	public void destroy() {
		super.destroy();
		
		logger.info("Running destroy() for ehCache ");
		try
		{
			if (null != ehCache)
				ehCache.dispose();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
		try
		{
			if (null != ehCacheManager)
				ehCacheManager.shutdown();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
		logger.info("Running destroy() for Lucene");
		luceneIndexManager.close();
		return;
	}
	
//	public LuceneIndexManager getIndexManager(){
//		return indexManager;
//	}

	/**
	 * Saves web response to cache-file
	 */
	@Override
	public void putToCache(String domain, String url, WebResponse component) {
		
		component.setDomain(domain);
		
		if (FCHeaders.CACHE_LEVEL_L1.equalsIgnoreCase(component.getCacheLevel()))
		{
			ehCache.put(new Element(url, component));
		} else {
			
			// L2 (default)
			try {
				luceneIndexManager.indexDoc(component);
			} catch (IOException e) {
				logger.error("Error during putting response to lucene cache", e);
			}
		}
		
		return;
	}

	/**
	 * Load web-response from file
	 */
	@Override
	public WebResponse getFromCacheImpl(String url) {
		logger.debug("Getting from cache {}", url);
		
		Element el = ehCache.get(url); // check L1 - ehCache
		if (null != el && null != el.getObjectValue())
			return (WebResponse) el.getObjectValue();
	
		// check L2 - Lucene
		WebResponse webResponse = luceneIndexManager.getResponse(url);
		
		return webResponse;
	}
	

	@Override
	public void removeFromCache(String domain, String filter) {
		logger.debug("Removing from cache {}", filter);
		
		{ // remove from ehCache
			List<Object> removeList = new ArrayList<Object>();
			
			for(Object key : ehCache.getKeys())
			{
				String str = key.toString();
				if (-1 < str.indexOf(filter))
					removeList.add(key);	
			}
			
			for(Object key : removeList)
				ehCache.remove(key);
		}

		// remove from Lucene
		luceneIndexManager.delete(domain, filter);
	}

	
	@Override
	public void removeFromCacheAll(String domain) {
		logger.debug("truncate cache");

		ehCache.removeAll(); // ehCache

		luceneIndexManager.truncate(); // lucene
	}
	
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = super.getCacheStatus();
		status.put("impl", this.getClass().getName());
		status.put("impl_L1", "EhCache");
		status.put("impl_L2", "Lucene");
		status.put(CacheProcessor.CACHED_ENTRIES, "" + (ehCache.getKeys().size() + luceneIndexManager.getIndexSize()));
		status.put(CacheProcessor.CACHED_ENTRIES + "-L1", "" + ehCache.getKeys().size());
		status.put(CacheProcessor.CACHED_ENTRIES + "-L2", "" + luceneIndexManager.getIndexSize());
		return status;
	}
	
	
	@Override
	public List<String> getCachedKeys() {
		List<String> keys = new ArrayList<String>();
		
		// ehCahce
		for (Object key : ehCache.getKeys())
			keys.add(key.toString());

		// Lucene
		keys.addAll(luceneIndexManager.getKeys());
		
		return keys;
	}
	
}

