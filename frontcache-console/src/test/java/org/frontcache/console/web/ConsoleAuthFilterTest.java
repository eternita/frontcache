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
import static org.junit.Assert.assertNull;
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
import org.springframework.mock.web.MockHttpSession;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The console's single-user login - see docs/basic-auth-proposal.md (the credential) and
 * docs/console-login-ui.md (the login screen).
 *
 * Three groups of cases carry the weight here, and each one is silent when it breaks:
 *
 * <ul>
 * <li>the Basic path still passes - {@code curl}, the container HEALTHCHECK and anything automated
 *     against a 2.9.0 console;</li>
 * <li>the exclusion set is EXACT - {@code anAssetTheLoginPageDoesNotNeedIsStillProtected} is the
 *     assertion that fails when someone turns it into a prefix match;</li>
 * <li>a console whose config predates authentication has no credentials and must keep serving.
 *     Failing closed there would take a monitoring dashboard offline during an upgrade nobody
 *     expected to be breaking.</li>
 * </ul>
 */
public class ConsoleAuthFilterTest {

	private ConsoleAuthFilter filter;

	private FrontcacheService frontcacheService;

	private MockHttpServletRequest request;
	private MockHttpServletResponse response;
	private MockFilterChain chain;

	@Before
	public void setUp() {
		frontcacheService = Mockito.mock(FrontcacheService.class);
		filter = new ConsoleAuthFilter();
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

	/** Even the login page: there is nothing to sign into, so nothing is excluded from nothing. */
	@Test
	public void withNoCredentialsConfiguredTheLoginPageIsServedToo() throws Exception {
		when(frontcacheService.isAuthEnabled()).thenReturn(false);
		request = get("/login");

		run();

		assertTrue(passedThrough());
	}

	// ---- the session, once the login page has been used ----------------------------------------

	@Test
	public void anAuthenticatedSessionPassesThroughWithNoHeader() throws Exception {
		request.setSession(signedInSession());

		run();

		assertTrue(passedThrough());
		assertEquals(HttpServletResponse.SC_OK, response.getStatus());
	}

	/** A session exists but nobody signed in - a plain visit that the container gave a session. */
	@Test
	public void aSessionWithoutTheMarkerIsNotAuthenticated() throws Exception {
		request.setSession(new MockHttpSession());
		request.addHeader("Accept", "text/html");

		run();

		assertFalse(passedThrough());
		assertEquals(HttpServletResponse.SC_FOUND, response.getStatus());
	}

	/**
	 * <b>The filter must never create a session.</b> The console answers whatever can route to its
	 * port, and getSession(true) here would allocate a session and a timer per scanner probe - with
	 * no per-IP limit to lean on. See docs/console-login-ui.md section 4.2.
	 */
	@Test
	public void aRefusedRequestCreatesNoSession() throws Exception {
		run();

		assertFalse(passedThrough());
		assertNull(request.getSession(false));
	}

	@Test
	public void anAuthorizedBasicRequestCreatesNoSessionEither() throws Exception {
		request.addHeader("Authorization", basic("admin", "s3cret"));

		run();

		assertTrue(passedThrough());
		assertNull(request.getSession(false));
	}

	// ---- the shape of the refusal --------------------------------------------------------------

	/**
	 * A page navigation gets the login page, and it must NOT get a WWW-Authenticate header - that
	 * is what opens the browser's native dialog on top of the page the login screen replaces. See
	 * docs/console-login-ui.md section 5.2.
	 */
	@Test
	public void aBrowserNavigationIsRedirectedToTheLoginPageWithoutAChallenge() throws Exception {
		request.addHeader("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");

		run();

		assertFalse(passedThrough());
		assertEquals(HttpServletResponse.SC_FOUND, response.getStatus());
		assertEquals("/login?next=%2Frealtime", response.getRedirectedUrl());
		assertNull(response.getHeader("WWW-Authenticate"));
		assertEquals("no-store", response.getHeader("Cache-Control"));
	}

	/** The query string is where an edge URL or a cache key lives; losing it loses the page. */
	@Test
	public void theQueryStringSurvivesIntoNext() throws Exception {
		request = get("/cache-view");
		request.setQueryString("key=http%3A%2F%2Fa%2Fb&amount=50");
		request.addHeader("Accept", "text/html");

		run();

		assertEquals("/login?next=%2Fcache-view%3Fkey%3Dhttp%253A%252F%252Fa%252Fb%26amount%3D50",
				response.getRedirectedUrl());
	}

	/** Nothing to remember - the front page is where a login goes anyway. */
	@Test
	public void theFrontPageNeedsNoNextParameter() throws Exception {
		request = get("/");
		request.addHeader("Accept", "text/html");

		run();

		assertEquals("/login", response.getRedirectedUrl());
	}

	@Test
	public void aJsonPollGetsA401WithTheChallenge() throws Exception {
		request = get("/edges-status.json");
		request.addHeader("Accept", "application/json");

		run();

		assertFalse(passedThrough());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
		assertEquals("Basic realm=\"Frontcache Console\", charset=\"UTF-8\"",
				response.getHeader("WWW-Authenticate"));
		assertNull(response.getRedirectedUrl());
	}

	/** curl, and the console image's HEALTHCHECK. Unchanged from 2.9.0, and it has to be. */
	@Test
	public void aCallerThatSendsNoAcceptHeaderGetsA401() throws Exception {
		run();

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
		assertNull(response.getRedirectedUrl());
	}

	@Test
	public void anEventSourceGetsA401() throws Exception {
		request = get("/fc-dashboard-proxy.stream");
		request.addHeader("Accept", "text/event-stream");

		run();

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
	}

	// ---- the exclusion set ---------------------------------------------------------------------

	@Test
	public void theLoginPageAndTheThreeFilesItRendersWithAreServedAnonymously() throws Exception {
		String[] anonymous = {
				"/login",
				"/static/css/fc-login.css",
				"/static/images/frontcache-logo.png",
				"/static/images/favicon.ico" };

		for (String path : anonymous)
		{
			get(path);

			run();

			assertTrue("should be served anonymously: " + path, passedThrough());
		}
	}

	/**
	 * <b>This is the assertion that fails when the exact match becomes a prefix match.</b> The
	 * login page needs four files; everything else the console serves stays behind the credential,
	 * including the rest of /static.
	 */
	@Test
	public void anAssetTheLoginPageDoesNotNeedIsStillProtected() throws Exception {
		String[] protectedPaths = {
				"/static/js/console.js",
				"/static/css/app.css",
				"/static/css/bootstrap.css",
				"/static/images/map.png",
				"/static/",
				"/static" };

		for (String path : protectedPaths)
		{
			get(path);

			run();

			assertFalse("should NOT be served anonymously: " + path, passedThrough());
		}
	}

	/** Traversal, a trailing slash and a different case are all "not the login page". */
	@Test
	public void onlyTheExactLoginPathIsExcluded() throws Exception {
		String[] notTheLoginPage = {
				"/LOGIN",
				"/Login",
				"/login/",
				"/login/../realtime",
				"/loginx",
				"/realtime/login" };

		for (String path : notTheLoginPage)
		{
			get(path);

			run();

			assertFalse("should NOT be served anonymously: " + path, passedThrough());
		}
	}

	/**
	 * A path parameter is not part of the path. A browser with cookies disabled is where
	 * ";jsessionid=..." shows up, and the login page has to keep working there.
	 */
	@Test
	public void aPathParameterDoesNotHideTheLoginPage() throws Exception {
		get("/login;jsessionid=ABC123");

		run();

		assertTrue(passedThrough());
	}

	/** The container's own answer, which is what the filter reads when it is populated. */
	@Test
	public void theServletPathIsPreferredOverTheRequestUri() throws Exception {
		request = new MockHttpServletRequest("GET", "/console/login");
		request.setContextPath("/console");
		request.setServletPath("/login");
		response = new MockHttpServletResponse();
		chain = new MockFilterChain();

		run();

		assertTrue(passedThrough());
	}

	// ---- helpers -------------------------------------------------------------------------------

	private MockHttpServletRequest get(String path) {
		request = new MockHttpServletRequest("GET", path);
		response = new MockHttpServletResponse();
		chain = new MockFilterChain();
		return request;
	}

	private MockHttpSession signedInSession() {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE, "admin");
		return session;
	}

}
