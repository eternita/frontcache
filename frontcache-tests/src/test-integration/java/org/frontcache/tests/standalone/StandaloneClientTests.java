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
	public String getFrontCacheBaseURLLocalhost() {
		return getStandaloneBaseURLLocalhost();
	}

	@Override
	public String getFrontCacheBaseURLLoopback() {
		return getStandaloneBaseURLLoopback();
	}

	/** the standalone node carries the rate-limit rule the guard e2e tests exercise */
	@Override
	protected boolean nodeShipsGuardRules() {
		return true;
	}

	/** this node's bots.conf is on the 2.9.0 rule format */
	@Override
	protected String expectedBotsConfigMarker() {
		return "headless-e2e";
	}

}
