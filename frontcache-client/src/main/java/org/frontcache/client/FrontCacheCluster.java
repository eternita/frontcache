package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.frontcache.core.WebResponse;
import org.frontcache.io.CachedKeysActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheCluster {

	private Set<FrontCacheClient> fcCluster = new HashSet<FrontCacheClient>();
	
	private final static String DEFAULT_CLUSTER_CONFIG_NAME = "frontcache-cluster.conf";
	
	private Logger logger = LoggerFactory.getLogger(FrontCacheCluster.class);
	
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

	public FrontCacheCluster(FrontCacheClient ... fcClients) 
	{
		for (FrontCacheClient fcClient : fcClients)
			fcCluster.add(fcClient);
	}

	public FrontCacheCluster() 
	{
		this(DEFAULT_CLUSTER_CONFIG_NAME);
	}
	
	public FrontCacheCluster(String configResourceName) 
	{
		Set<String> fcURLSet = loadFrontcacheClusterNodes(configResourceName);
		for (String url : fcURLSet)
			fcCluster.add(new FrontCacheClient(url));
	}

	
	private static FrontCacheCluster instance = null;
	
	public static FrontCacheCluster getInstance()
	{
		if (null == instance)
			instance = new FrontCacheCluster();
		
		return instance;
	}
	
	public static FrontCacheCluster reload()
	{
		instance = new FrontCacheCluster();
		return instance;
	}
	
	private Set<String> loadFrontcacheClusterNodes(String configName) {
		Set<String> fcURLSet = new HashSet<String>();
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FrontCacheCluster.class.getClassLoader().getResourceAsStream(configName);
			if (null == is)
				throw new RuntimeException("Frontcache cluster nodes can't be loaded from " + configName);

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String clusterNodeURLStr;
			while ((clusterNodeURLStr = confReader.readLine()) != null) {
				
				if (clusterNodeURLStr.trim().startsWith("#")) // handle comments
					continue;
				
				if (0 == clusterNodeURLStr.trim().length()) // skip empty
					continue;
				
				fcURLSet.add(clusterNodeURLStr);
			}
			
		} catch (Exception e) {
			throw new RuntimeException("Frontcache cluster nodes can't be loaded from " + configName, e);
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
		return fcURLSet;
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
		Map<String, String> response = new ConcurrentHashMap<String, String>();
		fcCluster.forEach(client -> response.put(client.getFrontCacheURL() ,client.getCacheState()));

//		for (FrontCacheClient client : fcCluster)
//			response.put(client.getFrontCacheURL() ,client.getCacheState());

		return response;
	}

	/**
	 * 
	 * @param filter
	 * @return
	 */
	public Map<String, String> removeFromCache(String filter)
	{
		Map<String, String> response = new ConcurrentHashMap<String, String>();
		fcCluster.forEach(client -> response.put(client.getFrontCacheURL() ,client.removeFromCache(filter)));

//		Map<String, String> response = new HashMap<String, String>();
//		for (FrontCacheClient client : fcCluster)
//			response.put(client.getFrontCacheURL() ,client.removeFromCache(filter));

		return response;
	}

	/**
	 * 
	 * @return
	 */
	public Map<String, String> removeFromCacheAll()
	{
		Map<String, String> response = new ConcurrentHashMap<String, String>();
		fcCluster.forEach(client -> response.put(client.getFrontCacheURL() ,client.removeFromCacheAll()));
		
//		Map<String, String> response = new HashMap<String, String>();
//		for (FrontCacheClient client : fcCluster)
//			response.put(client.getFrontCacheURL() ,client.removeFromCacheAll());

		return response;
	}
	
	/**
	 * 
	 * @return
	 */
	public Map<FrontCacheClient, CachedKeysActionResponse> getCachedKeys()
	{
		Map<FrontCacheClient, CachedKeysActionResponse> response = new ConcurrentHashMap<FrontCacheClient, CachedKeysActionResponse>();
		fcCluster.forEach(client -> response.put(client ,client.getCachedKeys()));

		return response;
	}

	/**
	 * 
	 * Put all cached objects to all nodes in cluster.
	 * Get all cached keys in cluster from all nodes - and re-populate difference for each node  
	 * 
	 * @return Map<FrontCacheClient, Long> - <fcClusterNode, amount of pushed updates to it>
	 */
	public Map<FrontCacheClient, Long> reDistriburteCache()
	{
		Map<FrontCacheClient, CachedKeysActionResponse> clusterCachedKeysActionResponseMap = getCachedKeys();
		final Map<String, FrontCacheClient> allKeys = new HashMap<String, FrontCacheClient>();
		Map<String, FrontCacheClient> missedKeys = new HashMap<String, FrontCacheClient>(); // keys missed in at least one node
		Map<FrontCacheClient, Long> updateCounterMap = new ConcurrentHashMap<FrontCacheClient, Long>();

		clusterCachedKeysActionResponseMap.forEach((fcInstance,resp)->{
			updateCounterMap.put(fcInstance, new Long(0)); // init counter
			for (String key : resp.getCachedKeys())
				allKeys.put(key, fcInstance);
			
			logger.debug(fcInstance + " - " + resp.getAmount() + " objects in cache");
		});
		
		logger.debug("total - " + allKeys.size() + " objects in all caches");

		allKeys.forEach((key, fcInstance)->{

			for (CachedKeysActionResponse resp : clusterCachedKeysActionResponseMap.values())
			{
				List<String> fcCachedKeysList = resp.getCachedKeys();
				if (!fcCachedKeysList.contains(key))
				{
					missedKeys.put(key, fcInstance);
					break;
				}
			}
		});
		
		allKeys.clear();
		logger.debug("total missed - " + missedKeys.size() + " objects are missed in some caches");

		missedKeys.forEach((key, fcWithCacheForKey)->{
			final WebResponse webResponse;
			// FC instance doesn't have object with such key in it's cache
			// get WebResponse from node which has it
			GetFromCacheActionResponse gfcResp = fcWithCacheForKey.getFromCache(key);
			if (null != gfcResp && null != gfcResp.getValue())
			{
				webResponse = gfcResp.getValue();
			} else {
				webResponse = null;
			}
			
			clusterCachedKeysActionResponseMap.forEach((fcInstance,resp)->{
				List<String> fcCachedKeysList = resp.getCachedKeys();
				if (!fcCachedKeysList.contains(key))
				{

					if (null != webResponse)
					{
						fcInstance.putToCache(webResponse); // push WebResponse to cache on fcInstance 
						incUpdateCounterMap(updateCounterMap, fcInstance);
					} else {
						logger.debug("Can't put " + key + " to " + fcInstance);
					}
					
				} else {
					// this fc has WebResponse with key='key' cached
				}
			});
			
		});

		logger.debug("Updates: ");
		for (FrontCacheClient fcInstance : updateCounterMap.keySet())
		{
			logger.debug(fcInstance + " - " + updateCounterMap.get(fcInstance) + " updates posted");
		}
		
		return updateCounterMap;
	}
	
	private void incUpdateCounterMap(Map<FrontCacheClient, Long> updateCounterMap, FrontCacheClient fcInstance)
	{
		Long updateCounter = updateCounterMap.get(fcInstance);
		if (null == updateCounter)
		{
			updateCounterMap.put(fcInstance, new Long(1));
		} else {
			updateCounterMap.put(fcInstance, new Long(1 + updateCounter.longValue()));
		}
		return;
	}
	
}
