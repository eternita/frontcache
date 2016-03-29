package org.frontcache.core;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class StandaloneIntegrationTest {

	public static final String TEST_BASE_URI = "http://localhost:9080/";
	public static final int ORIGIN_APP_PORT = 9080;
	
	static Server server = null;
	WebClient webClient = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
        server = new Server(ORIGIN_APP_PORT);
        
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
	public void test1() throws Exception {
		
		TextPage page = webClient.getPage(TEST_BASE_URI + "1/a.txt");
		assertEquals("a", page.getContent());
	}
	
	@Test
	public void test2() throws Exception {
		
		TextPage page = webClient.getPage(TEST_BASE_URI + "2/a.txt");
		assertEquals("ab", page.getContent());
	}
	
	@Test
	public void jspInclude() throws Exception {
		
		HtmlPage page = webClient.getPage(TEST_BASE_URI + "4i/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}
	
	@Test
	public void jspIncludeAndCache1() throws Exception {
		
		HtmlPage page = webClient.getPage(TEST_BASE_URI + "6ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}

	@Test
	public void jspIncludeAndCache2() throws Exception {
		
		HtmlPage page = webClient.getPage(TEST_BASE_URI + "7ci/a.jsp");
		assertEquals("ab", page.getPage().asText());

	}
	

}

