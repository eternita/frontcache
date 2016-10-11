package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebResponse;

public abstract class FallbackHystrixTests extends TestsBase {

	public abstract String getFrontCacheBaseURL(); 
	
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
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/hystrix/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		assertEquals("Hi from Hystrix", webResponse.getContentAsString().trim());
	}

	@Test
	public void timeoutFailTest() throws Exception {
		// page timeout more than Hystrix timeout
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/hystrix/b.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		// "Default Fallabck for http://localhost:9080/common/hystrix/b.jsp"
		// "Default Fallabck for http://localhost:8080/common/hystrix/b.jsp"
		// difference in port number for Filter VS Standalone (8080 VS 9080)
		String respStr = webResponse.getContentAsString();
		assertNotEquals(-1, respStr.indexOf("Default Fallback"));
		assertNotEquals(-1, respStr.indexOf("/common/hystrix/b.jsp"));
		
	}

	@Test
	public void timeoutInsideIncludeTest() throws Exception {
		// page timeout more than Hystrix timeout
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/hystrix/top1.jsp");
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
		// page timeout more than Hystrix timeout
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/hystrix/fallback1.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		assertEquals("Hi from custom fallback", webResponse.getContentAsString());
	}
	
	@Test
	public void customeFallbackTest2LoadFromURL() throws Exception {
		// cleanup is in @BeforeClass
		// wait while server is started and fallbacks are loaded from URLs (they are loaded in separate thread during startup)
		// so, it's possible state (server is started but fallback configs are not loaded yet)
		
		// this wait time should be more than 
		// before
		// FallbackResolverFactory.init(httpClient);
		// in FrontCacheEngine
		
		Thread.sleep(5000); 

		// page timeout more than Hystrix timeout
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/hystrix/fallback2.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		assertEquals("Hi from custom fallback (loaded from URL)", webResponse.getContentAsString());
	}
	
	@Test
	public void customeFallbackTest3URLPattern() throws Exception {
		// page timeout more than Hystrix timeout
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/hystrix/fallback3-pattern.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		assertEquals("Hi from custom fallback", webResponse.getContentAsString());
	}
	
}
