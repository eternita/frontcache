package org.frontcache.tests.standalone;

import org.frontcache.tests.base.StaticReadTests;

/**
 * 
 * run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneStaticReadTests extends StaticReadTests {

	
	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURLLocalhost();
	}
	
}
