package org.frontcache.core;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.frontcache.tests.CommonTests;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * 
 * start frontcache in embedded Jetty 
 * and run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneCommonTests extends CommonTests {

	private static final int FRONTFACHE_PORT = 9080;
	static Server server = null;
	
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
	
}
