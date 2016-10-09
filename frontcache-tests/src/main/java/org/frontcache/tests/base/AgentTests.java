package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import org.frontcache.agent.FrontCacheAgent;
import org.frontcache.agent.FrontCacheAgentCluster;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 * 
 *
 */
public abstract class AgentTests extends TestsBase {

	private String FRONTCACHE_CLUSTER_NODE1 = getFrontCacheBaseURL();
	
	private String FRONTCACHE_CLUSTER_NODE2 = getFrontCacheBaseURL();
	
	protected FrontCacheAgent frontcacheClient = null;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
		frontcacheClient = new FrontCacheAgent(getFrontCacheBaseURL());
		frontcacheClient.removeFromCache("*"); // clean up		

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURL(); 


	
	@Test
	public void invalidationByFilterTestClient() throws Exception {
		
		final String TEST_URI = "common/fc-agent/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		String debugResponseTime = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME);
		Assert.assertNotNull(debugResponseTime);
		String debugResponseSize = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE);
		assertEquals("1", debugResponseSize);

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		// cache invalidation (both standalone and filter)
		String response = frontcacheClientStandalone.removeFromCache(TEST_URI); // clean up FC standalone		
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		response = frontcacheClientFilter.removeFromCache(TEST_URI); // clean up FC filter
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		return;
	}

	@Test
	public void invalidationByFilterTestCluster() throws Exception {
		
		FrontCacheAgentCluster fcCluster = new FrontCacheAgentCluster(FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2);
		final String TEST_URI = "common/fc-agent/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		String debugResponseTime = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME);
		Assert.assertNotNull(debugResponseTime);
		String debugResponseSize = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE);
		assertEquals("1", debugResponseSize);

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		// cache invalidation
		String response = fcCluster.removeFromCache(TEST_URI).get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		return;
	}

//	@Test
//	public void invalidationAllTestClient() throws Exception {
//		
//		final String TEST_URI_A = "common/fc-agent/a.jsp";
//		final String TEST_URI_B = "common/fc-agent/b.jsp";
//		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
//		frontcacheClient = new FrontCacheAgent(getFrontCacheBaseURL());
//		
//		// clean up
//		String response = frontcacheClient.removeFromCache("*");
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
//
//		// the first request a - response should be cached
//		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI_A);
//		assertEquals("a", page.getPage().asText());		
//		WebResponse webResponse = page.getWebResponse(); 
//		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
//		assertEquals("false", debugCached);
//
//		// the first request b - response should be cached
//		page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI_B);
//		assertEquals("b", page.getPage().asText());		
//		webResponse = page.getWebResponse(); 
//		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
//		assertEquals("false", debugCached);
//		
//		Map<String, String> cacheState = frontcacheClient.getCacheState();
//		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));
//
//		// cache invalidation
//		response = frontcacheClient.removeFromCacheAll();
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
//		
//		cacheState = frontcacheClient.getCacheState();
//		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));
//		return;
//	}
	
//	@Test
//	public void invalidationAllTestCluster() throws Exception {
//		
//		final String TEST_URI_A = "common/fc-agent/a.jsp";
//		final String TEST_URI_B = "common/fc-agent/b.jsp";
//		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
//		FrontCacheCluster fcCluster = new FrontCacheCluster(FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2);
//		
//		// clean up
//		String response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
//
//		// the first request a - response should be cached
//		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI_A);
//		assertEquals("a", page.getPage().asText());		
//		WebResponse webResponse = page.getWebResponse(); 
//		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
//		assertEquals("false", debugCached);
//
//		// the first request b - response should be cached
//		page = webClient.getPage(getFrontCacheBaseURL() + TEST_URI_B);
//		assertEquals("b", page.getPage().asText());		
//		webResponse = page.getWebResponse(); 
//		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
//		assertEquals("false", debugCached);
//		
//		Map<String, String> cacheState = frontcacheClient.getCacheState();
//		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));
//		
//		// cache invalidation
//		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
//		
//		cacheState = frontcacheClient.getCacheState();
//		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));
//		
//		return;
//	}
		
	
}
