package org.frontcache.core;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class BasicIntegrationTest {

//	public static final String BASE_URI = "http://myfc.coinshome.net:9080/";
	public static final String BASE_URI = "http://localhost:9080/";
	
	WebClient webClient = new WebClient();

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
	public void jspInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(BASE_URI + "en/coin-x-y-z-AuEKX9ISkuwAAAFT8ntrNpK2.htm");

		Assert.assertNotNull(page.getPage().asText());

	}
//*/	
}
