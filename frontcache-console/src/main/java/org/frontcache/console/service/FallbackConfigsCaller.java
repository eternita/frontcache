package org.frontcache.console.service;

import java.util.concurrent.Callable;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.FallbackConfig;

public class FallbackConfigsCaller implements Callable<FallbackConfig> {
	
	private FrontCacheClient fcClient;
	
	public FallbackConfigsCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public FallbackConfig call() throws Exception {
		
		 return new FallbackConfig(fcClient.getName(), fcClient.getFallbackConfigs()); 
    }
}	
