package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class FrontCacheFilterTest {

	public static final String BASE_URI = "http://localhost:9080/";
	
	private WebClient webClient = null;

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

		TextPage page = webClient.getPage(BASE_URI + "1/a.txt");
		String pageAsText = page.getContent();
		assertEquals("a", pageAsText);
	}

	@Test
	public void jsp() throws Exception {
		
		HtmlPage page = webClient.getPage(BASE_URI + "3/a.jsp");
		assertEquals("Hi from JSP", page.getPage().asText());

	}

	@Test
	public void jspInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(BASE_URI + "4i/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache1() throws Exception {
		
		HtmlPage page = webClient.getPage(BASE_URI + "6ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache2() throws Exception {
		
		HtmlPage page = webClient.getPage(BASE_URI + "7ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}
}
