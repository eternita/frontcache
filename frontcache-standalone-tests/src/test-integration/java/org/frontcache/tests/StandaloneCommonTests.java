package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.eclipse.jetty.server.Server;
import org.frontcache.core.FCHeaders;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebResponse;

/**
 * 
 * start frontcache in embedded Jetty 
 * and run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneCommonTests extends CommonTests {

	static Server server = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
        server = StandaloneUtils.startServerWithFrontcache();
        
        return;
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		server.stop();
	}
	
	@Test
	public void frontcacheIdTest() throws Exception {
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/fc-headers/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		String frontcacheId = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_ID);

		assertEquals("localhost-fc-standalone", frontcacheId);
	}
	
}
