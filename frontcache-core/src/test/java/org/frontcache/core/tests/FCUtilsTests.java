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
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FCUtilsTests {

	protected Logger logger = LoggerFactory.getLogger(FCUtilsTests.class);

	/**
	 * HTTP header names are case-insensitive (RFC 7230). Intermediaries such as
	 * Cloudflare re-case origin response headers (e.g. send "X-Frontcache.component.maxage"
	 * while FCHeaders uses "X-frontcache.component.maxage"). revertHeaders() must build a
	 * case-insensitive map so component cache directives still resolve - otherwise the
	 * edge node treats every response as NO_CACHE and serves it dynamic forever.
	 *
	 * @throws Exception
	 */
	@Test
	public void revertHeadersIsCaseInsensitive() throws Exception {

		Header[] headers = new Header[] {
				new BasicHeader("X-Frontcache.component.maxage", "3h"),   // title-cased by proxy
				new BasicHeader("X-Frontcache.component.tags", "welcome"),
				new BasicHeader("Content-Type", "text/html;charset=UTF-8")
		};

		Map<String, List<String>> map = FCUtils.revertHeaders(headers);

		// looked up with the (lowercase-f) FCHeaders constants used by parseWebComponent
		assertNotNull("maxage must resolve regardless of header-name casing", map.get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE));
		assertEquals("3h", map.get(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE).get(0));
		assertEquals("welcome", map.get(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS).get(0));
		assertEquals("text/html;charset=UTF-8", map.get(FCHeaders.CONTENT_TYPE).get(0));

		return;
	}


	@Test
	public void buildRequestURITest() throws Exception {

		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.hobbyray.com:9080/fc/include-footer.htm?locale=en"));

		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.hobbyray.com/fc/include-footer.htm?locale=en"));

		assertEquals("/ccc/veiw-catref-groups.htm?catrefFQ=catRef%3AKM%5C-2", FCUtils.buildRequestURI("http://origin.hobbyray.com/ccc/veiw-catref-groups.htm?q=&catrefFQ=catRef:KM\\-2"));

		return;
	}
}
