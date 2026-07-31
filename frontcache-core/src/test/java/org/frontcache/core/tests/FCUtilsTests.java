/**
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
	 * Cloudflare re-case origin response headers (e.g. send "X-Frontcache-Component-Maxage"
	 * while FCHeaders uses "x-frontcache-component-maxage"). revertHeaders() must build a
	 * case-insensitive map so component cache directives still resolve - otherwise the
	 * edge node treats every response as NO_CACHE and serves it dynamic forever.
	 *
	 * @throws Exception
	 */
	@Test
	public void revertHeadersIsCaseInsensitive() throws Exception {

		Header[] headers = new Header[] {
				new BasicHeader("X-Frontcache-Component-Maxage", "3h"),   // title-cased by proxy
				new BasicHeader("X-Frontcache-Component-Tags", "welcome"),
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

		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.example.com:9080/fc/include-footer.htm?locale=en"));

		assertEquals("/fc/include-footer.htm?locale=en", FCUtils.buildRequestURI("http://myfc.example.com/fc/include-footer.htm?locale=en"));

		assertEquals("/ccc/veiw-catref-groups.htm?catrefFQ=catRef%3AKM%5C-2", FCUtils.buildRequestURI("http://origin.example.com/ccc/veiw-catref-groups.htm?q=&catrefFQ=catRef:KM\\-2"));

		return;
	}

	/**
	 * getClientIP must never propagate an exception: async includes resolve it in a background thread
	 * after the servlet request has been recycled, so the request is either null or throws on access.
	 * This used to NPE and abort the include before the origin call ran (100k+/day on
	 * coin-visit-counter). It must degrade to "unresolved" instead.
	 */
	@Test
	public void getClientIPIsNullSafe() throws Exception {
		assertEquals("unresolved", FCUtils.getClientIP(null));
	}

	@Test
	public void getClientIPSurvivesRecycledRequest() throws Exception {
		// a container-recycled request throws on any accessor
		jakarta.servlet.http.HttpServletRequest recycled = (jakarta.servlet.http.HttpServletRequest)
				java.lang.reflect.Proxy.newProxyInstance(
						getClass().getClassLoader(),
						new Class[]{ jakarta.servlet.http.HttpServletRequest.class },
						(proxy, method, args) -> { throw new IllegalStateException("request does not exist (recycled)"); });

		assertEquals("unresolved", FCUtils.getClientIP(recycled));
	}
}
