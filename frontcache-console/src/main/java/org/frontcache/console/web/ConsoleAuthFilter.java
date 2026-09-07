/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.frontcache.console.service.FrontcacheService;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Authentication for the whole console - see docs/basic-auth-proposal.md (the credential) and
 * docs/console-login-ui.md (the login screen).
 *
 * One user, no roles, credentials from conf/frontcache-console.conf. The console can invalidate the
 * cache of every node it knows and holds the api-key that authorizes it, so an unauthenticated
 * console is a credentialed proxy in front of an authenticated fleet.
 *
 * <h2>Two ways in, and only one of them is a browser's</h2>
 *
 * A browser signs in through the login page and carries a session cookie afterwards. Everything
 * else - {@code curl}, the console image's HEALTHCHECK, anything already automated against a 2.9.0
 * console - keeps presenting {@code Authorization: Basic} on every request, which is still
 * accepted and still verified by {@link ConsoleAuthenticator}. What changed with the login page is
 * the <b>refusal</b>, not the check: a {@code WWW-Authenticate} header sent to a page navigation
 * opens the browser's native credential dialog on top of the login page, which is the whole thing
 * the page exists to replace. So the shape of the refusal is chosen from {@code Accept} - see
 * {@link #refuse}.
 *
 * <h2>Why a filter rather than the container's own BASIC or form login</h2>
 *
 * web.xml used to carry a commented-out {@code security-constraint} for this. Container-managed
 * BASIC and container-managed FORM both resolve a ROLE through a container realm - a second
 * credential file the project would have to ship for the bundled Jetty launcher, and someone
 * else's realm config for the war dropped into a foreign container. A filter is the one mechanism
 * that covers the Spring dispatcher, the hand-declared {@link DashboardStreamProxyServlet} and the
 * static resource handlers alike, in every container, reading the file the operator already edits.
 *
 * <h2>No credentials means no check</h2>
 *
 * A config file written before this feature existed has neither key, and that console keeps
 * serving. {@code FrontcacheService} warns about it at WARN on every startup; deciding here to
 * refuse instead would take a monitoring dashboard offline during an upgrade nobody expected to be
 * breaking. On such a console {@code /login} is not a page either - {@code LoginController}
 * redirects away from it rather than rendering a form that ignores what is typed into it.
 *
 * <h2>What it does not do</h2>
 *
 * Basic sends base64(user:password) - encoding, not encryption - on every request. The session
 * cookie is a bearer token in the same sense. This makes the console safe to leave running; it does
 * not make it safe to publish. Terminate TLS in front of it.
 */
public class ConsoleAuthFilter implements Filter {

	static final String REALM = "Frontcache Console";

	/** Set on the session at login, read by this filter and by the navbar. Never the password. */
	public static final String SESSION_USER_ATTRIBUTE = "fc.user";

	/** The one page served without a credential, and where a refused browser is sent. */
	public static final String LOGIN_PATH = "/login";

	/**
	 * The only paths served without a credential - see docs/console-login-ui.md section 6.2.
	 *
	 * <b>Exact match, never a prefix, and this is the security-critical line of the change.</b> A
	 * prefix match on {@code /static/} would be shorter and would also expose every asset the
	 * console has; a prefix match on {@code /login} would match {@code /login/../realtime} on a
	 * container that does not normalize the way this one happens to. {@code WebMVCConfig} maps
	 * {@code addResourceHandler("/**")} to a classpath location, so ANY path no controller claims
	 * falls through to a resource handler - this set is the only thing standing in front of that.
	 *
	 * Adding an exclusion list at all reverses section 9.5 of the Basic-auth proposal ("an
	 * exclusion list is a thing to get wrong later"). It is forced: a login page that cannot load
	 * its own stylesheet is not a login page. The mitigation is the shape of the list, and the test
	 * that pins it asserts a NEGATIVE - that /static/js/console.js is not in it - because that is
	 * the assertion which fails when someone loosens the match.
	 */
	private static final Set<String> ANONYMOUS_PATHS = Set.of(
			LOGIN_PATH,
			"/static/css/fc-login.css",
			"/static/images/frontcache-logo.png",
			"/static/images/favicon.ico");

	private FrontcacheService frontcacheService;

	private ConsoleAuthenticator authenticator;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// The same lookup DashboardStreamProxyServlet does. The root context is built by
		// ContextLoaderListener, and the servlet spec initializes context listeners before filters,
		// so the bean is there.
		WebApplicationContext context =
				WebApplicationContextUtils.getRequiredWebApplicationContext(filterConfig.getServletContext());
		setFrontcacheService(context.getBean(FrontcacheService.class));
	}

	/** Test seam - lets the filter be built without a servlet context. */
	void setFrontcacheService(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
		this.authenticator = new ConsoleAuthenticator(frontcacheService);
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest) request;

		if (!frontcacheService.isAuthEnabled())
		{
			chain.doFilter(request, response);
			return;
		}

		// The login page and the three files it renders with. Before the session check, because
		// every first-time caller has no session.
		if (isAnonymous(requestPath(httpRequest)))
		{
			chain.doFilter(request, response);
			return;
		}

		if (hasAuthenticatedSession(httpRequest) || authenticator.isAuthorizedBasicHeader(httpRequest.getHeader("Authorization")))
		{
			chain.doFilter(request, response);
			return;
		}

		refuse(httpRequest, (HttpServletResponse) response);
	}

	/**
	 * <b>{@code getSession(false)}, always.</b> The console answers whatever can route to its port,
	 * and a filter that called {@code getSession(true)} would allocate a session object and a timer
	 * per scanner probe - with no per-IP limit to lean on and no identity to key one to. A session
	 * is created in exactly one place: the successful branch of {@code POST /login}.
	 */
	private boolean hasAuthenticatedSession(HttpServletRequest request) {

		HttpSession session = request.getSession(false);

		return null != session && null != session.getAttribute(SESSION_USER_ATTRIBUTE);
	}

	private boolean isAnonymous(String path) {

		// Belt and braces around the exact match: a path holding ".." is never a console URL, and
		// stating it here means the invariant survives someone reformatting the set above.
		return !path.contains("..") && ANONYMOUS_PATHS.contains(path);
	}

	/**
	 * The request path, context-relative, decoded and free of path parameters.
	 *
	 * {@code getServletPath()} + {@code getPathInfo()} is the spec's answer and the one the
	 * container has already normalized. The fallback covers the request for the context root,
	 * where a container may leave both empty.
	 */
	static String requestPath(HttpServletRequest request) {

		String servletPath = null == request.getServletPath() ? "" : request.getServletPath();
		String pathInfo = null == request.getPathInfo() ? "" : request.getPathInfo();

		String path = servletPath + pathInfo;

		if (path.isEmpty())
		{
			String uri = null == request.getRequestURI() ? "" : request.getRequestURI();
			String contextPath = null == request.getContextPath() ? "" : request.getContextPath();

			path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
		}

		// ";jsessionid=..." is a path parameter, not part of the path. getServletPath() strips it;
		// the fallback above does not, and a browser with cookies disabled is where it shows up.
		int semicolon = path.indexOf(';');
		if (semicolon > -1)
			path = path.substring(0, semicolon);

		return path;
	}

	/**
	 * Refuse - as a redirect to the login page for a browser, as a 401 with the Basic challenge for
	 * everything else. See docs/console-login-ui.md section 5.2.
	 *
	 * <b>{@code Accept} chooses the shape of the refusal and never authorizes anything.</b> Both
	 * misclassifications are visible immediately: a page navigation read as programmatic shows the
	 * old native dialog, and a script read as a browser gets a 302 to an HTML page. Neither grants
	 * access.
	 *
	 * No logging, on purpose, and none on success either: the console answers whatever can route to
	 * it, and one scanner would otherwise write a line per request. With one user, a successful
	 * authentication carries no information.
	 */
	private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {

		if (wantsHtml(request))
		{
			// A 302 to the login page must never be the cached answer for the page it replaced.
			response.setHeader("Cache-Control", "no-store");
			response.sendRedirect(request.getContextPath() + LOGIN_PATH + nextQuery(request));
			return;
		}

		// charset="UTF-8" is what makes a non-ASCII password behave the same in every browser.
		response.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
		response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
	}

	/** A page navigation, as opposed to curl, a fetch() asking for JSON or an EventSource. */
	private boolean wantsHtml(HttpServletRequest request) {

		String accept = request.getHeader("Accept");

		return null != accept && accept.toLowerCase(Locale.ROOT).contains("text/html");
	}

	/** "?next=<where they were going>", or "" when that is the console's own front page anyway. */
	private String nextQuery(HttpServletRequest request) {

		String path = requestPath(request);

		if (path.isEmpty() || "/".equals(path))
			return "";

		String query = request.getQueryString();
		String next = null == query || query.isEmpty() ? path : path + "?" + query;

		return "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
	}

}
