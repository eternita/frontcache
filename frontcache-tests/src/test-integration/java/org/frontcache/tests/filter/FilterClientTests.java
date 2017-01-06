package org.frontcache.tests.filter;

import org.frontcache.tests.base.ClientTests;

public class FilterClientTests extends ClientTests {


	@Override
	public String getFrontCacheBaseURLDomainFC1() {
		return getFilterBaseURLDomainFC1();
	}
	
	@Override
	public String getFrontCacheBaseURLDomainFC2() {
		return getFilterBaseURLDomainFC2();
	}
}
