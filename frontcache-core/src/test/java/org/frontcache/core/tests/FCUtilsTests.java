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
package org.frontcache.core.tests;

import static org.junit.Assert.assertEquals;

import org.frontcache.core.FCUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FCUtilsTests {

	protected Logger logger = LoggerFactory.getLogger(FCUtilsTests.class);  


	@Test
	public void buildRequestURITest() throws Exception {
		
		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.coinshome.net:9080/fc/include-footer.htm?locale=en"));
		
		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.coinshome.net/fc/include-footer.htm?locale=en"));
		
		assertEquals("/ccc/veiw-catref-groups.htm?catrefFQ=catRef%3AKM%5C-2", FCUtils.buildRequestURI("http://origin.coinshome.net/ccc/veiw-catref-groups.htm?q=&catrefFQ=catRef:KM\\-2"));
		
		return;
	}
}
