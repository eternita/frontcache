package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

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



	@Before
	public void setUp() throws Exception {
		super.setUp();

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURL(); 


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
	
}
