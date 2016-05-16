package org.frontcache.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.io.PutToCacheActionResponse;
import org.frontcache.tests.ClientTests;
import org.frontcache.tests.TestConfig;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 * 
 * start frontcache in embedded Jetty 
 * and run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneClientTests extends ClientTests {

	static Server server = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
        server = new Server(TestConfig.FRONTFACHE_PORT);
        
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        String frontcacheStandaloneTestWebDir = System.getProperty("frontcache.standalone.frontcache.web.dir");
        File warFile = new File(frontcacheStandaloneTestWebDir);
        webapp.setWar(warFile.getAbsolutePath());
 
        server.setHandler(webapp);
        server.start();		        
        
        return;
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		server.stop();
	}
	
	// different port inside cache keys	
	@Test
	public void getFromCacheClient() throws Exception {
		
		final String TEST_URI_A = "common/fc-agent/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache("http://localhost:8080/" + TEST_URI_A).getValue();

		assertEquals("a", new String(resp.getContent()));	
		return;
	}

	@Test
	public void getFromCacheClientNull() throws Exception {
		
		final String TEST_URI_A = "common/fc-agent/a.jsp";

		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache("http://localhost:8080/" + TEST_URI_A).getValue();

		assertNull(resp);	
		return;
	}
	
	@Test
	public void putToCacheClient() throws Exception {
		
		final String TEST_URI_A = "common/fc-agent/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache("http://localhost:8080/" + TEST_URI_A).getValue();

		assertEquals("a", new String(resp.getContent()));
		
		resp.setContent("b".getBytes());
		
		PutToCacheActionResponse actionResponse = frontcacheClient.putToCache(resp);
		
		assertNotNull(actionResponse);
		
		resp = frontcacheClient.getFromCache("http://localhost:8080/" + TEST_URI_A).getValue();

		assertEquals("b", new String(resp.getContent()));
		
		return;
	}
	
	
}
