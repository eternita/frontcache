package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FrontCacheCluster {

	private Set<FrontCacheClient> fcCluster = new HashSet<FrontCacheClient>();
	
	private final static String DEFAULT_CLUSTER_CONFIG_NAME = "frontcache-cluster.conf";
	
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

	public FrontCacheCluster() 
	{
		this(DEFAULT_CLUSTER_CONFIG_NAME);
	}
	
	public FrontCacheCluster(String configResourceName) 
	{
		Set<String> fcURLSet = loadCacheIgnoreURIPatterns(configResourceName);
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
	
	private Set<String> loadCacheIgnoreURIPatterns(String configName) {
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
	
}
