package org.frontcache.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FrontCacheCluster {

	private Set<FrontCacheClient> fcCluster = new HashSet<FrontCacheClient>();
	
	public FrontCacheCluster(Set<String> fcURLSet) 
	{
		for (String url : fcURLSet)
			fcCluster.add(new FrontCacheClient(url));
	}
	
	public FrontCacheCluster(String ... fcURLs) 
	{
		for (String url : fcURLs)
			fcCluster.add(new FrontCacheClient(url));
	}


	public Set<String> getNodes()
	{
		Set<String> nodes = new HashSet<String>();
		
		for (FrontCacheClient client : fcCluster)
			nodes.add(client.getFrontCacheURL());
			
		return nodes;
	}
	
	/**
	 * 
	 * @return
	 */
	public Map<String, String> getCacheState()
	{
		Map<String, String> response = new HashMap<String, String>();
		for (FrontCacheClient client : fcCluster)
			response.put(client.getFrontCacheURL() ,client.getCacheState());

		return response;
	}

	/**
	 * 
	 * @param filter
	 * @return
	 */
	public Map<String, String> removeFromCache(String filter)
	{
		Map<String, String> response = new HashMap<String, String>();
		for (FrontCacheClient client : fcCluster)
			response.put(client.getFrontCacheURL() ,client.removeFromCache(filter));

		return response;
	}

	/**
	 * 
	 * @return
	 */
	public Map<String, String> removeFromCacheAll()
	{
		Map<String, String> response = new HashMap<String, String>();
		for (FrontCacheClient client : fcCluster)
			response.put(client.getFrontCacheURL() ,client.removeFromCacheAll());

		return response;
	}
	
}
