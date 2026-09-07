/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web.controllers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import org.frontcache.console.service.FrontcacheService;
import org.frontcache.console.web.ConsoleAuthFilter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ModelMap;

import jakarta.servlet.http.HttpSession;

/**
 * The console's login screen - see docs/archive/console-login-ui.md.
 *
 * Three of these carry more weight than the rest, and all three are silent when they break: the
 * {@code next} validation (an open redirect on a host an operator trusts), the failure branch
 * creating no session, and an un-credentialed console refusing to sign anyone in at all.
 */
public class LoginControllerTest {

	private LoginController controller;

	private FrontcacheService frontcacheService;

	private MockHttpServletRequest request;
	private ModelMap model;

	@Before
	public void setUp() {
		frontcacheService = Mockito.mock(FrontcacheService.class);
		when(frontcacheService.isAuthEnabled()).thenReturn(true);
		when(frontcacheService.getUser()).thenReturn("admin");
		when(frontcacheService.getPassword()).thenReturn("s3cret");

		controller = new LoginController();
		controller.setFrontcacheService(frontcacheService);

		request = new MockHttpServletRequest("POST", "/login");
		model = new ModelMap();
	}

	private String submit(String user, String password, String next) {
		request.setParameter("user", user);
		request.setParameter("password", password);
		if (null != next)
			request.setParameter("next", next);

		return controller.login(request, model);
	}

	private String signedInUser() {
		HttpSession session = request.getSession(false);
		return null == session ? null : (String) session.getAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE);
	}

	// ---- signing in ----------------------------------------------------------------------------

	@Test
	public void theCorrectCredentialEstablishesASessionAndGoesWhereTheOperatorWasHeaded() {
		String view = submit("admin", "s3cret", "/cache-view");

		assertEquals("redirect:/cache-view", view);
		assertEquals("admin", signedInUser());
	}

	@Test
	public void withNoTargetItGoesToTheFrontPage() {
		assertEquals("redirect:/", submit("admin", "s3cret", null));
	}

	/**
	 * Session fixation: the id an attacker could have planted must not be the id the operator ends
	 * up authenticated on. Three lines, and the argument for skipping it is never worth writing
	 * down.
	 */
	@Test
	public void signingInReplacesAnExistingSession() {
		MockHttpSession planted = new MockHttpSession();
		planted.setAttribute("planted", "yes");
		request.setSession(planted);

		submit("admin", "s3cret", null);

		HttpSession current = request.getSession(false);
		assertNotNull(current);
		assertEquals("admin", current.getAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE));
		assertNull("the old session's contents must not carry over", current.getAttribute("planted"));
	}

	/** The header path decodes UTF-8; the form path must agree, or a password works one way only. */
	@Test
	public void aNonAsciiPasswordMatches() {
		when(frontcacheService.getPassword()).thenReturn("paßwort—é");

		assertEquals("redirect:/", submit("admin", "paßwort—é", null));
		assertEquals("admin", signedInUser());
	}

	// ---- refusals ------------------------------------------------------------------------------

	@Test
	public void everyKindOfWrongCredentialGetsTheSamePageAndTheSameMessage() {
		String[][] wrong = {
				{ "admin", "wrong" },
				{ "root", "s3cret" },
				{ "root", "wrong" },
				{ "admin", "" },
				{ "", "" } };

		for (String[] credential : wrong)
		{
			request = new MockHttpServletRequest("POST", "/login");
			model = new ModelMap();

			String view = submit(credential[0], credential[1], "/cache-view");

			assertEquals("login", view);
			assertEquals(LoginController.WRONG_CREDENTIAL, model.get("error"));
			assertNull("a failed login must create no session", request.getSession(false));
		}
	}

	/** A missing form field is not a credential. */
	@Test
	public void anEmptySubmissionIsRefusedWithoutThrowing() {
		String view = controller.login(request, model);

		assertEquals("login", view);
		assertEquals(LoginController.WRONG_CREDENTIAL, model.get("error"));
		assertNull(request.getSession(false));
	}

	/** A mistyped password should not also clear the user name. The password is never echoed. */
	@Test
	public void theUserNameIsEchoedBackAndThePasswordIsNot() {
		submit("admin", "wrong", "/cache-view");

		assertEquals("admin", model.get("submittedUser"));
		assertEquals("/cache-view", model.get("next"));
		assertNull(model.get("password"));
	}

	// ---- an un-credentialed console --------------------------------------------------------

	/**
	 * A console with no user/password has nothing to sign into. Rendering a form that ignores what
	 * is typed into it is worse than not having one, and no branch here may create a session or
	 * check a credential - or a console with no credential grows a way to hold one.
	 */
	@Test
	public void withNoCredentialsConfiguredTheLoginPageIsNotAPage() {
		when(frontcacheService.isAuthEnabled()).thenReturn(false);

		assertEquals("redirect:/", controller.loginForm(request, model));
		assertEquals("redirect:/", submit("admin", "s3cret", "/cache-view"));
		assertNull(request.getSession(false));
	}

	@Test
	public void anAlreadySignedInBrowserIsSentAwayFromTheForm() {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE, "admin");
		request.setSession(session);

		assertEquals("redirect:/", controller.loginForm(request, model));
	}

	@Test
	public void theFormCarriesTheTargetThroughTheGet() {
		request.setParameter("next", "/guard-configs");

		assertEquals("login", controller.loginForm(request, model));
		assertEquals("/guard-configs", model.get("next"));
	}

	// ---- signing out ---------------------------------------------------------------------------

	@Test
	public void signingOutInvalidatesTheSession() {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ConsoleAuthFilter.SESSION_USER_ATTRIBUTE, "admin");
		request.setSession(session);

		assertEquals("redirect:/login", controller.logout(request));
		assertNull(request.getSession(false));
	}

	/** A logout with no session is not an error - a second click, or an expired session. */
	@Test
	public void signingOutWithoutASessionIsNotAnError() {
		assertEquals("redirect:/login", controller.logout(request));
	}

	// ---- where a login is allowed to send the browser ------------------------------------------

	/**
	 * The open-redirect test, and the one to keep when the rest of this file is refactored. Every
	 * entry below has a leading slash and is still an absolute URL to something.
	 */
	@Test
	public void anOffSiteTargetFallsBackToTheFrontPage() {
		String[] rejected = {
				null,
				"",
				"//evil.example",
				"//evil.example/path",
				"/\\evil.example",
				"/\\/evil.example",
				"http://evil.example",
				"https://evil.example/x",
				"https:/evil.example",
				"javascript:alert(1)",
				"cache-view",
				"/realtime\r\nSet-Cookie: a=b",
				"/realtime\nLocation: http://evil.example",
				"/{path}",
				"/login",
				"/login?next=/login" };

		for (String next : rejected)
			assertEquals("should not be a redirect target: " + next,
					"/", LoginController.safeTarget(next));
	}

	@Test
	public void aConsoleRelativeTargetSurvivesIntact() {
		String[] accepted = {
				"/",
				"/realtime",
				"/cache-view",
				"/cache-view?key=http%3A%2F%2Fa%2Fb&amount=50",
				"/save-config",
				"/guard-configs" };

		for (String next : accepted)
			assertEquals(next, LoginController.safeTarget(next));
	}

}
