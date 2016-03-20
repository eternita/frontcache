package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class FrontCacheTest {

	public static final String BASE_URI = "http://localhost:9080/frontcache-web/";
	WebClient webClient = new WebClient();

	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}

//	@Test
//	public void staticRead() throws Exception {
//
//		TextPage page = webClient.getPage(BASE_URI + "1/a.txt");
//		String pageAsText = page.getContent();
//		assertEquals("a", pageAsText);
//	}
//
//	@Test
//	public void staticInclude() throws Exception {
//		
//		TextPage page = webClient.getPage(BASE_URI + "2i/a.txt");
//		String pageAsText = page.getContent();
//		assertEquals("ab", pageAsText);
//		System.out.println(pageAsText);
//
//	}

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
	public void jspIncludeAndCache() throws Exception {
		
		HtmlPage page = webClient.getPage(BASE_URI + "6ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

}
