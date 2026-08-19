package org.frontcache.tests.standalone;

import org.frontcache.tests.base.SiteKeyTests;

/**
 *
 * run tests defined in SiteKeyTests through it
 *
 */
public class StandaloneSiteKeyTests extends SiteKeyTests {

	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getStandaloneBaseURLLocalhost();
	}

}
