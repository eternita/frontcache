/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.console.model.BotConfig;
import org.frontcache.console.model.DynamicURLsConfig;
import org.frontcache.console.model.FallbackConfig;
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
	
	private static final int THREAD_AMOUNT = 4;
    
	private ExecutorService executor = Executors.newFixedThreadPool(THREAD_AMOUNT);
	
	private static final long FRONTCACHE_CLIENT_TIMEOUT = FrontCacheClient.CONNECTION_TIMEOUT + 1000; // slightly more then frontcache client timeout
	
	
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
		
        List<Future<FrontCacheStatus>> futureList = new ArrayList<Future<FrontCacheStatus>>();
		
		Map<String, FrontCacheStatus> clusterStatus = new HashMap<String, FrontCacheStatus>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new FrontCacheStatusCaller(fcClient)));

		// processing timeouts 
        boolean timeoutReached = false;
        for (Future<FrontCacheStatus> f : futureList)
        {
            try {
            	FrontCacheStatus result = null;
            	if (timeoutReached)
            		result = f.get(1, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
            		
            	if (null != result)
            		clusterStatus.put(result.getName(), result);
            		
            } catch (TimeoutException | InterruptedException | ExecutionException e) { 
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached for resolving includes. Some statuses may not be retrieved ");
            }
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

        List<Future<FallbackConfig>> futureList = new ArrayList<Future<FallbackConfig>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new FallbackConfigsCaller(fcClient)));

		// processing timeouts 
        boolean timeoutReached = false;
        for (Future<FallbackConfig> f : futureList)
        {
            try {
            	FallbackConfig result = null;
            	if (timeoutReached)
            		result = f.get(1, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
            		
            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());
            		
            } catch (TimeoutException | InterruptedException | ExecutionException e) { 
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached for resolving includes. Some configs may not be retrieved ");
            }
        }
		
		return clusterStatus;
	}

	public Map<String,  Map<String, Set<String>>> getBotConfigs() {
		
        List<Future<BotConfig>> futureList = new ArrayList<Future<BotConfig>>();
		
		Map<String, Map<String, Set<String>>> clusterStatus = new HashMap<String, Map<String, Set<String>>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new BotConfigsCaller(fcClient)));

		// processing timeouts 
        boolean timeoutReached = false;
        for (Future<BotConfig> f : futureList)
        {
            try {
            	BotConfig result = null;
            	if (timeoutReached)
            		result = f.get(1, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
            		
            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());
            		
            } catch (TimeoutException | InterruptedException | ExecutionException e) { 
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached for resolving includes. Some configs may not be retrieved ");
            }
        }

		return clusterStatus;
	}
	
	public Map<String, Map<String, Set<String>>> getDynamicURLsConfigs() {

        List<Future<DynamicURLsConfig>> futureList = new ArrayList<Future<DynamicURLsConfig>>();
		
		Map<String, Map<String, Set<String>>> clusterStatus = new HashMap<String, Map<String, Set<String>>>();

		for (FrontCacheClient fcClient : getFrontCacheAgents())
            futureList.add(executor.submit(new DynamicURLsConfigsCaller(fcClient)));

		// processing timeouts 
        boolean timeoutReached = false;
        for (Future<DynamicURLsConfig> f : futureList)
        {
            try {
            	DynamicURLsConfig result = null;
            	if (timeoutReached)
            		result = f.get(1, TimeUnit.MILLISECONDS);
            	else
            		result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
            		
            	if (null != result)
            		clusterStatus.put(result.getName(), result.getConfig());
            		
            } catch (TimeoutException | InterruptedException | ExecutionException e) { 
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached for resolving includes. Some configs may not be retrieved ");
            }
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
