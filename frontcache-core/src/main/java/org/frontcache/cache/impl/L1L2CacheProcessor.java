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
import org.frontcache.core.FCUtils;
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
		InputStream is = null;
		if (null != ehCacheConfigFile)
		{
			try {
				is = FCConfig.getConfigInputStream(ehCacheConfigFile);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}

		if (null != is)
			ehCacheManager = CacheManager.create(is);
		else 
			ehCacheManager = CacheManager.create();
			
		
		if (null != is)
		{
			try {
				is.close();
			} catch (IOException e) {		}
		}
		
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

	/**
	 * Saves web response to cache-file
	 */
	@Override
	public void putToCache(String domain, String url, WebResponse component) {
		
		// don't cache pure dynamic components
		if (!FCUtils.isWebComponentSubjectToCache(component.getExpireTimeMap()))
		{
			try {
				throw new Exception("Debug call trace - component.getExpireTimeMap() shouldn't be like that " + component.getExpireTimeMap());
			} catch (Exception e) {
				logger.error("Debuging #202 (dynamic response caching) ", e);
			}
			
			return;
		}
		
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
				String objDomain = ((WebResponse) ehCache.get(key).getObjectValue()).getDomain();
				
				String str = key.toString();
//				if (domain.equals(objDomain) && -1 < str.indexOf(filter))
				if (-1 < str.indexOf(filter))
					removeList.add(key);
			}
			
			for(Object key : removeList)
				ehCache.remove(key);
		}

		// remove from Lucene
		luceneIndexManager.delete(domain, filter);
	}


	public void removeFromCache(String filter) {
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
		luceneIndexManager.delete(filter);
	}
	
	@Override
	public void removeFromCacheAll(String domain) {
		logger.debug("truncate cache");

		{ // remove from ehCache
			List<Object> removeList = new ArrayList<Object>();
			
			for(Object key : ehCache.getKeys())
			{
				String objDomain = ((WebResponse) ehCache.get(key).getObjectValue()).getDomain();
				if (null != domain && domain.equals(objDomain))
					removeList.add(key);
			}
			
			for(Object key : removeList)
				ehCache.remove(key);
		}

		luceneIndexManager.deleteAll(domain);
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
		
		for (String domain : FCConfig.getDomains())
		{
			long domainCount = luceneIndexManager.getDocumentsCount(domain);
			status.put(CacheProcessor.CACHED_ENTRIES + "-L2." + domain, "" + domainCount);
		}
		
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

	@Override
	public void patch() {
		
		System.out.println("!!!!! start patching ");
		logger.info("!!!!! start patching ");
		
		final String domain = "coinshome.net";
		long webResponseNullCounter = 0;
		long webResponseDomainErrorCounter = 0;
		long webResponseDomainNullErrorCounter = 0;
		
		System.out.println("!!!!! start getting keys ... ");
		logger.info("!!!!! start getting keys ... ");
		List<String> urls = luceneIndexManager.getKeys();
		System.out.println("" + urls.size() + " keys are found ... ");
		logger.info("" + urls.size() + " keys are found ... ");
		
		long counter = 0;
		for (String url : urls)
		{
			counter++;
			if (0 == counter % 1000)
				logger.info("" + counter + " items processed");
			
			WebResponse webResponse = getFromCacheImpl(url);
			
			if (null == webResponse)
			{
				System.out.println("webResponse = null for " + url);
				webResponseNullCounter++;
				removeFromCache(domain, url);
				continue;
			}
			
			if (null != webResponse.getDomain()) 
			{
				System.out.println("1. domain = " + webResponse.getDomain() + " should be null");
				webResponseDomainErrorCounter++;
			} else {
				putToCache(domain, url, webResponse);
			}
			
			webResponse = getFromCache(url);
			if (null == webResponse.getDomain())
			{
				System.out.println("1. domain = null, should be " + domain);
				webResponseDomainNullErrorCounter++;
			}
			
		} // for (String url : luceneIndexManager.getKeys())

		System.out.println("webResponseNullCounter = " + webResponseNullCounter + " webResponseDomainErrorCounter = " + webResponseDomainErrorCounter + ", webResponseDomainNullErrorCounter = " + webResponseDomainNullErrorCounter);
		
		return;
	}
}

