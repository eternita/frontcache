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
import java.util.concurrent.atomic.AtomicBoolean;

import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The {@code front-cache.api-key} check, in one place.
 *
 * This is the management API's <b>only</b> access control - the thing that decides whether a caller
 * may invalidate a cache, dump its keys or read its metrics. There used to be a
 * {@code front-cache.management.port} check in front of it, which was never a boundary: it compared
 * the port the caller ADDRESSED, taken from the Host header. It is gone; restricting which connector
 * answers management traffic is the reverse proxy's job.
 *
 * Extracted because there was one implementation in {@code FrontCacheIOServlet} and a second endpoint
 * needed the same rule, and two implementations of one security check is one too many - the copy is
 * always the one that goes stale.
 *
 * <h2>The property was {@code front-cache.site-key} through 2.8</h2>
 *
 * Renamed, with NO fallback to the old name: a node whose {@code frontcache.properties} still says
 * {@code front-cache.site-key} reads no key at all and comes up with its management API, dashboard
 * stream and {@code /fc-metrics} OPEN. Rename the property when you upgrade. The credential on the
 * wire did not change, so callers - agent, console, peer nodes, Prometheus - need no coordination
 * with this; only the node's own config file does.
 *
 * <h2>{@code Authorization: Bearer <api-key>} is the way to send it</h2>
 *
 * One form, everywhere: the agent, the console, {@code FrontCacheClient}, node-to-node replication and
 * a Prometheus scrape all use it. It is a standard scheme with standard tooling, and it is the only
 * form <b>Prometheus can send at all</b> - a scrape_config offers basic_auth, authorization and
 * oauth2, and no way to set an arbitrary header - which is what settled the choice between the two.
 *
 * <h2>The legacy {@code x-frontcache-site-key} header, for one release</h2>
 *
 * That header was the Frontcache convention through 2.6 and is still accepted, deprecated, with a
 * warning naming the caller. It cannot simply be dropped: {@code frontcache-agent} is a published
 * artifact embedded in customer applications, the console is deployed separately from the fleet, and
 * nodes replicate to each other - so at any moment some caller is older than the node it is calling,
 * and a node that refused the old header would break all of them on upgrade rather than at a time
 * anyone chose. Same treatment {@code get-hystrix-configs} got in 2.7. Remove once every supported
 * caller is 2.7 or later.
 *
 * <h2>The SSE stream's check is stricter, on purpose</h2>
 *
 * {@code FCUtils.isAuthorizedApiKeyHeader} - used by {@code FcSampleSseServlet} - is a third spelling
 * of this, and it is NOT the same rule: it goes through {@code FrontCacheEngine.isNodeApiKey}, which
 * refuses a request when the node has no API key at all, where this returns true. Folding them
 * together would have to pick one of those, and both directions are a behaviour change on somebody's
 * node - the stream would open where it is closed today, or the management API would close where it is
 * open. Left as it is, and written down here, because the difference is invisible at both call sites.
 *
 * <h2>Read per request</h2>
 *
 * Rather than cached at servlet init, so that reloading a node's configuration changes the key that is
 * actually enforced. A property read per management request costs nothing measurable.
 */
public class ApiKey {

	static final String API_KEY_PROPERTY = "front-cache.api-key";

	private static final String BEARER = "Bearer ";

	private static final Logger logger = LoggerFactory.getLogger(ApiKey.class);

	/** Package-private so a test can assert the warning fires, and fires once. */
	static final AtomicBoolean legacyHeaderLogged = new AtomicBoolean(false);

	private ApiKey() { }

	/** @return the configured API key, or "" when the node has none */
	public static String configured()
	{
		String key = FCConfig.getProperty(API_KEY_PROPERTY, "");
		return (null == key) ? "" : key.trim();
	}

	/**
	 * @return true when this node has an API key at all. When it does not, the management API has no
	 *         access control at all.
	 */
	public static boolean isConfigured()
	{
		return configured().length() > 0;
	}

	/**
	 * @param request the inbound request
	 * @return true when the request carries this node's API key, or when the node has no API key
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

	/**
	 * The credential the caller presented, or null.
	 *
	 * Public because the SSE stream reads the same credential and applies a different rule to it (see
	 * the class javadoc) - so the two share how a caller SENDS the key while keeping their own answers
	 * about what it means.
	 *
	 * @param request the inbound request
	 * @return the API key the caller sent, by the current or the legacy route, or null
	 */
	public static String presented(HttpServletRequest request)
	{
		if (null == request)
			return null;

		String authorization = request.getHeader("Authorization");
		if (null != authorization && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length()))
		{
			String credentials = authorization.substring(BEARER.length()).trim();
			if (credentials.length() > 0)
				return credentials;
		}

		return legacyHeader(request);
	}

	/**
	 * The pre-2.7 header. Warned about once rather than per request - a fleet mid-upgrade would
	 * otherwise fill a log with it - and the message names the caller, because "something still uses
	 * the old header" is only actionable if you can tell what.
	 */
	@SuppressWarnings("deprecation")
	private static String legacyHeader(HttpServletRequest request)
	{
		String header = request.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY);
		if (null == header || 0 == header.trim().length())
			return null;

		if (legacyHeaderLogged.compareAndSet(false, true))
			logger.warn("A caller is authenticating with the deprecated " + FCHeaders.X_FRONTCACHE_SITE_KEY
					+ " header (User-Agent: " + request.getHeader("User-Agent") + ", from "
					+ request.getRemoteAddr() + "). Send 'Authorization: Bearer <api-key>' instead;"
					+ " the old header is accepted for one more release.");

		return header.trim();
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
