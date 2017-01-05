package org.frontcache.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FrontCacheAgentCluster {

	private Set<FrontCacheAgent> fcCluster = new HashSet<FrontCacheAgent>();
	
	private final static String DEFAULT_CLUSTER_CONFIG_NAME = "frontcache-cluster.conf";
	
	private final static String SITE_KEY_CONFIG_FILE = "frontcache-site-key.conf";
	
	private final static String DEFAULT_SITE_KEY = "";
	
	public FrontCacheAgentCluster(Collection<String> fcURLSet, String siteKey) 
	{
		for (String url : fcURLSet)
			fcCluster.add(new FrontCacheAgent(url, siteKey));
	}
	
	public FrontCacheAgentCluster(FrontCacheAgent ... fcClients) 
	{
		for (FrontCacheAgent fcClient : fcClients)
			fcCluster.add(fcClient);
	}

	public FrontCacheAgentCluster(Collection<FrontCacheAgent> fcClients) 
	{
		for (FrontCacheAgent fcClient : fcClients)
			fcCluster.add(fcClient);
	}

	public FrontCacheAgentCluster() 
	{
		this(DEFAULT_CLUSTER_CONFIG_NAME);
	}
	
	public FrontCacheAgentCluster(String configResourceName) 
	{
		Set<String> fcURLSet = loadFrontcacheClusterNodes(configResourceName);
		String siteKey = loadSiteKey();
		
		for (String url : fcURLSet)
			fcCluster.add(new FrontCacheAgent(url, siteKey));
	}

	private String loadSiteKey()
	{
		String siteKey = DEFAULT_SITE_KEY;
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FrontCacheAgentCluster.class.getClassLoader().getResourceAsStream(SITE_KEY_CONFIG_FILE);
			if (null == is)
			{
				return siteKey;
			}

			confReader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			siteKey = confReader.readLine();
			if (null == siteKey)
				siteKey = "";
			
		} catch (Exception e) {
			// TODO: log ...
//			throw new RuntimeException("Frontcache cluster nodes can't be loaded from " + configName, e);
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
		
		return siteKey;
	}
		
	private Set<String> loadFrontcacheClusterNodes(String configName) {
		Set<String> fcURLSet = new HashSet<String>();
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FrontCacheAgentCluster.class.getClassLoader().getResourceAsStream(configName);
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

	/**
	 * 
	 * @param filter
	 * @return
	 */
	public Map<String, String> removeFromCache(String filter)
	{
		Map<String, String> response = new ConcurrentHashMap<String, String>();
		fcCluster.forEach(client -> response.put(client.getFrontCacheURL() ,client.removeFromCache(filter)));
		return response;
	}

	
}
