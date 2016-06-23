package org.frontcache.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
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
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/hystrix/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("Hi from Hystrix", webResponse.getContentAsString().trim());
	}

	@Test
	public void timeoutFailTest() throws Exception {
		// page timeout 4000ms. Hystrix timeout - 3000ms
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/hystrix/b.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		// "Default Fallabck for http://localhost:9080/common/hystrix/b.jsp"
		// "Default Fallabck for http://localhost:8080/common/hystrix/b.jsp"
		// difference in port number for Filter VS Standalone (9080 VS 8080)
		String respStr = webResponse.getContentAsString();
		assertNotEquals(-1, respStr.indexOf("Default Fallback"));
		assertNotEquals(-1, respStr.indexOf("/common/hystrix/b.jsp"));
		
	}

	@Test
	public void timeoutInsideIncludeTest() throws Exception {
		// page timeout 4000ms. Hystrix timeout - 3000ms
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/hystrix/top1.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		// difference in port number for Filter VS Standalone (9080 VS 8080)
		String respStr = webResponse.getContentAsString();
		assertNotEquals(-1, respStr.indexOf("top")); // from main page
		assertNotEquals(-1, respStr.indexOf("inc11")); // successful include #1
		assertNotEquals(-1, respStr.indexOf("Default Fallback")); // default fallback for include #2
		assertNotEquals(-1, respStr.indexOf("/common/hystrix/inc12.jsp")); // default fallback for include #2
	}
	
	@Test
	public void customeFallbackTest1() throws Exception {
		// page timeout 4000ms. Hystrix timeout - 3000ms
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/hystrix/fallback1.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		assertEquals("Hi from custom fallback", webResponse.getContentAsString());
	}
}
