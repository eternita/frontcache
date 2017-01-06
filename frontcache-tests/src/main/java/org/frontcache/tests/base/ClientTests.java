package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.client.FrontCacheCluster;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.NameValuePair;

/**
 * 
 *
 */
public abstract class ClientTests extends TestsBase {

	private String FRONTCACHE_CLUSTER_NODE1 = getFrontCacheBaseURLDomainFC1();
	
	private String FRONTCACHE_CLUSTER_NODE2 = getFrontCacheBaseURLDomainFC1();
	
	protected FrontCacheClient frontcacheClient = null;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1), 
						new FrontCacheClient(getFrontCacheBaseURLDomainFC2(), SiteKeys.TEST_SITE_KEY_2)}
			));
	
		// clean up
		fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
	
	
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURLDomainFC1(); 

	public abstract String getFrontCacheBaseURLDomainFC2(); 
	
	@Test
	public void getFromCacheClient() throws Exception {
		
		final String TEST_URI_A = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI_A);

		assertEquals("a", new String(resp.getContent()));	
		return;
	}

	@Test
	public void deepInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/a.jsp");
		assertEquals("abcdef", page.getPage().asText());

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/a.jsp");
		assertEquals("a<fc:include url=\"/common/deep-include-cache/b.jsp\" />", new String(resp.getContent()));	

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/b.jsp");
		assertEquals("b<fc:include url=\"/common/deep-include-cache/c.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/c.jsp");
		assertEquals("c<fc:include url=\"/common/deep-include-cache/d.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/d.jsp");
		assertEquals("d<fc:include url=\"/common/deep-include-cache/e.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/e.jsp");
		assertEquals("e<fc:include url=\"/common/deep-include-cache/f.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/f.jsp");
		assertEquals("f", new String(resp.getContent()));	
		
	}
	
	@Test
	public void getFromCacheClientNull() throws Exception {
		
		final String TEST_URI_A = "common/fc-client/a.jsp";

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCacheActionResponse(getFrontCacheBaseURLDomainFC1() + TEST_URI_A).getValue();

		assertNull(resp);	
		return;
	}

	
	@Test
	public void getCacheStatusClient() throws Exception {
		
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		
		Map<String, String> response = frontcacheClient.getCacheState();
		Assert.assertNotNull(response);
		logger.debug("response " + response);
	}

	@Test
	public void getCacheStatusCluster() throws Exception {
		
		FrontCacheClient frontCacheClient1 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE1, SiteKeys.TEST_SITE_KEY_1);
		FrontCacheClient frontCacheClient2 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE2, SiteKeys.TEST_SITE_KEY_1);
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(new FrontCacheClient[]{frontCacheClient1, frontCacheClient2}));
		
		Map<String, String> response = fcCluster.getCacheState().get(frontCacheClient1);
		Assert.assertNotNull(response);
		logger.debug("response " + response);
	}

//	@Test
//	public void getCachedKeysClient() throws Exception {
//		
//		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURL());
//		
//		CachedKeysActionResponse response = frontcacheClient.getCachedKeys();
//		Assert.assertNotNull(response);
//		Assert.assertEquals("cached keys", response.getAction());
//		logger.debug("response " + response);
//	}
//
//	@Test
//	public void getCachedKeysCluster() throws Exception {
//		
//		FrontCacheClient frontCacheClient1 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE1);
//		FrontCacheClient frontCacheClient2 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE2);
//		FrontCacheCluster fcCluster = new FrontCacheCluster(frontCacheClient1, frontCacheClient2);
//		
//		CachedKeysActionResponse response = fcCluster.getCachedKeys().get(frontCacheClient1);
//		Assert.assertNotNull(response);
//		Assert.assertEquals("cached keys", response.getAction());
//		logger.debug("response " + response);
//	}
	
	@Test
	public void invalidationByFilterTestClient() throws Exception {
		
		final String TEST_URI = "common/fc-client/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
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
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		// cache invalidation (both standalone and filter)
		String response = frontcacheClientStandalone.removeFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI); // clean up FC standalone		
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		response = frontcacheClientFilter.removeFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI); // clean up FC filter
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		return;
	}

	@Test
	public void invalidationByFilterTestCluster() throws Exception {
		
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), SiteKeys.TEST_SITE_KEY_1);
		final String TEST_URI = "common/fc-client/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
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
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		// cache invalidation
		String response = fcCluster.removeFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI).get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		return;
	}

	@Test
	public void invalidationAllTestClient() throws Exception {
		
		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1), 
						new FrontCacheClient(getFrontCacheBaseURLDomainFC2(), SiteKeys.TEST_SITE_KEY_2)}
			));
		
		fcCluster.removeFromCacheAll(); // clean up		

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_B);
		assertEquals("b", page.getPage().asText());		
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		
		Map<String, String> cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		// cache invalidation
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		return;
	}
	
	@Test
	public void invalidationAllTestCluster() throws Exception {
		
		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
					new FrontCacheClient[]{
							new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1), 
							new FrontCacheClient(getFrontCacheBaseURLDomainFC2(), SiteKeys.TEST_SITE_KEY_2)}
				));
		
		// clean up
		String response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
//		fcCluster = new FrontCacheCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), SiteKeys.TEST_SITE_KEY_2);
//		
//		// clean up
//		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_B);
		assertEquals("b", page.getPage().asText());		
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		
		Map<String, String> cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		
		// cache invalidation
		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		
		return;
	}
	
	@Test
	public void invalidationAllMultidomainTestCluster() throws Exception {
		
		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1), 
						new FrontCacheClient(getFrontCacheBaseURLDomainFC2(), SiteKeys.TEST_SITE_KEY_2)}
			));
		
		// clean up
		String response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		
		WebResponse webResponse = page.getWebResponse(); 
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI_B);
		assertEquals("b", page.getPage().asText());		
		webResponse = page.getWebResponse(); 
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		
		FrontCacheClient frontcacheClient1 = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		Map<String, String> cacheState = frontcacheClient1.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		

		// same pages with fc2.test.org instead of fc1.test.org
		// the first request a - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLDomainFC2() + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLDomainFC2() + TEST_URI_B);
		assertEquals("b", page.getPage().asText());		
		
		cacheState = frontcacheClient1.getCacheState();
		Assert.assertEquals("4", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		
		
		// cache invalidation - remove all for fc1.test.org
		response = frontcacheClient1.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		cacheState = frontcacheClient1.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES)); // pages from fc2.test.org remain in cache
		
		FrontCacheClient frontcacheClient2 = new FrontCacheClient(getFrontCacheBaseURLDomainFC2(), SiteKeys.TEST_SITE_KEY_2);
		// fc2 cache invalidation - remove all for fc2.test.org
		response = frontcacheClient2.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		cacheState = frontcacheClient1.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES)); // cache is empty
		
		return;
	}
	
	@Test
	public void httpHeadersDuplication() throws Exception {
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/deep-include-cache/e.jsp");
		assertEquals("ef", page.getPage().asText());
		
		List<NameValuePair> headers = page.getWebResponse().getResponseHeaders();
		for (NameValuePair nv : headers)
			System.out.println("HTTP HEADER " + nv.getName() + " -- " + nv.getValue());
		
		Set<String> names = new HashSet<String>();
		for (NameValuePair nv : headers)
		{
			String name = nv.getName();
			if (names.contains(name))
				fail("HTTP header duplicate name - " + name);
			
			names.add(name);
		}
		return;
	}
	
	
}
