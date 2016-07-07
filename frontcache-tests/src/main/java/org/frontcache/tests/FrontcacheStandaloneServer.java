package org.frontcache.tests;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

public class FrontcacheStandaloneServer {

	public static void main(String[] args) {
		
		System.out.println("Starting Frontcache Standalone Server ...");

		int port = Integer.parseInt(System.getProperty("frontcache.standalone.frontcache.port"));
		Server server = new Server(port);
        
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        String frontcacheStandaloneTestWebDir = System.getProperty("frontcache.standalone.frontcache.web.dir");
        File warFile = new File(frontcacheStandaloneTestWebDir);
        webapp.setWar(warFile.getAbsolutePath());
 
        server.setHandler(webapp);
        try {
			server.start();
			
			System.out.println("Frontcache Standalone Server has been started successfully ...");
			System.out.println("StandaloneFrontcache started");
		} catch (Exception e1) {
			e1.printStackTrace();
		}		        

        return;
	}

}
