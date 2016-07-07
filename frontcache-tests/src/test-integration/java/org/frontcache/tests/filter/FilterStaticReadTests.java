package org.frontcache.tests.filter;

import org.frontcache.tests.base.StaticReadTests;

public class FilterStaticReadTests extends StaticReadTests {

	@Override
	public String getFrontCacheBaseURL() {
		return getFilterBaseURL();
	}

}
