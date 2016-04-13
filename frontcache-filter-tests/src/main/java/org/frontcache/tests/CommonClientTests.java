package org.frontcache.tests;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import static org.junit.Assert.assertEquals;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;

public class CommonClientTests {

	protected WebClient webClient = null;
	
	protected Logger logger = LoggerFactory.getLogger(CommonClientTests.class);  

	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}

	@Test
	public void getCacheStatus() throws Exception {
		
		FrontCacheClient fcc = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		String response = fcc.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("cache status"));
		logger.debug("response " + response);
	}

	@Test
	public void invalidationByFilterTest() throws Exception {
		
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
		FrontCacheClient fcc = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		String response = fcc.removeFromCache(TEST_URI);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

	}

	@Test
	public void invalidationAllTest() throws Exception {
		
		final String TEST_URI_A = "common/cache-invalidation/a.jsp";
		final String TEST_URI_B = "common/cache-invalidation/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		FrontCacheClient fcc = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = fcc.removeFromCacheAll();
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
		
		response = fcc.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("\"cached entiries\":\"2\""));
		
		// cache invalidation
		response = fcc.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		response = fcc.getCacheState();
		Assert.assertNotEquals(-1, response.indexOf("\"cached entiries\":\"0\""));

	}
	
}
