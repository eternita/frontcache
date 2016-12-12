package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 * 
 * Defined tests are run in filter & standalone modes
 *
 */
public abstract class CommonTests extends TestsBase {

	public abstract String getFrontCacheBaseURL(); 
	
	protected FrontCacheClient frontcacheClient = null;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURL());
		frontcacheClient.removeFromCacheAll(); // clean up		

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	

	@Test
	public void jsp() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/jsp-read/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("Hi from JSP", page.getPage().asText());

	}

	@Test
	public void jspInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/jsp-include/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache1() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/6ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache2() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/7ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}
	
	@Test
	public void jspDeepInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/deep-include/a.jsp");
		assertEquals("abcdef", page.getPage().asText());

	}
	
	@Test
	public void redirect() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/redirect/a.jsp");
		assertEquals("redirecred", page.getPage().asText());

	}

	/**
	 * check if debug mode available
	 * 
	 * @throws Exception
	 */
	@Test
	public void debugMode() throws Exception {
		
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		
		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/debug/a.jsp");
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
		page = webClient.getPage(getFrontCacheBaseURL() + "common/debug/a.jsp");
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 

		debugCacheable = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE);
		assertEquals("true", debugCacheable);
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		debugResponseTime = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME);
		Assert.assertNotNull(debugResponseTime);
		debugResponseSize = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE);
		assertEquals("1", debugResponseSize);
		
	}

	@Test
	public void httpHeadersMode() throws Exception {
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/fc-headers/a.jsp");
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 

		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("-1", maxage);
		
	}
	
	/**
	 * for single page maxage="bot:60"
	 * 
	 * call with User-Agent GoogleBot -> dynamic
	 * call with User-Agent GoogleBot -> cached
	 * 
	 * call with User-Agent chrome -> dynamic
	 * call with User-Agent chrome -> dynamic
	 * 
	 * @throws Exception
	 */
	@Test
	public void clientTypeSpecificCache() throws Exception {
		// single page maxage="bot:60"
		
		webClient.addRequestHeader("User-Agent", "Googlebot");
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// call with User-Agent GoogleBot -> dynamic
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/a.jsp");
		assertEquals("a", page.getPage().asText());
		WebResponse webResponse = page.getWebResponse(); 
		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		String debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);

		// call with User-Agent GoogleBot -> cached
		page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/a.jsp");
		assertEquals("a", page.getPage().asText());
		webResponse = page.getWebResponse(); 
		maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
		
		webClient.addRequestHeader("User-Agent", "Chrome");
		
		// multiple times
		// call with User-Agent chrome -> dynamic
		for (int i = 0; i<3; i++)
		{
			page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/a.jsp");
			assertEquals("a", page.getPage().asText());
			webResponse = page.getWebResponse(); 
			maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
			assertEquals("bot:60", maxage);
			debugCached = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
			assertEquals("false", debugCached);
		}
		
		return;
	}
	
	/**
	 * 
	 * call with User-Agent Chrome -> include is in cache
	 * call with User-Agent Chrome to include -> it's dynamic
	 * 
	 * call with User-Agent GoogleBot -> include is in cache
	 * call with User-Agent GoogleBot to include -> it's from cache
	 * 
	 * @throws Exception
	 */
	@Test
	public void clientTypeSpecificIncludes() throws Exception {
		
		webClient.addRequestHeader("User-Agent", "Chrome");
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");

		// call with User-Agent Chrome -> include is in cache
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/a1.jsp");
		assertEquals("ab", page.getPage().asText());
		org.frontcache.core.WebResponse webResponse = frontcacheClient.getFromCache( getFrontCacheBaseURL() + "common/client-bot-browser/b1.jsp");
		// b1.jsp has "bot:60" 
		// request was from browser -> so, page is not cached
		assertEquals(null, webResponse);	

		// call with User-Agent Chrome to include -> it's dynamic
		page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/b1.jsp");
		assertEquals("b", page.getPage().asText());
		com.gargoylesoftware.htmlunit.WebResponse pageWebResponse = page.getWebResponse(); 
		String maxage = pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		String debugCached = pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("false", debugCached);
		
		
		webClient.addRequestHeader("User-Agent", "Googlebot");
		
		// call with User-Agent GoogleBot -> include is in cache
		page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/a1.jsp");
		assertEquals("ab", page.getPage().asText());
		webResponse = frontcacheClient.getFromCache( getFrontCacheBaseURL() + "common/client-bot-browser/b1.jsp");
		assertEquals("b", new String(webResponse.getContent()));
		maxage = webResponse.getHeader(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		
		// call with User-Agent GoogleBot to include -> it's from cache
		page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/b1.jsp");
		assertEquals("b", page.getPage().asText());
		pageWebResponse = page.getWebResponse(); 
		maxage = pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		debugCached = pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_DEBUG_CACHED);
		assertEquals("true", debugCached);
	}
	
	
	@Test
	public void includeSync() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/client-bot-browser/a2.jsp");
		assertEquals("ab", page.getPage().asText());

		//  b2 has maxage="0" -> should not be cached
		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache( getFrontCacheBaseURL() + "common/client-bot-browser/b2.jsp");
		assertEquals(null, resp);	
		
	}
	
	
}
