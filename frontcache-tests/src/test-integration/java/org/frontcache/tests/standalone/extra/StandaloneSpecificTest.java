package org.frontcache.tests.standalone.extra;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.frontcache.tests.base.TestsBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebClient;

public class StandaloneSpecificTest extends TestsBase {

	WebClient webClient = null;
	
	@Before
	public void setUp() throws Exception {
		webClient = new WebClient();
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		webClient.close();
	}
	

	
	@Test
	public void test2() throws Exception {
		
		TextPage page = webClient.getPage(getStandaloneBaseURL() + "standalone/2/a.txt");
		assertEquals("ab", page.getContent());
	}
	
}

