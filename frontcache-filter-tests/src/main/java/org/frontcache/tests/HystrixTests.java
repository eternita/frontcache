package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class HystrixTests extends CommonTestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();

	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	@Test
	public void customTimeoutTest() throws Exception {
		// page timeout 1500ms. Hystrix timeout - 3000ms, what is more than default 1000ms
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/hystrix/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("Hi from Hystrix", page.getPage().asText());
	}

	@Test
	public void timeoutFailTest() throws Exception {
		// page timeout 4000ms. Hystrix timeout - 3000ms
		TextPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/hystrix/b.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("FC - ORIGIN ERROR - http://localhost:9080/common/hystrix/b.jsp", page.getContent());
	}
}
