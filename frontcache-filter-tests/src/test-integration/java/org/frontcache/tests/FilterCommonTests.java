package org.frontcache.tests;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebResponse;

public class FilterCommonTests extends CommonTests {


	@Test
	public void frontcacheIdTest() throws Exception {
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "common/fc-headers/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		String frontcacheId = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_ID);

		assertEquals("localhost-fc-filter", frontcacheId);
	}
	
}
