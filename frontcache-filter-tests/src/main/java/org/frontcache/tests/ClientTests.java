package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.client.FrontCacheCluster;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class ClientTests extends CommonTestsBase {

	private static String FRONTCACHE_CLUSTER_NODE1 = TestConfig.FRONTCACHE_TEST_BASE_URI;
	
	private static String FRONTCACHE_CLUSTER_NODE2 = TestConfig.FRONTCACHE_TEST_BASE_URI;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	@Test
	public void getCacheStatusClient() throws Exception {
		
		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		String response = frontcacheClient.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("cache status"));
		logger.debug("response " + response);
	}

	@Test
	public void getCacheStatusCluster() throws Exception {
		
		FrontCacheCluster fcCluster = new FrontCacheCluster(FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2);
		
		String response = fcCluster.getCacheState().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("cache status"));
		logger.debug("response " + response);
	}
	
	@Test
	public void invalidationByFilterTestClient() throws Exception {
		
		final String TEST_URI = "common/cache-invalidation/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
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
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		// cache invalidation
		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		String response = frontcacheClient.removeFromCache(TEST_URI);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		return;
	}

	@Test
	public void invalidationByFilterTestCluster() throws Exception {
		
		FrontCacheCluster fcCluster = new FrontCacheCluster(FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2);
		final String TEST_URI = "common/cache-invalidation/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
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
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		// cache invalidation
		String response = fcCluster.removeFromCache(TEST_URI).get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		return;
	}

	@Test
	public void invalidationAllTestClient() throws Exception {
		
		final String TEST_URI_A = "common/cache-invalidation/a.jsp";
		final String TEST_URI_B = "common/cache-invalidation/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

		// the first request b - response should be cached
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_B);
		assertEquals("b", page.getPage().asText());		
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		
		response = frontcacheClient.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("\"cached entiries\":\"2\""));
		
		// cache invalidation
		response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		response = frontcacheClient.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("\"cached entiries\":\"0\""));
		return;
	}
	
	@Test
	public void invalidationAllTestCluster() throws Exception {
		
		final String TEST_URI_A = "common/cache-invalidation/a.jsp";
		final String TEST_URI_B = "common/cache-invalidation/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		FrontCacheCluster fcCluster = new FrontCacheCluster(FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2);
		
		// clean up
		String response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

		// the first request b - response should be cached
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_B);
		assertEquals("b", page.getPage().asText());		
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		
		response = fcCluster.getCacheState().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("\"cached entiries\":\"2\""));
		
		// cache invalidation
		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		response = fcCluster.getCacheState().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("\"cached entiries\":\"0\""));
		return;
	}
}
