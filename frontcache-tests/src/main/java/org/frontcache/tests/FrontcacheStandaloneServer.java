/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
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
