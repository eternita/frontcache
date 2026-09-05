package org.frontcache.tests.standalone;

import org.frontcache.tests.base.ApiKeyTests;

/**
 *
 * run tests defined in ApiKeyTests through it
 *
 */
public class StandaloneApiKeyTests extends ApiKeyTests {

	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getStandaloneBaseURLLocalhost();
	}

}
