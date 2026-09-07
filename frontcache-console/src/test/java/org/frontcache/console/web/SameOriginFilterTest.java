/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The cross-origin check on state-changing requests - docs/archive/basic-auth-proposal.md section 8.1.
 *
 * It exists because turning Basic auth on makes CSRF reachable: the browser attaches cached Basic
 * credentials to a cross-site request by itself, and they are not cookies, so SameSite does not
 * apply. What it buys is bounded, and two tests below pin the boundary deliberately - a request with
 * no Origin and no Referer is ALLOWED (curl, an operator's script), and the scheme is NOT compared
 * (a console behind a TLS-terminating proxy sees http while the browser sends https).
 */
public class SameOriginFilterTest {

	private SameOriginFilter filter;
	private MockHttpServletResponse response;
	private MockFilterChain chain;

	@Before
	public void setUp() {
		filter = new SameOriginFilter();
		response = new MockHttpServletResponse();
		chain = new MockFilterChain();
	}

	private MockHttpServletRequest post() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cache-invalidation");
		request.addHeader("Host", "console.example.com:7080");
		return request;
	}

	private boolean passes(MockHttpServletRequest request) throws Exception {
		filter.doFilter(request, response, chain);
		return null != chain.getRequest();
	}

	// ---- safe methods are never checked --------------------------------------------------------

	@Test
	public void aGetFromAnywherePasses() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realtime");
		request.addHeader("Host", "console.example.com:7080");
		request.addHeader("Origin", "https://evil.example.net");

		assertTrue(passes(request));
	}

	// ---- state changes ------------------------------------------------------------------------

	@Test
	public void aPostFromTheConsoleItselfPasses() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Origin", "http://console.example.com:7080");

		assertTrue(passes(request));
	}

	@Test
	public void aPostFromAnotherSiteIsRejected() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Origin", "https://evil.example.net");

		assertFalse(passes(request));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
	}

	/** A different port on the same host is a different origin. */
	@Test
	public void aPostFromAnotherPortOnTheSameHostIsRejected() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Origin", "http://console.example.com:9090");

		assertFalse(passes(request));
	}

	/**
	 * The scheme is deliberately NOT compared: behind a TLS-terminating reverse proxy the browser
	 * sends https while this connector sees http, and comparing schemes would reject every state
	 * change on exactly the deployments that bothered with TLS.
	 */
	@Test
	public void httpsOriginAgainstAnHttpConnectorPasses() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cache-invalidation");
		request.addHeader("Host", "console.example.com"); // no port
		request.addHeader("X-Forwarded-Proto", "https");
		request.addHeader("Origin", "https://console.example.com"); // implicit :443

		assertTrue(passes(request));
	}

	/** Default ports: Origin omits :80, Host may too. They must compare equal. */
	@Test
	public void defaultPortsCompareEqual() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cache-invalidation");
		request.addHeader("Host", "console.example.com");
		request.addHeader("Origin", "http://console.example.com:80");

		assertTrue(passes(request));
	}

	@Test
	public void theHostComparisonIsCaseInsensitive() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Origin", "http://CONSOLE.example.COM:7080");

		assertTrue(passes(request));
	}

	// ---- Origin absent -------------------------------------------------------------------------

	/**
	 * No Origin and no Referer is ALLOWED. curl sends neither, and an operator's script has to keep
	 * working - this filter stops a browser being used as a confused deputy, and nothing else.
	 */
	@Test
	public void aPostWithNoOriginAndNoRefererPasses() throws Exception {
		assertTrue(passes(post()));
	}

	@Test
	public void refererIsUsedWhenOriginIsAbsent() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Referer", "http://console.example.com:7080/edges");

		assertTrue(passes(request));
	}

	@Test
	public void aCrossSiteRefererIsRejected() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Referer", "https://evil.example.net/page.html");

		assertFalse(passes(request));
	}

	/**
	 * "null" is what a sandboxed iframe and some redirect chains send. It is not this origin, and it
	 * must not fall through to a Referer the same request may still carry.
	 */
	@Test
	public void aNullOriginIsRejectedEvenWithAGoodReferer() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Origin", "null");
		request.addHeader("Referer", "http://console.example.com:7080/edges");

		assertFalse(passes(request));
	}

	@Test
	public void anUnparseableOriginIsRejected() throws Exception {
		MockHttpServletRequest request = post();
		request.addHeader("Origin", ":::not a url:::");

		assertFalse(passes(request));
	}

	// ---- the endpoints this was added for ------------------------------------------------------

	/**
	 * add-edge and remove-edge became POST in the same change. As GETs they were reachable from any
	 * page the operator had open; as POSTs they are here, and this is what checks them.
	 */
	@Test
	public void aCrossSiteEdgeRemovalIsRejected() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/remove-edge");
		request.addHeader("Host", "console.example.com:7080");
		request.addHeader("Origin", "https://evil.example.net");

		assertFalse(passes(request));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
	}

	/**
	 * /save-config writes a node's config file and applies it, which makes it the most consequential
	 * POST the console has - a page the operator has open in another tab must not be able to rewrite
	 * the fleet's guard rules through their cached Basic credentials.
	 */
	@Test
	public void aCrossSiteConfigSaveIsRejected() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/save-config");
		request.addHeader("Host", "console.example.com:7080");
		request.addHeader("Origin", "https://evil.example.net");

		assertFalse(passes(request));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
	}

	/** ...and the operator's own page still works. */
	@Test
	public void aSameOriginConfigSaveIsAllowed() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/save-config");
		request.addHeader("Host", "console.example.com:7080");
		request.addHeader("Origin", "https://console.example.com:7080");

		assertTrue(passes(request));
	}

	/**
	 * The login page is the one thing ConsoleAuthFilter serves without a credential, so this filter
	 * is the only check standing in front of it - and login itself must not be drivable from
	 * another site. See docs/archive/console-login-ui.md section 8.
	 */
	@Test
	public void aCrossSiteLoginIsRejected() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.addHeader("Host", "console.example.com:7080");
		request.addHeader("Origin", "https://evil.example.net");

		assertFalse(passes(request));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
	}

	/** Same for the sign-out, which is a POST for exactly this reason. */
	@Test
	public void aCrossSiteLogoutIsRejected() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
		request.addHeader("Host", "console.example.com:7080");
		request.addHeader("Origin", "https://evil.example.net");

		assertFalse(passes(request));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
	}

}
