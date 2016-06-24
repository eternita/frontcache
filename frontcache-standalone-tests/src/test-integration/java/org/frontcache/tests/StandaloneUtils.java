package org.frontcache.tests;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

public class StandaloneUtils {

	public static Server startServerWithFrontcache() throws Exception {
		
		Server server = new Server(TestConfig.FRONTFACHE_PORT);
        
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        String frontcacheStandaloneTestWebDir = System.getProperty("frontcache.standalone.frontcache.web.dir");
        File warFile = new File(frontcacheStandaloneTestWebDir);
        webapp.setWar(warFile.getAbsolutePath());
 
        server.setHandler(webapp);
        server.start();		        
        
        return server;
	}

}
