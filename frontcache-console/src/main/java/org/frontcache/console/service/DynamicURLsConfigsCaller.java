package org.frontcache.console.service;

import java.util.concurrent.Callable;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.DynamicURLsConfig;

public class DynamicURLsConfigsCaller implements Callable<DynamicURLsConfig> {
	
	private FrontCacheClient fcClient;
	
	public DynamicURLsConfigsCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public DynamicURLsConfig call() throws Exception {
		
		 return new DynamicURLsConfig(fcClient.getName(), fcClient.getDynamicURLs()); 
    }
}	
