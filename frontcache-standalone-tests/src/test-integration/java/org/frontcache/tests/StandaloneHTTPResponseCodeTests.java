package org.frontcache.tests;

import org.eclipse.jetty.server.Server;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class StandaloneHTTPResponseCodeTests extends HTTPResponseCodeTests {

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

}
