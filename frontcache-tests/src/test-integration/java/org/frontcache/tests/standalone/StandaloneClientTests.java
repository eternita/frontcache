package org.frontcache.tests.standalone;

import org.frontcache.tests.base.ClientTests;

/**
 * 
 * run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneClientTests extends ClientTests {

	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURL();
	}
	
}
