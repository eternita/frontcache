package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.html.HtmlPage;

public abstract class IncludeTests extends TestsBase {

	public abstract String getFrontCacheBaseURL(); 
	
	protected FrontCacheClient frontcacheClient = null;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURL());
		frontcacheClient.removeFromCacheAll(); // clean up		

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	
	@Test
	public void includeAsync1() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/deep-include-async/a1.jsp");
		assertEquals("a", page.getPage().asText()); // no 'ef' in response because 'e' included in async mode
	}
	
	
	@Test
	public void includeAsync2() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/deep-include-async/a.jsp");
		assertEquals("abcd", page.getPage().asText()); // no 'ef' in response because 'e' included in async mode

	}

	
	@Test
	public void includeAsync3() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURL() + "common/deep-include-async/a.jsp");
		assertEquals("abcd", page.getPage().asText()); // no 'ef' in response because 'e' included in async mode

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache( getFrontCacheBaseURL() + "common/deep-include-async/a.jsp");
		assertEquals("a<fc:include url=\"/common/deep-include-async/b.jsp\" />", new String(resp.getContent()));	

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURL() + "common/deep-include-async/b.jsp");
		assertEquals("b<fc:include url=\"/common/deep-include-async/c.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURL() + "common/deep-include-async/c.jsp");
		assertEquals("c<fc:include url=\"/common/deep-include-async/d.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURL() + "common/deep-include-async/d.jsp");
		assertEquals("d<fc:include url=\"/common/deep-include-async/e.jsp\" type=\"async\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURL() + "common/deep-include-async/e.jsp");
		assertEquals("e<fc:include url=\"/common/deep-include-async/f.jsp\" />", new String(resp.getContent()));	

		// !!! includes inside async includes are not called
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURL() + "common/deep-include-async/f.jsp");
		assertEquals(null, resp);	
	}
	
}
