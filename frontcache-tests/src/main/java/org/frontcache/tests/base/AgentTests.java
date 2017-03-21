/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

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

	private String FRONTCACHE_CLUSTER_NODE1 = getFrontCacheBaseURLDomainFC1();
	
	private String FRONTCACHE_CLUSTER_NODE2 = getFrontCacheBaseURLDomainFC1();
	
	protected FrontCacheAgent frontcacheClient = null;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
		frontcacheClient = new FrontCacheAgent(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		frontcacheClient.removeFromCache("*"); // clean up		

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURLDomainFC1(); 


	
	@Test
	public void invalidationByFilterTestClient() throws Exception {
		
		final String TEST_URI = "common/fc-agent/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		
		// cache invalidation (both standalone and filter)
		String response = frontcacheClientStandalone.removeFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI); // clean up FC standalone		
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		response = frontcacheClientFilter.removeFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI); // clean up FC filter
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		return;
	}

	@Test
	public void invalidationByFilterTestCluster() throws Exception {
		
		
		FrontCacheAgentCluster fcCluster = new FrontCacheAgentCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), SiteKeys.TEST_SITE_KEY_1);
		final String TEST_URI = "common/fc-agent/a.jsp";
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		
		// cache invalidation
		String response = fcCluster.removeFromCache(getFrontCacheBaseURLDomainFC1() + TEST_URI).get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		
		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + TEST_URI);
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
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
