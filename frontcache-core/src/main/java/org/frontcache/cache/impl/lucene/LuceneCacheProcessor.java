package org.frontcache.cache.impl.lucene;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Cache processor based on Apache Lucene.
 *
 */
public class LuceneCacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private static final Logger logger = LoggerFactory.getLogger(LuceneCacheProcessor.class);

	private LuceneIndexManager indexManager;
	
	private static String CACHE_BASE_DIR_DEFAULT = "/tmp/cache/";
	
	private static final String CACHE_BASE_DIR_KEY = "front-cache.cache-processor.impl.cache-dir"; // to override path in configs
	
	private static String CACHE_RELATIVE_DIR = "cache/lucene-index/";
	
	private static String INDEX_BASE_DIR = CACHE_BASE_DIR_DEFAULT + CACHE_RELATIVE_DIR;


	@Override
	public void init(Properties properties) {
		 
		Objects.requireNonNull(properties, "Properties should not be null");
		
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
		
		indexManager = new LuceneIndexManager(INDEX_BASE_DIR);
	}
	
	@Override
	public void destroy() {
		indexManager.close();
	}
	
	public LuceneIndexManager getIndexManager(){
		return indexManager;
	}

	/**
	 * Saves web response to cache-file
	 */
	@Override
	public void putToCache(String url, WebResponse component) {
		try {
			indexManager.indexDoc(component);
		} catch (IOException e) {
			logger.error("Error during putting response to cache", e);
		}
	}

	/**
	 * Load web-response from file
	 */
	@Override
	public WebResponse getFromCacheImpl(String url) {
		logger.debug("Getting from cache {}", url);
		WebResponse webResponse = indexManager.getResponse(url);
		if (webResponse.isExpired())
		{
			removeFromCache(url);
			return null;
		}
		
		return webResponse;
	}
	

	@Override
	public void removeFromCache(String filter) {
		logger.debug("Removing from cache {}", filter);
		indexManager.delete(filter);
	}

	@Override
	public void removeFromCacheAll() {
		logger.debug("truncate cache");
		indexManager.truncate();
	}
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = super.getCacheStatus();
		status.put("impl", this.getClass().getName());
		status.put(CacheProcessor.CACHED_ENTRIES, "" + indexManager.getIndexSize());
		return status;
	}
	
	@Override
	public List<String> getCachedKeys() {
		return indexManager.getKeys();
	}
	
}

