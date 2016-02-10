package org.frontcache.core;

import static org.junit.Assert.assertNotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class BasicIntegrationTest {

	public static final String BASE_URI = "http://localhost:9080";
	WebClient webClient = new WebClient();

	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}
	
	@Test
	public void test() throws Exception {

		HtmlPage page = webClient.getPage(BASE_URI + "/search.htm");
		assertNotNull(page.getPage().asText());
		
	}

}
