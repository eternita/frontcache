package org.frontcache.tests.standalone;

import org.frontcache.tests.base.InvalidationTagsTests;

public class StandaloneInvalidationTagsTests extends InvalidationTagsTests {

	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURL();
	}

}
