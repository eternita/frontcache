package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 * 
 * Defined tests are run in filter & standalone modes
 *
 */
public class CommonTests {

	public static final String FRONTCACHE_TEST_BASE_URI = "http://localhost:9080/";
	
	protected WebClient webClient = null;

	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}

	@Test
	public void staticRead() throws Exception {

		TextPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/static-read/a.txt");
		String pageAsText = page.getContent();
		assertEquals("a", pageAsText);
	}

	@Test
	public void jsp() throws Exception {
		
		HtmlPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/jsp-read/a.jsp");
		assertEquals("Hi from JSP", page.getPage().asText());

	}

	@Test
	public void jspInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/jsp-include/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache1() throws Exception {
		
		HtmlPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/6ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache2() throws Exception {
		
		HtmlPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/7ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}
	
	@Test
	public void jspDeepInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/deep-include/a.jsp");
		assertEquals("abcdef", page.getPage().asText());

	}
	
	@Test
	public void redirect() throws Exception {
		
		HtmlPage page = webClient.getPage(FRONTCACHE_TEST_BASE_URI + "common/redirect/a.jsp");
		assertEquals("redirecred", page.getPage().asText());

	}
}
