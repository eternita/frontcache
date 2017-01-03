package org.frontcache.console.service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.FCConfig;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.FrontCacheStatus;
import org.frontcache.console.model.HystrixConnection;
import org.frontcache.core.WebResponse;
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
	
	private Set<String> frontcacheAgentURLs = new LinkedHashSet<String>();
	
	public FrontcacheService() {
		loadConfigs();
	}

	private void loadConfigs() {
		String frontcacheConsoleConfPath = System.getProperty("org.frontcache.console.config");
		
		if (null == frontcacheConsoleConfPath)
		{
			logger.info("System property 'org.frontcache.console.config' is not defined");					
			return;
		}
		
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = new FileInputStream(frontcacheConsoleConfPath);
			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String frontcacheURLStr;
			int hostCounter = 0;
			while ((frontcacheURLStr = confReader.readLine()) != null) {
				if (frontcacheURLStr.trim().startsWith("#")) // handle comments
					continue;
				
				if (0 == frontcacheURLStr.trim().length()) // skip empty
					continue;
				
				frontcacheAgentURLs.add(frontcacheURLStr);
				hostCounter++;
			}
			logger.info("Successfully loaded " + hostCounter +  " frontcacheAgentURLs to frontcache console");					
			
		} catch (Exception e) {
			logger.error("Console frontcacheAgentURLs are not loaded from " + frontcacheConsoleConfPath);
			throw new RuntimeException("Can't initialize Frontcache Console", e);
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
	
	public boolean isFrontCacheEdgeAvailable(String frontcacheURL)
	{
		boolean available = false;
		FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL);
		if (null != fcClient.getCacheState()) // if get state work -> FC is active
			available = true;
		
		return available;
	}

	/**
	 * 
	 * @return
	 */
	public Map<String, FrontCacheStatus> getClusterStatus() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

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
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

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

	public Map<String, Set<String>> getBotConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		Map<String, Set<String>> clusterStatus = new HashMap<String, Set<String>>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			Set<String> botConfigs = fcClient.getBots().get(FCConfig.DEFAULT_DOMAIN); 
			if (null != botConfigs)
				clusterStatus.put(fcClient.getName(), botConfigs);
		}
		
		return clusterStatus;
	}

	public Map<String, Set<String>> getDynamicURLsConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		Map<String, Set<String>> clusterStatus = new HashMap<String, Set<String>>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			Set<String> dynamicURLs = fcClient.getDynamicURLs().get(FCConfig.DEFAULT_DOMAIN); 
			if (null != dynamicURLs)
				clusterStatus.put(fcClient.getName(), dynamicURLs);
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
		
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		List<HystrixConnection> hystrixConnections = new ArrayList<HystrixConnection>();
		
		for (FrontCacheClient fcClient : fcClients)
		{
			String fcURL = fcClient.getFrontCacheURL();
			if (!fcURL.endsWith("/"))
				fcURL += "/";
			hystrixConnections.add(new HystrixConnection(fcClient.getName(), fcURL + "hystrix.stream"));
		}
		
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

	public Set<String> getFrontCacheAgentURLs()
	{
		Set<String> urls = new LinkedHashSet<String>();
		urls.addAll(frontcacheAgentURLs);
		
		return urls;
	}
	
	public WebResponse getFromCache(String edgeURL, String key)
	{
		if (null == key)
			return null;
		
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL);
		WebResponse webResponse = fcClient.getFromCache(key.trim());
		
		return webResponse;
	}
	
	
	private List<FrontCacheClient> getFrontCacheAgents()
	{
		return getFrontCacheAgents(false);
	}

	private List<FrontCacheClient> getFrontCacheAgents(boolean activeOnly)
	{
		List<FrontCacheClient> fcClients = new ArrayList<FrontCacheClient>();
		for (String frontcacheURL : frontcacheAgentURLs)
		{
			FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL);
			
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

	public int getEdgesAmount() {
		return frontcacheAgentURLs.size();
	}
	
	public void addEdge(String edge) {
		if (null != edge)
			frontcacheAgentURLs.add(edge);
	}
	
	public void removeEdge(String edge) {
		if (null != edge)
			frontcacheAgentURLs.remove(edge);
	}
	
	public void invalidateEdge(String edgeURL, String filter) {
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL);
		fcClient.removeFromCache(filter);
	}
	
}
