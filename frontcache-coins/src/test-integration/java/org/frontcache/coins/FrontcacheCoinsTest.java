package org.frontcache.coins;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class FrontcacheCoinsTest {

	public static final String FRONTCACHE_TEST_BASE_URI = "http://sg.coinshome.net:80/";
	
	protected Logger logger = LoggerFactory.getLogger(FrontcacheCoinsTest.class);  
	
	WebClient webClient = null;
	
	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}
	

	
	/**
	 * test if front cache is on for welcome page in Singapure
	 * @throws Exception
	 */
	@Test
	public void testWelcomePageCacheSG() throws Exception {
		
		HtmlPage page = webClient.getPage("http://sg.coinshome.net/en/welcome.htm");
		page = webClient.getPage("http://sg.coinshome.net/en/welcome.htm"); // request 2 time to make sure request is cached
		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		printHeaders(webResponse);
	}
	
	/**
	 * test if front cache is on for welcome page in Oregon
	 * @throws Exception
	 */
	@Test
	public void testWelcomePageCacheOR() throws Exception {
		
		HtmlPage page = webClient.getPage("http://or.coinshome.net/en/welcome.htm");
		page = webClient.getPage("http://or.coinshome.net/en/welcome.htm"); // request 2 time to make sure request is cached
		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		printHeaders(webResponse);
	}
	
	protected void printHeaders(WebResponse webResponse)
	{
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		String debugResponseTime = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME);
		String debugResponseSize = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE);
		
		String logStr = "cacheable: " + debugCacheable + ", cached:" + debugCached + ", responseTime: " + debugResponseTime + ", responseSize: " + debugResponseSize;
		logger.info(logStr);
		return;
	}
	
}

