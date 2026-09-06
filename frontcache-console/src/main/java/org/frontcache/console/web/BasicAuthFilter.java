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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

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

/**
 * HTTP Basic authentication for the whole console - see docs/basic-auth-proposal.md.
 *
 * One user, no roles, credentials from conf/frontcache-console.conf. The console can invalidate the
 * cache of every node it knows and holds the api-key that authorizes it, so an unauthenticated
 * console is a credentialed proxy in front of an authenticated fleet.
 *
 * <h2>Why a filter rather than the container's own BASIC</h2>
 *
 * web.xml used to carry a commented-out {@code security-constraint} for this. Container-managed
 * BASIC resolves a ROLE through a container realm - a second credential file the project would have
 * to ship for the bundled Jetty launcher, and someone else's realm config for the war dropped into
 * a foreign container. A filter is the one mechanism that covers the Spring dispatcher, the
 * hand-declared {@link DashboardStreamProxyServlet} and the static resource handlers alike, in
 * every container, reading the file the operator already edits.
 *
 * <h2>No credentials means no check</h2>
 *
 * A config file written before this feature existed has neither key, and that console keeps
 * serving. {@code FrontcacheService} warns about it at WARN on every startup; deciding here to
 * refuse instead would take a monitoring dashboard offline during an upgrade nobody expected to be
 * breaking.
 *
 * <h2>What it does not do</h2>
 *
 * Basic sends base64(user:password) - encoding, not encryption - on every request, including the
 * long-lived SSE stream. This makes the console safe to leave running; it does not make it safe to
 * publish. Terminate TLS in front of it.
 */
public class BasicAuthFilter implements Filter {

	static final String REALM = "Frontcache Console";

	private static final String BASIC_PREFIX = "basic ";

	private FrontcacheService frontcacheService;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// The same lookup DashboardStreamProxyServlet does. The root context is built by
		// ContextLoaderListener, and the servlet spec initializes context listeners before filters,
		// so the bean is there.
		WebApplicationContext context =
				WebApplicationContextUtils.getRequiredWebApplicationContext(filterConfig.getServletContext());
		frontcacheService = context.getBean(FrontcacheService.class);
	}

	/** Test seam - lets the filter be built without a servlet context. */
	void setFrontcacheService(FrontcacheService frontcacheService) {
		this.frontcacheService = frontcacheService;
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		if (!frontcacheService.isAuthEnabled())
		{
			chain.doFilter(request, response);
			return;
		}

		if (isAuthorized(((HttpServletRequest) request).getHeader("Authorization")))
		{
			chain.doFilter(request, response);
			return;
		}

		HttpServletResponse httpResponse = (HttpServletResponse) response;

		// charset="UTF-8" is what makes a non-ASCII password behave the same in every browser.
		httpResponse.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");

		// No logging here, on purpose: the console answers whatever can route to it, and one scanner
		// would otherwise write a line per request. A SUCCESSFUL authentication is not logged either -
		// there is one user, so it carries no information.
		httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
	}

	/**
	 * A missing, malformed or wrong header is all one answer. Nothing distinguishes "no header" from
	 * "wrong password" to the caller.
	 */
	private boolean isAuthorized(String header) {

		if (null == header || header.length() <= BASIC_PREFIX.length())
			return false;

		// RFC 7617: the scheme token is case-insensitive.
		if (!BASIC_PREFIX.equalsIgnoreCase(header.substring(0, BASIC_PREFIX.length())))
			return false;

		String decoded;
		try {
			decoded = new String(Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length()).trim()),
					StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return false; // not base64 - a probe, not a login
		}

		// Split on the FIRST colon only: a password may contain colons, a username may not.
		int separator = decoded.indexOf(':');
		if (separator < 0)
			return false;

		return equalsConstantTime(decoded.substring(0, separator), frontcacheService.getUser())
				& equalsConstantTime(decoded.substring(separator + 1), frontcacheService.getPassword());
	}

	/**
	 * Constant-time over the bytes, so the number of correct leading characters is not readable from
	 * the response time. Note the caller uses {@code &} rather than {@code &&} - short-circuiting on
	 * the username would leak whether it was right.
	 */
	private boolean equalsConstantTime(String candidate, String expected) {
		return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

}
