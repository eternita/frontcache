package org.frontcache.tests.standalone;

import org.frontcache.tests.base.AcceptEncodingTests;

/**
 *
 * Runs the AcceptEncodingTests catalog against the standalone server (:9080),
 * i.e. cache-by-cache mode (FC1 standalone chained in front of the FC2 filter).
 *
 */
public class StandaloneAcceptEncodingTests extends AcceptEncodingTests {

	@Override
	public String getFrontCacheBaseURLDomainFC1() {
		return getStandaloneBaseURLDomainFC1();
	}

}
