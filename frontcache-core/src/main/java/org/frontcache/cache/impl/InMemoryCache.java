package org.frontcache.cache.impl;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.frontcache.WebComponent;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheProcessorBase;

public class InMemoryCache extends CacheProcessorBase implements CacheProcessor {


	private Map<String, WebComponent> cache = new ConcurrentHashMap<String, WebComponent>();

	private int currentSize = 0;
	private int maxSize = 0;
	
	@Override
	public void init(Properties properties) {
		try
		{
			String maxSizeStr = properties.getProperty("cache.inmemory.maxsize");
			String countStr = maxSizeStr;
			
			if (maxSizeStr.endsWith("G"))
			{
				countStr = maxSizeStr.substring(0, maxSizeStr.indexOf("G"));
				maxSize = Integer.parseInt(countStr)  * 1024 * 1024 * 1024; 
			} else if (maxSizeStr.endsWith("M")) {
				countStr = maxSizeStr.substring(0, maxSizeStr.indexOf("M"));
				maxSize = Integer.parseInt(countStr)  * 1024 * 1024; 
			} else if (maxSizeStr.endsWith("K")) {
				countStr = maxSizeStr.substring(0, maxSizeStr.indexOf("K"));
				maxSize = Integer.parseInt(countStr)  * 1024; 
			} else {
				maxSize = Integer.parseInt(countStr); 
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		logger.info("max cache size is " + maxSize);
	}	

	@Override
	public void putToCache(String url, WebComponent component) {
		
		int newSize = currentSize + component.getContent().length();
		if (newSize < maxSize)
		{
			cache.put(url, component);
			currentSize = newSize;
		} else {
			logger.info("web component is not cached - max cache size " + maxSize + " is reached");
		}
		
		return;
	}

	@Override
	public WebComponent getFromCache(String url) {
		return cache.get(url);
	}

	@Override
	public void destroy() {
		cache.clear();
		
		return;
	}	

	
}
