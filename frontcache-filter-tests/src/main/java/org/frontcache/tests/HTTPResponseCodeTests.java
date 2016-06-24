package org.frontcache.tests;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebResponse;


public class HTTPResponseCodeTests extends CommonTestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
	}
	
	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	@Test
	public void code404() throws Exception {
		Page page = webClient.getPage(TestConfig.FRONTCACHE_TEST_BASE_URI + "something/what-doesnt-exist");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		Assert.assertEquals(404, webResponse.getStatusCode());
	}

}
