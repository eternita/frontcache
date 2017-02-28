package org.frontcache.console.service;

import java.util.concurrent.Callable;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.BotConfig;

public class BotConfigsCaller implements Callable<BotConfig> {
	
	private FrontCacheClient fcClient;
	
	public BotConfigsCaller(FrontCacheClient fcClient) {
		super();
		this.fcClient = fcClient;
	}

    @Override
    public BotConfig call() throws Exception {
		
		 return new BotConfig(fcClient.getName(), fcClient.getBots()); 
    }
}	
