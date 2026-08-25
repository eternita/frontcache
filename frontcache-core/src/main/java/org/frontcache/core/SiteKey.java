/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.frontcache.FCConfig;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The {@code front-cache.site-key} check, in one place.
 *
 * This is the management API's actual access control - the thing that decides whether a caller may
 * invalidate a cache, dump its keys or read its metrics. {@link ManagementPort} is NOT that; it checks
 * the port the caller addressed, which comes from the Host header, and says so itself.
 *
 * Extracted for the reason ManagementPort was: there was one implementation in
 * {@code FrontCacheIOServlet} and a second endpoint needed the same rule, and two implementations of
 * one security check is one too many - the copy is always the one that goes stale.
 *
 * <h2>Two headers, one secret</h2>
 *
 * {@code X-frontcache-site-key} is the Frontcache convention: the agent, the console and
 * {@code FrontCacheClient} all send it, and it is hardcoded in frontcache-agent.
 *
 * {@code Authorization: Bearer <key>} is accepted for the same secret because <b>Prometheus cannot
 * send an arbitrary request header</b> - a scrape_config offers basic_auth, authorization and oauth2,
 * and nothing else. Without the Bearer form, requiring a site key on {@code /fc-metrics} would make
 * the endpoint unscrapable by the very tool it exists for, which is a worse answer than the hole it
 * closes.
 *
 * <h2>The SSE stream's check is stricter, on purpose</h2>
 *
 * {@code FCUtils.isAuthorizedSiteKeyHeader} - used by {@code FcSampleSseServlet} - is a third spelling
 * of this, and it is NOT the same rule: it goes through {@code FrontCacheEngine.isNodeSiteKey}, which
 * refuses a request when the node has no site key at all, where this returns true. Folding them
 * together would have to pick one of those, and both directions are a behaviour change on somebody's
 * node - the stream would open where it is closed today, or the management API would close where it is
 * open. Left as it is, and written down here, because the difference is invisible at both call sites.
 *
 * <h2>Read per request</h2>
 *
 * Rather than cached at servlet init, so that reloading a node's configuration changes the key that is
 * actually enforced. A property read per management request costs nothing measurable.
 */
public class SiteKey {

	static final String SITE_KEY_PROPERTY = "front-cache.site-key";

	private static final String BEARER = "Bearer ";

	private SiteKey() { }

	/** @return the configured site key, or "" when the node has none */
	public static String configured()
	{
		String key = FCConfig.getProperty(SITE_KEY_PROPERTY, "");
		return (null == key) ? "" : key.trim();
	}

	/**
	 * @return true when this node has a site key at all. When it does not, the management API has no
	 *         access control of its own - see {@link ManagementPort}, which is not one.
	 */
	public static boolean isConfigured()
	{
		return configured().length() > 0;
	}

	/**
	 * @param request the inbound request
	 * @return true when the request carries this node's site key, or when the node has no site key
	 *         configured (the historical default - an unconfigured node is open, and says so at
	 *         startup rather than refusing traffic on upgrade)
	 */
	public static boolean isAuthorized(HttpServletRequest request)
	{
		String expected = configured();
		if (0 == expected.length())
			return true;

		return matches(expected, presented(request));
	}

	/** The key the caller presented, by either accepted route, or null. */
	private static String presented(HttpServletRequest request)
	{
		if (null == request)
			return null;

		String header = request.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY);
		if (null != header && header.trim().length() > 0)
			return header.trim();

		String authorization = request.getHeader("Authorization");
		if (null != authorization && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length()))
		{
			String credentials = authorization.substring(BEARER.length()).trim();
			if (credentials.length() > 0)
				return credentials;
		}

		return null;
	}

	/**
	 * Constant-time comparison. The timing of a shared-secret check is not where anyone expects to lose
	 * one, but {@code String.equals} returns on the first differing byte and the cost of not doing this
	 * is a one-line import.
	 */
	private static boolean matches(String expected, String presented)
	{
		if (null == presented)
			return false;

		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				presented.getBytes(StandardCharsets.UTF_8));
	}

}
