package org.frontcache.tests.filter;

import org.frontcache.tests.base.AgentTests;

public class FilterAgentTests extends AgentTests {

	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getFilterBaseURLLocalhost();
	}

	@Override
	public String getFrontCacheBaseURLLoopback() {
		return getFilterBaseURLLoopback();
	}

}
