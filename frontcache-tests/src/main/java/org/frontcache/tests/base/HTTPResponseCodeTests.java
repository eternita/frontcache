package org.frontcache.tests.base;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebResponse;

public abstract class HTTPResponseCodeTests extends TestsBase {

	@Before
	public void setUp() throws Exception {
		super.setUp();
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
	}
	
	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURL(); 

	@Test
	public void code404() throws Exception {
		Page page = webClient.getPage(getFrontCacheBaseURL() + "something/what-doesnt-exist");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		Assert.assertEquals(404, webResponse.getStatusCode());
	}

}
