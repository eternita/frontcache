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
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.frontcache.console.service.FrontcacheService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The console's single-user Basic login - see docs/basic-auth-proposal.md.
 *
 * The case that matters most is the LAST one: a console whose config predates this feature has no
 * credentials and must keep serving. Failing closed there would take a monitoring dashboard offline
 * during an upgrade nobody expected to be breaking.
 */
public class BasicAuthFilterTest {

	private BasicAuthFilter filter;

	private FrontcacheService frontcacheService;

	private MockHttpServletRequest request;
	private MockHttpServletResponse response;
	private MockFilterChain chain;

	@Before
	public void setUp() {
		frontcacheService = Mockito.mock(FrontcacheService.class);
		filter = new BasicAuthFilter();
		filter.setFrontcacheService(frontcacheService);

		request = new MockHttpServletRequest("GET", "/realtime");
		response = new MockHttpServletResponse();
		chain = new MockFilterChain();

		authConfigured("admin", "s3cret");
	}

	private void authConfigured(String user, String password) {
		when(frontcacheService.isAuthEnabled()).thenReturn(true);
		when(frontcacheService.getUser()).thenReturn(user);
		when(frontcacheService.getPassword()).thenReturn(password);
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder()
				.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private void run() throws Exception {
		filter.doFilter(request, response, chain);
	}

	/** The chain ran iff the request was let through. */
	private boolean passedThrough() {
		return null != chain.getRequest();
	}

	// ---- the happy path ------------------------------------------------------------------------

	@Test
	public void correctCredentialsPassThrough() throws Exception {
		request.addHeader("Authorization", basic("admin", "s3cret"));

		run();

		assertTrue(passedThrough());
		assertEquals(HttpServletResponse.SC_OK, response.getStatus());
	}

	/** RFC 7617: the scheme token is case-insensitive, and so is the header name. */
	@Test
	public void theSchemeTokenIsCaseInsensitive() throws Exception {
		request.addHeader("authorization", "basic " + Base64.getEncoder()
				.encodeToString("admin:s3cret".getBytes(StandardCharsets.UTF_8)));

		run();

		assertTrue(passedThrough());
	}

	/** Split on the FIRST colon only - a password may contain colons, a username may not. */
	@Test
	public void aPasswordMayContainColons() throws Exception {
		authConfigured("admin", "a:b:c");
		request.addHeader("Authorization", basic("admin", "a:b:c"));

		run();

		assertTrue(passedThrough());
	}

	/** charset="UTF-8" in the challenge is what makes this behave the same across browsers. */
	@Test
	public void aNonAsciiPasswordIsDecodedAsUtf8() throws Exception {
		authConfigured("admin", "paßwort—é");
		request.addHeader("Authorization", basic("admin", "paßwort—é"));

		run();

		assertTrue(passedThrough());
	}

	// ---- refusals ------------------------------------------------------------------------------

	@Test
	public void noHeaderIsChallenged() throws Exception {
		run();

		assertFalse(passedThrough());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
		assertEquals("Basic realm=\"Frontcache Console\", charset=\"UTF-8\"",
				response.getHeader("WWW-Authenticate"));
	}

	@Test
	public void aWrongPasswordIsRefused() throws Exception {
		request.addHeader("Authorization", basic("admin", "wrong"));

		run();

		assertFalse(passedThrough());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
	}

	@Test
	public void aWrongUserIsRefused() throws Exception {
		request.addHeader("Authorization", basic("root", "s3cret"));

		run();

		assertFalse(passedThrough());
	}

	/** An empty password must never authenticate against a configured one. */
	@Test
	public void anEmptyPasswordIsRefused() throws Exception {
		request.addHeader("Authorization", basic("admin", ""));

		run();

		assertFalse(passedThrough());
	}

	/** A probe must produce a 401, not a 500 - these are all "not authorized", nothing more. */
	@Test
	public void malformedHeadersAreRefusedWithoutThrowing() throws Exception {
		String noColon = "Basic "
				+ Base64.getEncoder().encodeToString("nocolon".getBytes(StandardCharsets.UTF_8));

		String[] malformed = { "Bearer abc", "Basic", "Basic ", "Basic !!!not-base64!!!", "", noColon };

		for (String header : malformed)
		{
			request = new MockHttpServletRequest("GET", "/realtime");
			response = new MockHttpServletResponse();
			chain = new MockFilterChain();
			request.addHeader("Authorization", header);

			run();

			assertFalse("should not pass: " + header, passedThrough());
			assertEquals("should be 401: " + header,
					HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
		}
	}

	// ---- the upgrade path ----------------------------------------------------------------------

	/**
	 * A config file written before this feature existed has no credentials, and that console keeps
	 * serving. This is the decision in section 5 of the proposal, and it is the one behaviour here
	 * that an operator would notice immediately if it regressed.
	 */
	@Test
	public void withNoCredentialsConfiguredEveryRequestPassesThrough() throws Exception {
		when(frontcacheService.isAuthEnabled()).thenReturn(false);

		run();

		assertTrue(passedThrough());
		assertEquals(HttpServletResponse.SC_OK, response.getStatus());
		assertEquals(null, response.getHeader("WWW-Authenticate"));
	}

}
