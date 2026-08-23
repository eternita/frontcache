package org.frontcache.tests.filter;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.frontcache.tests.base.CommonTests;
import org.junit.Test;

import org.htmlunit.Page;
import org.htmlunit.WebResponse;

public class FilterCommonTests extends CommonTests {


	@Test
	public void frontcacheIdTest() throws Exception {
		Page page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/fc-headers/a.jsp");
		WebResponse webResponse = page.getWebResponse();
		printHeaders(webResponse);
		String frontcacheId = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_ID);

		assertEquals("localhost-fc-filter", frontcacheId);
	}

	@Override
	public String getFrontCacheBaseURLLocalhost() {
		return getFilterBaseURLLocalhost();
	}

}
