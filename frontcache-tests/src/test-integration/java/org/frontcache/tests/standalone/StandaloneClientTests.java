package org.frontcache.tests.standalone;

import org.frontcache.tests.base.ClientTests;

/**
 * 
 * run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneClientTests extends ClientTests {

	
	// different port inside cache keys
	private static final String CACHE_KEY_FRONTCACHE_TEST_BASE_URI_FILTER = "http://localhost:8080/"; // cache key
	
	// different port inside cache keys
	public String getCacheKeyBaseURL()
	{
		return CACHE_KEY_FRONTCACHE_TEST_BASE_URI_FILTER;
	}

	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURL();
	}
	
}
