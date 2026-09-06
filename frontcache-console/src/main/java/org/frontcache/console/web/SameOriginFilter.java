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
import java.net.URI;
import java.util.Locale;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects a state-changing request that a browser made from another site - see
 * docs/basic-auth-proposal.md section 8.1.
 *
 * <h2>Why this ships with Basic auth rather than after it</h2>
 *
 * Basic-auth credentials are cached by the browser per origin and attached automatically, and they
 * are not cookies, so {@code SameSite} does not apply to them. Turning authentication on therefore
 * makes CSRF <b>reachable</b>: a page an authenticated operator has open in another tab can
 * auto-submit a form to /cache-invalidation and the browser will attach the credentials. Before
 * authentication existed the attacker could just call the console directly, so this cost nothing;
 * now it does.
 *
 * <h2>What it checks, and what that is worth</h2>
 *
 * A safe method (GET/HEAD/OPTIONS) passes untouched. Anything else must carry an {@code Origin} -
 * or failing that a {@code Referer} - that is either absent or this same host. <b>Absent is
 * allowed</b>: {@code curl} sends neither, and an operator's script has to keep working. So this
 * stops a browser being used as a confused deputy and stops nothing else - it is hardening, not an
 * authorization model. The authorization is {@link BasicAuthFilter}, which runs first.
 *
 * <h2>Host and port, not scheme</h2>
 *
 * A console behind a TLS-terminating reverse proxy sees {@code http} on the wire while the browser
 * sends {@code Origin: https://...}. Comparing schemes would reject every state-changing request on
 * exactly the deployments careful enough to put TLS in front. The host is what separates the
 * operator's origin from an attacker's, and it is taken from the {@code Host} header - the name the
 * browser actually used - rather than from {@code getServerName()}.
 */
public class SameOriginFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest) request;

		if (isSafeMethod(httpRequest.getMethod()) || isSameOrigin(httpRequest))
		{
			chain.doFilter(request, response);
			return;
		}

		((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN,
				"cross-origin request rejected");
	}

	/** The methods that do not change state, per RFC 9110. TRACE is not one of them here. */
	private boolean isSafeMethod(String method) {
		return "GET".equalsIgnoreCase(method)
				|| "HEAD".equalsIgnoreCase(method)
				|| "OPTIONS".equalsIgnoreCase(method);
	}

	private boolean isSameOrigin(HttpServletRequest request) {

		String origin = request.getHeader("Origin");
		if (null != origin)
			// "null" is what a sandboxed iframe and some redirect chains send. It is not this
			// origin, so it does not pass - and it must not fall through to Referer, which such a
			// request may still carry.
			return !"null".equals(origin) && matchesHost(origin, request);

		// No Origin: an older browser, or a same-origin GET-turned-POST redirect. Referer carries
		// the full URL, and its host is the part that matters.
		String referer = request.getHeader("Referer");
		if (null != referer)
			return matchesHost(referer, request);

		// Neither header: not a browser. Allowed - see the class comment.
		return true;
	}

	private boolean matchesHost(String url, HttpServletRequest request) {

		String hostHeader = request.getHeader("Host");
		if (null == hostHeader)
			return false; // HTTP/1.1 requires it; without it there is nothing to compare against

		try {
			URI uri = new URI(url);
			String host = uri.getHost();
			if (null == host)
				return false;

			return normalize(host, uri.getPort(), uri.getScheme()).equals(normalizeHostHeader(hostHeader, request));

		} catch (Exception e) {
			return false; // unparseable is not same-origin
		}
	}

	/** host:port, lowercased, with the scheme's default port filled in so ":443" and "" compare equal. */
	private String normalize(String host, int port, String scheme) {
		return host.toLowerCase(Locale.ROOT) + ":" + (port > -1 ? port : defaultPort(scheme));
	}

	private String normalizeHostHeader(String hostHeader, HttpServletRequest request) {

		String host = hostHeader.trim();
		int colon = host.lastIndexOf(':');

		// lastIndexOf, then a digits check: a bare IPv6 literal is full of colons ("[::1]"), and only
		// a trailing ":<digits>" outside the brackets is a port.
		if (colon > -1 && colon > host.lastIndexOf(']') && isNumeric(host.substring(colon + 1)))
			return host.substring(0, colon).toLowerCase(Locale.ROOT) + ":" + host.substring(colon + 1);

		// No port in Host: it is the default for the scheme the BROWSER used, which behind a
		// TLS-terminating proxy is X-Forwarded-Proto rather than what this connector sees.
		String scheme = request.getHeader("X-Forwarded-Proto");
		if (null == scheme)
			scheme = request.getScheme();

		return host.toLowerCase(Locale.ROOT) + ":" + defaultPort(scheme);
	}

	private int defaultPort(String scheme) {
		return "https".equalsIgnoreCase(scheme) ? 443 : 80;
	}

	private boolean isNumeric(String s) {
		if (s.isEmpty())
			return false;
		for (int i = 0; i < s.length(); i++)
			if (s.charAt(i) < '0' || s.charAt(i) > '9')
				return false;
		return true;
	}

}
