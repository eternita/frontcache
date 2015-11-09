package org.frontcache;

import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;

public class FrontCache {

	private static CacheProcessor cacheProcessor = CacheManager.getInstance(); // can be null (no caching)

	private FrontCache() {
	}

	public static void remove(String filter)
	{		
		if (null != cacheProcessor)
			cacheProcessor.removeFromCache(filter);
	}
	
	public static void removeAll()
	{		
		if (null != cacheProcessor)
			cacheProcessor.removeFromCacheAll();
	}
	
}
