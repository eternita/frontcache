package org.frontcache;

import org.frontcache.cache.CacheProcessor;

public class CacheInvalidator {
	
	private CacheProcessor cacheProcessor = null;

	
	public CacheInvalidator(CacheProcessor cacheProcessor) {
		super();
		this.cacheProcessor = cacheProcessor;
	}

	protected CacheInvalidator() {	}

	public void remove(String filter)
	{
		cacheProcessor.removeFromCache(filter);
	}
	
	public void removeAll()
	{
		cacheProcessor.removeFromCacheAll();
	}
	
}
