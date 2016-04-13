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

	public Map<String, String> getCacheState()
	{
		Map<String, String> response = new HashMap<String, String>();
		for (FrontCacheClient client : fcCluster)
			response.put(client.getFrontCacheURL() ,client.getCacheState());

		return response;
	}

}
