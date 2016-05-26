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

@Service
public class FrontcacheService {

	private static final Logger logger = LoggerFactory.getLogger(FrontcacheService.class);

	public Map<String, String> getCachedAmount() {

		List<FrontCacheClient> fcClients = getFrontCacheClients();
		Map<String, String> cachedAmount = new HashMap<String, String>();
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
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
//		
//		
//		fcClients.add(new FrontCacheClient("https://or.coinshome.net:443/"));
//		fcClients.add(new FrontCacheClient("https://sg.coinshome.net:443/"));
		
		return fcClients;
	}

}