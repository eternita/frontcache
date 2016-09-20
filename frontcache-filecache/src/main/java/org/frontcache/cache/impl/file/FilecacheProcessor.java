package org.frontcache.cache.impl.file;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.apache.commons.codec.digest.DigestUtils;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;
import org.frontcache.core.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;


public class FilecacheProcessor extends CacheProcessorBase implements CacheProcessor {

	private  ObjectMapper mapper;
	private  ObjectReader reader;
	private  ObjectWriter writer;
	
	private static final Logger logger = LoggerFactory.getLogger(FilecacheProcessor.class);

	
	private static final String PREFIX = "front-cache.file-processor.impl.";
	
	private static String CACHE_BASE_DIR = "/tmp/";
	
	private static int CACHE_DIR_SIZE = 3;
	private static String CACHE_FILE_EXT = ".ht";
	
	public FilecacheProcessor(){

	}

	@Override
	public void init(Properties properties) {
		 mapper = new ObjectMapper();
		 reader = mapper.reader(WebResponse.class);
		 writer = mapper.writerWithType(WebResponse.class);
		 
		Objects.requireNonNull(properties, "Properties should not be null");
		CACHE_BASE_DIR = Optional.ofNullable(properties.getProperty(PREFIX + "cache-dir")).orElse(CACHE_BASE_DIR);
		CACHE_FILE_EXT = Optional.ofNullable(properties.getProperty(PREFIX + "file-ext")).orElse(CACHE_FILE_EXT);
		CACHE_DIR_SIZE = Optional.ofNullable(properties.getProperty(PREFIX + "cache-dir-size")).map(size -> Integer.parseInt(size)).orElse(CACHE_DIR_SIZE);
		
	}
	
	@Override
	public void destroy() {

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
		File cacheFile = getCacheFile(url);
		if (cacheFile.exists()) {
			try {
				WebResponse response = reader.readValue(cacheFile);
				return response;
			} catch (IOException e) {
				logger.error("Exception during reading from cache file", e);
			}
		}
		return null;
	}

	@Override
	public void removeFromCache(String url) {
		File cacheFile = getCacheFile(url);	
		if (cacheFile.exists()) {
			try{
				cacheFile.delete();
			}catch(Exception ex){
				logger.error("Error during removing file",ex);
			}

		}
	}

	@Override
	public void removeFromCacheAll() {

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
	
	static String getHash(String url){
		return  DigestUtils.md5Hex(url);
	}
	
	private String saveToFile(String url, WebResponse component) {

		File cacheFile = getCacheFile(url);

		if (!cacheFile.exists()) {
			try {
				cacheFile.getParentFile().mkdirs();
				cacheFile.createNewFile();
				logger.info(cacheFile.getAbsolutePath());
				writer.writeValue(cacheFile, component);
			} catch (IOException e) {
				logger.error("Error during creating cache-file", e);
			}
		}

		return url;

	}
	
	static File getCacheFile(String url) {
		String hash = getHash(url);

		int counter = 0;
		int deep = 2;

		StringBuffer str = new StringBuffer(hash.length() + CACHE_BASE_DIR.length() + deep + CACHE_FILE_EXT.length());
		str.append(CACHE_BASE_DIR);
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

		return new File(str.toString());
	}
	
}

