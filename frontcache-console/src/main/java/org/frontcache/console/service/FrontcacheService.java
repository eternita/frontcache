package org.frontcache.console.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.FCConfig;
import org.frontcache.client.FrontCacheClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FrontcacheService {

	public static final String FRONTCACHE_CONSOLE_CONFIG_PATH_SYSTEM_KEY = "org.frontcache.console.config"; 
	
	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);
	private ObjectMapper jsonMapper = new ObjectMapper();

	public Map<String, String> getCachedAmount() {

		List<FrontCacheClient> fcClients = getFrontCacheClients();
		Map<String, String> cachedAmount = new HashMap<String, String>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			cachedAmount.put(fcClient.getName(), fcClient.getCacheState().get("cached entiries"));
		}
		logger.debug("getCachedAmount() is executed!");

		return cachedAmount;

	}
	
	private List<FrontCacheClient> getFrontCacheClients()
	{
		List<FrontCacheClient> fcClients = new ArrayList<FrontCacheClient>();
		try {
			// data in frontcache-console.properties
			//"https://or.coinshome.net:443/"
			//"https://sg.coinshome.net:443/"
			BufferedReader reader = new BufferedReader(new InputStreamReader(getConfigInputStream("frontcache-console.properties")));
			 
			while (true) {
			    String line = reader.readLine();
			    if (line == null)
			      break;
				if (line.startsWith("http"))
					fcClients.add(new FrontCacheClient(line));
			}
			reader.close();
		} catch (IOException e) {
			logger.error("Can't read frontcache-console.properties", e);
			e.printStackTrace();
		}
		
		return fcClients;
	}
	
	public static InputStream getConfigInputStream(String name)
	{
		
		InputStream is = null;
		
		// 1. get input stream from system variable frontcache.home
		String frontcacheConsoleConfigPath = System.getProperty(FRONTCACHE_CONSOLE_CONFIG_PATH_SYSTEM_KEY);
		
		if (null != frontcacheConsoleConfigPath)
		{
			File frontcacheConsoleConfigFile = new File(frontcacheConsoleConfigPath);
			if (frontcacheConsoleConfigFile.exists())
			{
				try {
					is = new FileInputStream(frontcacheConsoleConfigFile);
					logger.info(name + " is loaded from " + frontcacheConsoleConfigPath);
				} catch (Exception e) {		}
			}
		}
		
		if (null != is)
			return is;
		
		
		// 2. get input stream from inside jars (/frontcache-console.properties)
		try 
		{
			is = FrontcacheService.class.getClassLoader().getResourceAsStream(name);
			logger.info(name + " is loaded from class path");
		} catch (Exception e) {		}
		
		
    	if (null == is)
    		throw new RuntimeException("Can't load " + name + " from classpath and " + FRONTCACHE_CONSOLE_CONFIG_PATH_SYSTEM_KEY + " (java system variable) ");
		
		return is;
	}
	
	
	public String getHystrixMonitorURLList()
	{
		// [{"name":"My Super App","stream":"http://sg.coinshome.net/hystrix.stream","auth":"","delay":"2000"},{"name":"My Super App","stream":"http://or.coinshome.net/hystrix.stream","auth":"","delay":"2000"}]
		
		List<FrontCacheClient> fcClients = getFrontCacheClients();

		List<HystrixConnection> hystrixConnections = new ArrayList<HystrixConnection>();
		
		for (FrontCacheClient fcClient : fcClients)
			hystrixConnections.add(new HystrixConnection(fcClient.getName(), fcClient.getFrontCacheURL() + "hystrix.stream"));
		
		String urlListStr = "";
		try {
			urlListStr = jsonMapper.writeValueAsString(hystrixConnections);
		} catch (JsonProcessingException e1) {
			logger.error("Can't serialize array of Hystrix connections", e1);
			urlListStr = e1.getMessage();
			e1.printStackTrace();
		} 
		return urlListStr; 
	}

}