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

import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

/**
 * 
 * Defined tests are run in filter & standalone modes
 *
 */
public abstract class InvalidationTagsTests extends TestsBase {



	@Before
	public void setUp() throws Exception {
		super.setUp();

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}
	
	public abstract String getFrontCacheBaseURLDomainFC1(); 



	@Test
	public void staticInvalidationTags() throws Exception {
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/invalidation-tags/a.jsp");
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 

		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("-1", maxage);

		String tags = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS);
		assertEquals("apple|banana|orange", tags);
		
	}

	@Test
	public void dynamicInvalidationTags() throws Exception {
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/invalidation-tags/b.jsp");
		assertEquals("a", page.getPage().asText());
		
		WebResponse webResponse = page.getWebResponse(); 

		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("-1", maxage);

		String tags = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS);
		assertEquals("apple|banana|orange", tags);
		
	}
	
}
