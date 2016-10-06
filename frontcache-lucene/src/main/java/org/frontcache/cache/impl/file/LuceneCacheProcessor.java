package org.frontcache.cache.impl.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

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

	private IndexManager indexManager;
	
	private static String INDEX_PATH_SUFFIX = "index/";
	
	private static final String PREFIX = "front-cache.file-processor.impl.";
	
	private static String CACHE_BASE_DIR = "/tmp/cache/";
	
	private static String INDEX_PATH = "/tmp/index/";


	@Override
	public void init(Properties properties) {
		 
		Objects.requireNonNull(properties, "Properties should not be null");
		CACHE_BASE_DIR = Optional.ofNullable(properties.getProperty(PREFIX + "cache-dir")).orElse(CACHE_BASE_DIR);
		INDEX_PATH = CACHE_BASE_DIR + INDEX_PATH_SUFFIX;
		indexManager = new IndexManager(INDEX_PATH);
	}
	
	@Override
	public void destroy() {
		indexManager.close();
	}
	
	public IndexManager getIndexManager(){
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
		return indexManager.getResponse(url);
	}
	

	@Override
	public void removeFromCache(String url) {
		logger.debug("Removing from cache {}", url);
		indexManager.deleteByUrl(url);
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
		List<String> keys = new ArrayList<String>();
		//TODO: not implemented
		return keys;
	}
	
}

