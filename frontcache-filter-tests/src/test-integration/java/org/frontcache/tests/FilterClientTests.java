package org.frontcache.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.junit.Assert;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class FilterClientTests extends ClientTests {

	// different port inside cache keys
	@Test
	public void getFromCacheClient() throws Exception {
		
		final String TEST_URI_A = "common/fc-agent/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_DEBUG, "true");
		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + TEST_URI_A);
		assertEquals("a", page.getPage().asText());		

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache("http://localhost:9080/" + TEST_URI_A);

		assertEquals("a", new String(resp.getContent()));	
		return;
	}

	@Test
	public void getFromCacheClientNull() throws Exception {
		
		final String TEST_URI_A = "common/fc-agent/a.jsp";

		frontcacheClient = new FrontCacheClient(TestConfig.FRONTCACHE_TEST_BASE_URI);
		
		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache("http://localhost:9080/" + TEST_URI_A);

		assertNull(resp);	
		return;
	}

}
