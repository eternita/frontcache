package org.frontcache.core;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.frontcache.tests.CommonTests;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;

public class StandaloneSpecificTest {

	private static final int FRONTFACHE_PORT = 9080;
	
	static Server server = null;
	WebClient webClient = null;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
        server = new Server(FRONTFACHE_PORT);
        
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
	public void test2() throws Exception {
		
		TextPage page = webClient.getPage(CommonTests.FRONTCACHE_TEST_BASE_URI + "standalone/2/a.txt");
		assertEquals("ab", page.getContent());
	}
	
}

