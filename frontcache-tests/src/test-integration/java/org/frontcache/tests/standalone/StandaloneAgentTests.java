package org.frontcache.tests.standalone;

import org.frontcache.tests.base.AgentTests;

/**
 * 
 * run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneAgentTests extends AgentTests {

	
	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURLLocalhost();
	}
	
}
