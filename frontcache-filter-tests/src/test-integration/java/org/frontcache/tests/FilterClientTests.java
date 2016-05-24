package org.frontcache.tests;

public class FilterClientTests extends ClientTests {

	// different port inside cache keys
	private static final String CACHE_KEY_FRONTCACHE_TEST_BASE_URI_FILTER = "http://localhost:9080/"; // cache key
	
	// different port inside cache keys
	public String getCacheKeyBaseURL()
	{
		return CACHE_KEY_FRONTCACHE_TEST_BASE_URI_FILTER;
	}
	
}
