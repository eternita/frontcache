package org.frontcache.tests.standalone;

import org.frontcache.tests.base.HTTPResponseCodeTests;

public class StandaloneHTTPResponseCodeTests extends HTTPResponseCodeTests {

	@Override
	public String getFrontCacheBaseURLDomainFC1() {
		return getStandaloneBaseURLDomainFC1();
	}

}
