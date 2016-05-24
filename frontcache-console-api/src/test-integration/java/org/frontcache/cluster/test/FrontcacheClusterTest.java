package org.frontcache.cluster.test;

import static org.junit.Assert.fail;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.client.FrontCacheCluster;
import org.frontcache.core.FCHeaders;
import org.frontcache.io.CachedKeysActionResponse;
import org.frontcache.io.GetFromCacheActionResponse;
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
	public void invalidateCHN() throws Exception {
		FrontCacheClient fcClient = new FrontCacheClient("https://or.coinshome.net:443/");
		fcClient.removeFromCacheAll();
	}
	
//	@Test
//	public void invalidate() throws Exception {
//		FrontCacheCluster fcCluster = new FrontCacheCluster("https://or.coinshome.net:443/", "https://sg.coinshome.net:443/", "https://origin.coinshome.net:443/");
//		fcCluster.removeFromCache("http://www.coinshome");
//		fcCluster.removeFromCache("https://www.coinshome");		
//	}
	
//	@Test
	public void exportURLs() throws Exception {
		FrontCacheClient fcClient = new FrontCacheClient("https://or.coinshome.net:443/");
		
		CachedKeysActionResponse cachedKeysActionResponse = fcClient.getCachedKeys();
		
		List<String> cachedKeys = cachedKeysActionResponse.getCachedKeys();
		
		String fileName = "/Users/spa/tmp1/_cached_keys_coinshome.net2.txt";
		OutputStream fos = new FileOutputStream(fileName);
		DataOutputStream dos = new DataOutputStream(fos);
		
		for (String key : cachedKeys)
		{
			String s = key.replace("http://origin.coinshome.net:80", "http://www.coinshome.net");
			s = s.replace("https://origin.coinshome.net:443", "https://www.coinshome.net");
			if (-1 < s.indexOf(";jsessionid="))
			{
//				System.out.println("before: " + s);
				s = s.substring(0, s.indexOf(";jsessionid="));
//				System.out.println("after: " + s);
				
			}
			
			dos.writeChars(s + "\n");
		}
		dos.flush();
		dos.close();

	}
	
//	@Test
	public void getDetaisByKey() throws Exception {
		FrontCacheClient fcClient = new FrontCacheClient("https://or.coinshome.net:443/");
		
		GetFromCacheActionResponse aResponse = fcClient.getFromCacheActionResponse("http://origin.coinshome.net:80/en/coin-x-y-z-ZhEKbzbiSToAAAFRlCNaux6I.htm");
		String content = new String(aResponse.getValue().getContent());
		System.out.println(content);
		
		System.out.println("----------------");
		aResponse = fcClient.getFromCacheActionResponse("http://origin.coinshome.net:80/fc/include-footer.htm?locale=en");
		content = new String(aResponse.getValue().getContent());
		System.out.println(content);
		
//		System.out.println("----------------");
//		fcClient.getCachedKeys()getFromCache("http://origin.coinshome.net:80/fc/external-ads.htm?locale=");
//		content = new String(aResponse.getValue().getContent());
//		System.out.println(content);

		System.out.println("----------------");
		aResponse = fcClient.getFromCacheActionResponse("http://origin.coinshome.net:80/fc/external-ads.htm?locale=");
		content = new String(aResponse.getValue().getContent());
		System.out.println(content);

	}
	
	
	/**
	 * test cache redistribution in cluster
	 * @throws Exception
	 */
//	@Test
	public void distributeCacheAcrossCluster() throws Exception {
		
		FrontCacheCluster fcCluster = new FrontCacheCluster("https://or.coinshome.net:443/", "https://sg.coinshome.net:443/", "https://origin.coinshome.net:443/");		
//		FrontCacheCluster fcCluster = new FrontCacheCluster("http://or.coinshome.net:80/", "http://sg.coinshome.net:80/", "https://origin.coinshome.net:443/");		
//		FrontCacheCluster fcCluster = new FrontCacheCluster("https://sg.coinshome.net:443/", "https://origin.coinshome.net:443/");		
//		FrontCacheCluster fcCluster = new FrontCacheCluster("https://origin.coinshome.net:443/", "http://sg.coinshome.net:80/");
		
		Map<FrontCacheClient, Long> updateCounterMap = fcCluster.reDistriburteCache();
//		logger.debug("Updates: ");
//		for (FrontCacheClient fcInstance : updateCounterMap.keySet())
//		{
//			logger.debug(fcInstance + " - " + updateCounterMap.get(fcInstance) + " updates posted");
//		}

		
		fail("stop");
	}
	
}

