package org.frontcache.cache.impl.file;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.FileUtils;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.frontcache.cache.impl.file.IndexManager.*;


public class FilecacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private static final Logger logger = LoggerFactory.getLogger(FilecacheProcessor.class);

	private IndexManager indexManager;
	
	private static String INDEX_PATH_SUFFIX = "index/";
	private static String FILES_PATH_SUFFIX = "files/";
	
	private ExecutorService service;
	
	private static final String PREFIX = "front-cache.file-processor.impl.";
	
	private static String CACHE_BASE_DIR = "/tmp/cache/";
	
	private static int CACHE_DIR_SIZE = 3;
	private static String CACHE_FILE_EXT = ".ht";
	
	private static String INDEX_PATH = "/tmp/index/";
	private static String FILES_PATH = "/tmp/files/";
	

	
	public FilecacheProcessor(){
		service = Executors.newFixedThreadPool(1);
	}

	@Override
	public void init(Properties properties) {
		 
		Objects.requireNonNull(properties, "Properties should not be null");
		CACHE_BASE_DIR = Optional.ofNullable(properties.getProperty(PREFIX + "cache-dir")).orElse(CACHE_BASE_DIR);
		CACHE_FILE_EXT = Optional.ofNullable(properties.getProperty(PREFIX + "file-ext")).orElse(CACHE_FILE_EXT);
		CACHE_DIR_SIZE = Optional.ofNullable(properties.getProperty(PREFIX + "cache-dir-size")).map(size -> Integer.parseInt(size)).orElse(CACHE_DIR_SIZE);
		INDEX_PATH = CACHE_BASE_DIR + INDEX_PATH_SUFFIX;
		FILES_PATH = CACHE_BASE_DIR + FILES_PATH_SUFFIX;
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
		saveToFile(url, component);
	}

	/**
	 * Load web-response from file
	 */
	@Override
	public WebResponse getFromCacheImpl(String url) {
		logger.debug(url);
		Path file = getCacheFile(url);
		if (Files.exists(file)) {
			//try {
				WebResponse response = indexManager.getBaseResponse(url);
				//response.setContent(Files.readAllBytes(file));
				return response;
//			} catch (IOException e) {
//				logger.error("Exception during reading from cache file", e);
//			}
		}
		return null;
	}
	

	@Override
	public void removeFromCache(String url) {
		Path cacheFile = getCacheFile(url);	
		if (Files.exists(cacheFile)) {
			try{
				Files.delete(cacheFile);
			}catch(Exception ex){
				logger.error("Error during removing file",ex);
			}
		}
		
		// TODO: remove from lucene
	}

	@Override
	public void removeFromCacheAll() {
		indexManager.truncate();
		try {
			File file = new File(FILES_PATH);
			if (file.isDirectory()) {
				logger.info("Removing cache dir: " + FILES_PATH);
				FileUtils.cleanDirectory(file);
			}
		} catch (Exception e) {
			logger.error("Error during removing all files in cache", e);
		}
	}
	
	@Override
	public Map<String, String> getCacheStatus() {
		Map<String, String> status = super.getCacheStatus();
		//TODO: not implemented for files
		return status;
	}
	
	@Override
	public List<String> getCachedKeys() {
		List<String> keys = new ArrayList<String>();
		//TODO: not implemented for files
		return keys;
	}
	
	private String saveToFile(String url, WebResponse component) {

		Path cacheFile = getCacheFile(url);

		if (!Files.exists(cacheFile)) {
			try {
				Files.createDirectories(cacheFile.getParent());
				Files.createFile(cacheFile);

				logger.info(cacheFile.toString());

				Files.write(cacheFile, component.getContent());
				indexManager.indexDoc(component);
			} catch (IOException e) {
				// TODO: if error remove both files
				logger.error("Error during creating cache-file", e);
			}
		}

		return url;

	}
	
	static Path getCacheFile(String url) {
		String hash = getHash(url);

		int counter = 0;
		int deep = 2;

		StringBuffer str = new StringBuffer(hash.length() + FILES_PATH.length() + deep + CACHE_FILE_EXT.length());
		str.append(FILES_PATH);
		for (Character c : hash.toCharArray()) {
			str.append(c);
			counter++;
			if (counter >= CACHE_DIR_SIZE) {
				counter = 0;
				--deep;
				if (deep >= 0) {
					str.append(File.separator);
				}
			}
		}

		str.append(CACHE_FILE_EXT);

		return Paths.get(str.toString());
	}
	
}

