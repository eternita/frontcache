package org.frontcache.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FrontCacheCluster {

	private Set<FrontCacheClient> fcCluster = new HashSet<FrontCacheClient>();
	
	private final static String DEFAULT_CLUSTER_CONFIG_NAME = "frontcache-cluster.conf";
	
	private final static String SITE_KEY_CONFIG_FILE = "frontcache-site-key.conf";
	
	private final static String DEFAULT_SITE_KEY = "";
	
	private Logger logger = LoggerFactory.getLogger(FrontCacheCluster.class);
	
	private static final int THREAD_AMOUNT = 4;
    
	private ExecutorService executor = Executors.newFixedThreadPool(THREAD_AMOUNT);
	
	private static final long FRONTCACHE_CLIENT_TIMEOUT = 5*1000; // 5 second
	
	
	public FrontCacheCluster(Collection<String> fcURLSet, String siteKey) 
	{
		for (String url : fcURLSet)
			fcCluster.add(new FrontCacheClient(url, siteKey));
	}
	
	public FrontCacheCluster(Collection<FrontCacheClient> fcClients) 
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
		String siteKey = loadSiteKey();

		for (String url : fcURLSet)
			fcCluster.add(new FrontCacheClient(url, siteKey));
	}
	
	public void close()
	{
		executor.shutdown();
	}

	private String loadSiteKey()
	{
		String siteKey = DEFAULT_SITE_KEY;
		BufferedReader confReader = null;
		InputStream is = null;
		try 
		{
			is = FrontCacheCluster.class.getClassLoader().getResourceAsStream(SITE_KEY_CONFIG_FILE);
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
			logger.error("Frontcache cluster nodes can't be loaded from " + configName, e);
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
	
//	/**
//	 * 
//	 * @return
//	 */
//	public Map<FrontCacheClient, Map<String, String>> getCacheState()
//	{
//		Map<FrontCacheClient, Map<String, String>> response = new ConcurrentHashMap<FrontCacheClient, Map<String, String>>();
////		fcCluster.forEach(client -> response.put(client.getFrontCacheURL() ,client.getCacheState()));
//
//		for (FrontCacheClient client : fcCluster)
//		{
//			Map<String, String> cacheStatus = client.getCacheState();
//			if (null != cacheStatus)
//				response.put(client, cacheStatus);
//		}
//
//		return response;
//	}

	/**
	 * 
	 * @param filter
	 * @return
	 */
	public Map<String, String> removeFromCache(String filter)
	{
		Map<String, String> response = new ConcurrentHashMap<String, String>();
        List<Future<InvalidationResponse>> futureList = new ArrayList<Future<InvalidationResponse>>();
        
		fcCluster.forEach(client -> futureList.add(executor.submit(new InvalidationCaller(client, filter))));

		futureList.forEach(f -> 
					{
			            try {
			            	InvalidationResponse result = f.get(FRONTCACHE_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
			            		
			            	if (null != result)
			            		response.put(result.getName(), result.getResponse());
			            		
			            } catch (TimeoutException | InterruptedException | ExecutionException e) { 
			                f.cancel(true);
			                logger.debug("timeout (" + FRONTCACHE_CLIENT_TIMEOUT + ") reached for invalidation. Some cache instances may not invalidated ");
			            }
					}
				);

		return response;
	}

	/**
	 * 
	 * @return
	 */
	public Map<String, String> removeFromCacheAll()
	{
		return removeFromCache(null);
	}
	
}


/**
 * 
 */
class InvalidationResponse {

	private String name;
	private String response;
	
	public InvalidationResponse(String name, String response) {
		super();
		this.name = name;
		this.response = response;
	}

	public String getName() {
		return name;
	}

	public String getResponse() {
		return response;
	}

}

/**
 * 
 */
class InvalidationCaller implements Callable<InvalidationResponse> {
	
	private FrontCacheClient fcClient;
	private String filter;
	
	public InvalidationCaller(FrontCacheClient fcClient, String filter) {
		super();
		this.fcClient = fcClient;
		this.filter = filter;
	}
	
    @Override
    public InvalidationResponse call() throws Exception {
		String resp = (null == filter) ? fcClient.removeFromCacheAll() : fcClient.removeFromCache(filter); 
		return new InvalidationResponse(fcClient.getFrontCacheURL(), resp); 
    }
}	

