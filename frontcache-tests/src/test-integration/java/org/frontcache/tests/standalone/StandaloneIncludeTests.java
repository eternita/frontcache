package org.frontcache.tests.standalone;

import org.frontcache.tests.base.IncludeTests;

public class StandaloneIncludeTests extends IncludeTests {

	
	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURLLocalhost();
	}
	
}
