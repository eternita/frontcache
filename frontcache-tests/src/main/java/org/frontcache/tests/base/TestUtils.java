package org.frontcache.tests.base;

public class TestUtils {

	public TestUtils() {
		// TODO Auto-generated constructor stub
	}

	// X-frontcache.debug.request.0: success toplevel from-cache 15 65793 "http://localhost:9080/en/coin_definition-1_Escudo-Gold-Centralist_Republic_of_Mexico_(1835_1846)-E9AKbzbiOBIAAAFG0vnZjkvL.htm" frontcache-localhost-1 browser
	public static boolean isRequestFromCache(String headerLogStr)
	{
		boolean cached = false;
		
		if (-1 < headerLogStr.indexOf("from-cache"))
			cached = true;
		
		return cached;
	}
	
}
