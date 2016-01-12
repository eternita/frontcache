package org.frontcache;

import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;

public abstract class FrontCacheClient {
	
	private static FrontCacheClient instance;

	public static FrontCacheClient getInstance(){
		if (null == instance) {
			CacheProcessor cacheProcessor = CacheManager.getInstance();
			instance = cacheProcessor.getFrontCacheClient();
		}
		return instance;
	}
	
	protected FrontCacheClient() {	}

	public abstract void remove(String filter);
	
	public abstract void removeAll();
	
}
