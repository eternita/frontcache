package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class BasicIntegrationTest {

//	public static final String BASE_URI = "http://myfc.coinshome.net:9080/fc-test/";
	public static final String BASE_URI = "http://myfc.coinshome.net:9080/";
	
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

		System.out.println("Hi Mister from Integration test");
	}

//	@Test
//	public void staticRead() throws Exception {
//		TextPage page = webClient.getPage(BASE_URI + "1/a.txt");
//		String pageAsText = page.getContent();
//		assertEquals("a", pageAsText);
//	}

//	@Test
//	public void staticInclude() throws Exception {
//		
//		TextPage page = webClient.getPage(BASE_URI + "2i/a.txt");
//		String pageAsText = page.getContent();
//		assertEquals("ab", pageAsText);
//		System.out.println(pageAsText);
//
//	}

//	@Test
//	public void jsp() throws Exception {
//		
//		HtmlPage page = webClient.getPage(BASE_URI + "3/a.jsp");
//		assertEquals("Hi from JSP", page.getPage().asText());
//		
//		System.out.println("hi from tests");
//	}


	@Test
	public void jspInclude() throws Exception {
		
//		Thread.sleep(50000);
		
//		HtmlPage page = webClient.getPage(BASE_URI + "4i/a.jsp");
//		assertEquals("ab", page.getPage().asText());

		String url = BASE_URI + "en/coin-x-y-z-BQB_AAEBDT0AAAElluVzfAP..htm"; // "http://www.google.com/search?q=httpClient";

		HttpClient client = HttpClientBuilder.create().build();
		HttpGet request = new HttpGet(url);

		// add request header
//		request.addHeader("User-Agent", USER_AGENT);
		HttpResponse response = client.execute(request);

		System.out.println("Response Code : " 
	                + response.getStatusLine().getStatusCode());

		BufferedReader rd = new BufferedReader(
			new InputStreamReader(response.getEntity().getContent()));

		StringBuffer result = new StringBuffer();
		String line = "";
		while ((line = rd.readLine()) != null) {
			result.append(line);
		}		
		
		System.out.println(result.toString());
	}
	
}
