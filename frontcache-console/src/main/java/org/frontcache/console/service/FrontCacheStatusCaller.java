package org.frontcache.console.service;

import java.util.Map;
import java.util.concurrent.Callable;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.FrontCacheStatus;

public class FrontCacheStatusCaller implements Callable<FrontCacheStatus> {
	
	private FrontCacheClient fcClient;
	
	public FrontCacheStatusCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public FrontCacheStatus call() throws Exception {
		
    	FrontCacheStatus fcStatus = new FrontCacheStatus();
		fcStatus.setName(fcClient.getName());
		long cachedAmount = -1;
		boolean available = true;
		try
		{
			Map<String, String> cacheState = fcClient.getCacheState();
			if (null != cacheState)
			{
				String cacheAmountStr = cacheState.get(CacheProcessor.CACHED_ENTRIES);
				
				if (null != cacheAmountStr)
				{
					try
					{
						cachedAmount = Long.parseLong(cacheAmountStr);							
					} catch (Exception e) {e.printStackTrace();}
				}
			} else {
				available = false;
			}
		} catch (Exception ex) {
			available = false;
			ex.printStackTrace();
		}
		
		fcStatus.setAvailable(available);
		fcStatus.setCachedAmount(cachedAmount);
		fcStatus.setUrl(fcClient.getFrontCacheURL());
    	
        return fcStatus;
    }
}	
