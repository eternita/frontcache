/*
 * Copyright 2018 Eternita
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.frontcache.tests.standalone.extra;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCHeaders;
import org.frontcache.tests.base.TestsBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.htmlunit.TextPage;
import org.htmlunit.WebClient;

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

		TextPage page = webClient.getPage(getStandaloneBaseURLLocalhost() + "standalone/2/a.txt");
		assertEquals("ab", page.getContent());
	}

}

