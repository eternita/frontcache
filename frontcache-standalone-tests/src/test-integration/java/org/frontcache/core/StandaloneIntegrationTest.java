package org.frontcache.core;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;

public class StandaloneIntegrationTest {

	public static final String BASE_URI = "http://localhost:9080/";
	public static final int ORIGIN_APP_PORT = 8080;
	
	static Server server = null;
	WebClient webClient = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
        server = new Server(ORIGIN_APP_PORT);
        
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        String frontcacheStandaloneTestWebDir = System.getProperty("frontcache.standalone.test.web.dir");
//        File warFile = new File("/Users/spa/git/frontcache/frontcache-standalone-tests/src/test-integration/webapp");
        File warFile = new File(frontcacheStandaloneTestWebDir);
        webapp.setWar(warFile.getAbsolutePath());
        
        
        // This webapp will use jsps and jstl. We need to enable the
        // AnnotationConfiguration in order to correctly
        // set up the jsp container
        Configuration.ClassList classlist = Configuration.ClassList
                .setServerDefault( server );
        classlist.addBefore(
                "org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
                "org.eclipse.jetty.annotations.AnnotationConfiguration" );
 
        // Set the ContainerIncludeJarPattern so that jetty examines these
        // container-path jars for tlds, web-fragments etc.
        // If you omit the jar that contains the jstl .tlds, the jsp engine will
        // scan for them instead.
//        webapp.setAttribute(
//                "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern",
//                ".*/[^/]*servlet-api-[^/]*\\.jar$|.*/javax.servlet.jsp.jstl-.*\\.jar$|.*/[^/]*taglibs.*\\.jar$" );

        
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
		
		TextPage page = webClient.getPage(BASE_URI + "1/a.txt");
		assertEquals("a", page.getContent());
	}
	
	@Test
	public void test2() throws Exception {
		
		TextPage page = webClient.getPage(BASE_URI + "2/a.txt");
		assertEquals("ab", page.getContent());
	}
	

}

