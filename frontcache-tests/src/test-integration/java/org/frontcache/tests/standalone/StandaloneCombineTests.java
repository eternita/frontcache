package org.frontcache.tests.standalone;

import org.frontcache.tests.base.CombineTests;

public class StandaloneCombineTests extends CombineTests {

	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getStandaloneBaseURLLocalhost();
	}

}
