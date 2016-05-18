package org.frontcache.cluster.test;

import static org.junit.Assert.fail;

import java.util.Map;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.client.FrontCacheCluster;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gargoylesoftware.htmlunit.WebClient;

public class FrontcacheClusterTest {

	protected Logger logger = LoggerFactory.getLogger(FrontcacheClusterTest.class);  
	
	WebClient webClient = null;
	
	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.getOptions().setThrowExceptionOnScriptError(false);
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}
	
//	@Test
//	public void invalidate() throws Exception {
//		FrontCacheCluster fcCluster = new FrontCacheCluster("https://or.coinshome.net:443/", "https://sg.coinshome.net:443/", "https://origin.coinshome.net:443/");
//		fcCluster.removeFromCache("http://www.coinshome");
//		fcCluster.removeFromCache("https://www.coinshome");		
//	}
	
	/**
	 * test cache redistribution in cluster
	 * @throws Exception
	 */
//	@Test
	public void distributeCacheAcrossCluster() throws Exception {
		
		FrontCacheCluster fcCluster = new FrontCacheCluster("https://or.coinshome.net:443/", "https://sg.coinshome.net:443/", "https://origin.coinshome.net:443/");		
//		FrontCacheCluster fcCluster = new FrontCacheCluster("http://or.coinshome.net:80/", "http://sg.coinshome.net:80/");
		
		Map<FrontCacheClient, Long> updateCounterMap = fcCluster.reDistriburteCache();
//		logger.debug("Updates: ");
//		for (FrontCacheClient fcInstance : updateCounterMap.keySet())
//		{
//			logger.debug(fcInstance + " - " + updateCounterMap.get(fcInstance) + " updates posted");
//		}

		
		fail("stop");
	}
	
}

