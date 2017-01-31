package org.frontcache.console.service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;

@Service("frontcacheService")
public class FrontcacheService {


	private Config fcConsoleConfig = null;


	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);
	private ObjectMapper jsonMapper = new ObjectMapper();
	
	private Set<String> frontcacheAgentURLs = new LinkedHashSet<String>();
	
	private String siteKey = "default-site-key";
	
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
		
		try 
		{
			File configFile = new File(frontcacheConsoleConfPath);
			if (!configFile.exists())
			{
				logger.info("Console config file doesn't exist: " + configFile.getAbsolutePath());					
				return;
			}
			
			fcConsoleConfig = ConfigFactory.parseFile(configFile).getConfig("frontcache").getConfig("console");
			
		} catch (Exception e) {
			logger.error("Console frontcacheAgentURLs are not loaded from " + frontcacheConsoleConfPath);
			throw new RuntimeException("Can't initialize Frontcache Console", e);
		}
		
		List<ConfigValue> urls = fcConsoleConfig.getList("urls");
		for (ConfigValue urlValue : urls) {
			String url = urlValue.unwrapped().toString();
			logger.info(" -- Loading url {} from console config", url);
			frontcacheAgentURLs.add(url);
		}
		siteKey = fcConsoleConfig.getString("siteKey");
		logger.info(" -- Loading siteKey {} from console config", siteKey);
		
	}
	
	public String getSiteKey(){
		return siteKey;
	}
	
	public boolean isFrontCacheEdgeAvailable(String frontcacheURL)
	{
		boolean available = false;
		FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL, siteKey);
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
	public Map<String, Map<String, Set<FallbackConfigEntry>>> getFallbackConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		Map<String, Map<String, Set<FallbackConfigEntry>>> clusterStatus = new HashMap<String, Map<String, Set<FallbackConfigEntry>>>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			Map<String, Set<FallbackConfigEntry>> fallbackConfigs = fcClient.getFallbackConfigs(); 
			if (null != fallbackConfigs)
				clusterStatus.put(fcClient.getName(), fallbackConfigs);
		}
		
		return clusterStatus;
	}

	public Map<String,  Map<String, Set<String>>> getBotConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		// Map <clusterNode, Map <domain, Set <BotConfgi>>>
		Map<String,  Map<String, Set<String>>> clusterStatus = new HashMap<String,  Map<String, Set<String>>>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			 Map<String, Set<String>> botConfigs = fcClient.getBots(); 
			if (null != botConfigs)
				clusterStatus.put(fcClient.getName(), botConfigs);
		}
		
		return clusterStatus;
	}
	
	public Map<String, Map<String, Set<String>>> getDynamicURLsConfigs() {
		List<FrontCacheClient> fcClients = getFrontCacheAgents();

		Map<String, Map<String, Set<String>>> clusterStatus = new HashMap<String, Map<String, Set<String>>>();
		// TODO: make requests to nodes concurrent
		for (FrontCacheClient fcClient : fcClients)
		{
			Map<String, Set<String>> dynamicURLs = fcClient.getDynamicURLs(); 
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
		
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
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
			FrontCacheClient fcClient = new FrontCacheClient(frontcacheURL, siteKey);
			
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
		FrontCacheClient fcClient = new FrontCacheClient(edgeURL, siteKey);
		fcClient.removeFromCache(filter);
	}
	
}
