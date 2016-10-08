package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 * 
 * Defined tests are run in filter & standalone modes
 *
 */
public abstract class InvalidationTagsTests extends TestsBase {



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
	public void staticInvalidationTags() throws Exception {
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/invalidation-tags/a.jsp");
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 

		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("-1", maxage);

		String tags = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS);
		assertEquals("apple|banana|orange", tags);
		
	}

	@Test
	public void dynamicInvalidationTags() throws Exception {
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/invalidation-tags/b.jsp");
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 

		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("-1", maxage);

		String tags = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS);
		assertEquals("apple|banana|orange", tags);
		
	}
	
}
