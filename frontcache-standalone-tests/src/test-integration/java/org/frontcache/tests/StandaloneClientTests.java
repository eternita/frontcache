package org.frontcache.tests;

import org.eclipse.jetty.server.Server;
import org.junit.AfterClass;
import org.junit.BeforeClass;

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
		
        server = StandaloneUtils.startServerWithFrontcache();
        
        return;
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		server.stop();
	}
	
	// different port inside cache keys
	private static final String CACHE_KEY_FRONTCACHE_TEST_BASE_URI_FILTER = "http://localhost:8080/"; // cache key
	
	// different port inside cache keys
	public String getCacheKeyBaseURL()
	{
		return CACHE_KEY_FRONTCACHE_TEST_BASE_URI_FILTER;
	}
	
}
