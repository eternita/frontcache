package org.frontcache.console.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.client.FrontCacheClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class FrontcacheService {

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
			BufferedReader reader = new BufferedReader(new InputStreamReader(FrontcacheService.class.getClassLoader().getResourceAsStream("frontcache-console.properties")));
			 
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