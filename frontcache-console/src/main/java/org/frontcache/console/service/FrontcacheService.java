package org.frontcache.console.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.model.HystrixConnection;
import org.frontcache.hystrix.fr.FallbackConfigEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service("frontcacheService")
public class FrontcacheService {


	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);
	private ObjectMapper jsonMapper = new ObjectMapper();
	
	private Set<String> hosts = new HashSet<String>();
	
	private final static String FRONTCACHE_CONSOLE_CONFIG_FILE = "frontcache-console.conf";
	
	public FrontcacheService() {
		loadConfigs();
	}

	
	private void loadConfigs() {
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FrontcacheService.class.getClassLoader().getResourceAsStream(FRONTCACHE_CONSOLE_CONFIG_FILE);
			if (null == is)
			{
				logger.info("Console hosts are not loaded from " + FRONTCACHE_CONSOLE_CONFIG_FILE);
				return;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String hostStr;
			int hostCounter = 0;
			while ((hostStr = confReader.readLine()) != null) {
				if (hostStr.trim().startsWith("#")) // handle comments
					continue;
				
				if (0 == hostStr.trim().length()) // skip empty
					continue;
				
				hosts.add(hostStr);
				hostCounter++;
			}
			logger.info("Successfully loaded " + hostCounter +  " hosts to frontcache console");					
			
		} catch (Exception e) {
			logger.info("Console hosts are not loaded from " + FRONTCACHE_CONSOLE_CONFIG_FILE);
		} finally {
			if (null != confReader)
			{
				try {
					confReader.close();
				} catch (IOException e) { }
			}
			if (null != is)
			{
				try {
					is.close();
				} catch (IOException e) { }
			}
		}
		
	}
	

	/**
	 * 
	 * @return
	 */
	public Map<String, FrontCacheStatus> getClusterStatus() {
		List<FrontCacheClient> fcClients = getFrontCacheClients();

		Map<String, FrontCacheStatus> clusterStatus = new HashMap<String, FrontCacheStatus>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			FrontCacheStatus fcStatus = new FrontCacheStatus();
			fcStatus.setName(fcClient.getName());
			long cachedAmount = -1;
			boolean available = true;
			try
			{
				Map<String, String> cacheState = fcClient.getCacheState();
				if (null != cacheState)
					cachedAmount = Long.parseLong(cacheState.get("cached entiries"));
				else
					available = false;
			} catch (Exception ex) {
				available = false;
				ex.printStackTrace();
			}
			
			fcStatus.setAvailable(available);
			fcStatus.setCachedAmount(cachedAmount);
			
			clusterStatus.put(fcClient.getName(), fcStatus);
		}
		
		return clusterStatus;
	}
	

	/**
	 * 
	 * @param domain
	 * @return
	 */
	public Map<String, List<FallbackConfigEntry>> getFallbackConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheClients();

		Map<String, List<FallbackConfigEntry>> clusterStatus = new HashMap<String, List<FallbackConfigEntry>>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			List<FallbackConfigEntry> fallbackConfigs = fcClient.getFallbackConfigs(); 
			if (null != fallbackConfigs)
				clusterStatus.put(fcClient.getName(), fallbackConfigs);
		}
		
		return clusterStatus;
	}
	
	/**
	 * 
	 * @param domain
	 * @return
	 */
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

	private List<FrontCacheClient> getFrontCacheClients()
	{
		return getFrontCacheClients(false);
	}
	
	private List<FrontCacheClient> getFrontCacheClients(boolean activeOnly)
	{
		List<FrontCacheClient> fcClients = new ArrayList<FrontCacheClient>();
		for (String host : hosts)
		{
			FrontCacheClient fcClient = new FrontCacheClient("http://" + host + ":80/");
			
			if (activeOnly)
			{
				if (null != fcClient.getCacheState()) // if get state work -> FC is active
					fcClients.add(fcClient);
			} else {
				fcClients.add(fcClient);
			}
		}

		return fcClients;
	}
	
}
