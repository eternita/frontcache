/**
 *        Copyright 2017 Eternita LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.html.HtmlPage;

public abstract class IncludeTests extends TestsBase {

	public abstract String getFrontCacheBaseURLDomainFC1(); 
	
	protected FrontCacheClient frontcacheClient = null;
	
	@Before
	public void setUp() throws Exception {
		super.setUp();
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		frontcacheClient.removeFromCacheAll(); // clean up		

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	
	@Test
	public void includeAsync1() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/a1.jsp");
		assertEquals("a", page.getPage().asText()); // no 'b' in response because 'b' included in async mode
	}
	
	
	@Test
	public void includeAsync2() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/a.jsp");
		assertEquals("abcd", page.getPage().asText()); // no 'ef' in response because 'e' included in async mode

	}

	
	@Test
	public void includeAsync3() throws Exception {
		
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/a.jsp");
		assertEquals("abcd", page.getPage().asText()); // no 'ef' in response because 'e' included in async mode

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache( getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/a.jsp");
		assertEquals("a<fc:include url=\"/common/deep-include-async/b.jsp\" />", new String(resp.getContent()));	

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/b.jsp");
		assertEquals("b<fc:include url=\"/common/deep-include-async/c.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/c.jsp");
		assertEquals("c<fc:include url=\"/common/deep-include-async/d.jsp\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/d.jsp");
		assertEquals("d<fc:include url=\"/common/deep-include-async/e.jsp\" call=\"async\" />", new String(resp.getContent()));	
		
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/e.jsp");
		assertEquals("e<fc:include url=\"/common/deep-include-async/f.jsp\" />", new String(resp.getContent()));	

		// !!! includes inside async includes are not called
		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLDomainFC1() + "common/deep-include-async/f.jsp");
		assertEquals(null, resp);	
	}
	
	@Test
	public void includeClientSpecific1() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a.jsp");
		assertEquals("ab", page.getPage().asText());
		
		// request as browser
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a.jsp");
		assertEquals("ab", page.getPage().asText());
	}
	
	@Test
	public void includeClientSpecific2() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a1.jsp");
		assertEquals("ab", page.getPage().asText());
		
		// request as browser
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a1.jsp");
		assertEquals("a", page.getPage().asText());
	}
	
	@Test
	public void includeClientSpecific3() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a2.jsp");
		assertEquals("a", page.getPage().asText());
		
		// request as browser
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a2.jsp");
		assertEquals("ab", page.getPage().asText());
	}
	
	@Test
	public void includeClientSpecific4() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a3.jsp");
		assertEquals(page.getPage().asText(), "a");
		
		// request as browser
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/include-bot-browser/a3.jsp");
		assertEquals(page.getPage().asText(), "a");
	}
	
}
