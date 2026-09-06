package org.frontcache.tests.filter;

import org.frontcache.tests.base.ClientTests;

public class FilterClientTests extends ClientTests {


	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getFilterBaseURLLocalhost();
	}

	@Override
	public String getFrontCacheBaseURLLoopback() {
		return getFilterBaseURLLoopback();
	}

	/** the filter node ships no guard-rules.conf, which is what keeps the "file absent" path covered */
	@Override
	protected boolean nodeShipsGuardRules() {
		return false;
	}

	/** this node's bots.conf is deliberately left bare-keyword-only */
	@Override
	protected String expectedBotsConfigMarker() {
		return "bare-keyword-only format";
	}

}
