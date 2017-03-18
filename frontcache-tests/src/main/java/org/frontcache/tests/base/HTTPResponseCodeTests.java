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
	
	public abstract String getFrontCacheBaseURLDomainFC1(); 

	@Test
	public void code404() throws Exception {
		Page page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "something/what-doesnt-exist");
		WebResponse webResponse = page.getWebResponse(); 
		printHeaders(webResponse);
		
		Assert.assertEquals(404, webResponse.getStatusCode());
	}

}
