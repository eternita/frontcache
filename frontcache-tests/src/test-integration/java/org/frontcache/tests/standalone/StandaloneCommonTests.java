package org.frontcache.tests.standalone;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.frontcache.tests.base.CommonTests;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebResponse;

/**
 * 
 * run tests defined in CommonTests through it
 * 
 *
 */
public class StandaloneCommonTests extends CommonTests {

	
	@Test
	public void frontcacheIdTest() throws Exception {
		Page page = webClient.getPage(getFrontCacheBaseURL() + "common/fc-headers/a.jsp");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		String frontcacheId = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_ID);

		assertEquals("localhost-fc-standalone", frontcacheId);
	}

	@Override
	public String getFrontCacheBaseURL() {
		return getStandaloneBaseURLLocalhost();
	}
	
}
