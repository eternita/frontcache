/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.util.concurrent.atomic.AtomicBoolean;

import org.frontcache.FCConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The {@code front-cache.management.port} check, in one place.
 *
 * Management endpoints - the metrics stream and the Prometheus scrape - expose traffic volumes, error
 * rates and URL-shaped command names, so they belong on an internal connector rather than the public
 * one. This used to be inlined in {@code FcDashboardStreamSecurityServlet}; it was extracted when a
 * second endpoint needed the same rule, because two implementations of one check is one too many and
 * the copy is always the one that goes stale.
 *
 * <h2>This is a routing check, NOT a security boundary</h2>
 *
 * It compares against {@code request.getServerPort()}, which the Servlet specification derives from the
 * <b>Host header</b> - so it reports the port the CALLER ADDRESSED, not the socket the connection
 * arrived on. A client that can reach any connector can send {@code Host: whatever:443} and satisfy it.
 *
 * That is deliberate rather than an oversight to be swapped for {@code getLocalPort()}. The documented
 * topology (README-INSTALL section 5) terminates TLS upstream and proxies to {@code 127.0.0.1:9080}
 * with {@code X-Forwarded-*} forwarded, so the local socket port is 9080 while the port the operator
 * means - and configures here - is 443. Reading the socket would lock those deployments out of their
 * own management API on upgrade, to gain a boundary this property was never able to be.
 *
 * <b>{@link SiteKey} is the access control.</b> This decides which of a node's connectors management
 * traffic is answered on; the site key decides who may send it. A node with a management port and no
 * site key has nothing enforcing the latter, and {@link #isAllowed} says so once at startup.
 *
 * When the property is unset the check is open, and also says so once. That is the historical default
 * and changing it would lock operators out of their own dashboards on upgrade - but it is worth a
 * warning, since an open management endpoint on a public connector is not what most people intend.
 */
public class ManagementPort {

	private static final String MANAGEMENT_PORT_KEY = "front-cache.management.port";

	private static final Logger logger = LoggerFactory.getLogger(ManagementPort.class);

	private static final AtomicBoolean unrestrictedLogged = new AtomicBoolean(false);

	private static final AtomicBoolean noSiteKeyLogged = new AtomicBoolean(false);

	private ManagementPort() { }

	/**
	 * @return the configured management port, or -1 when unset or unparseable
	 */
	public static int configuredPort()
	{
		String configured = FCConfig.getProperty(MANAGEMENT_PORT_KEY);
		if (null == configured || 0 == configured.trim().length())
			return -1;

		try {
			return Integer.parseInt(configured.trim());
		} catch (NumberFormatException e) {
			logger.error("Can't read " + MANAGEMENT_PORT_KEY + "=" + configured
					+ " - management endpoints are NOT restricted to a specific port");
			return -1;
		}
	}

	/**
	 * @param request the inbound request
	 * @param what human-readable endpoint name, for the log line
	 * @return true when this request arrived on the management port, or no port is configured
	 */
	public static boolean isAllowed(HttpServletRequest request, String what)
	{
		int managementPort = configuredPort();

		warnIfNothingIsEnforcingAccess(managementPort, what);

		if (-1 == managementPort)
		{
			if (unrestrictedLogged.compareAndSet(false, true))
				logger.warn(MANAGEMENT_PORT_KEY + " is not set - management endpoints (" + what
						+ ") are reachable on every connector");
			return true;
		}

		if (managementPort == request.getServerPort())
			return true;

		logger.info("Refusing " + what + ": management endpoint addressed on port " + request.getServerPort()
				+ ", " + MANAGEMENT_PORT_KEY + "=" + managementPort);
		return false;
	}

	/**
	 * The case worth a warning: a node that has configured a management port and nothing else. The port
	 * check reads a client-supplied header (see the class javadoc), so on its own it stops nobody -
	 * and an operator who set it has clearly INTENDED to restrict this traffic, which makes a missing
	 * site key much more likely to be an oversight than a decision.
	 */
	private static void warnIfNothingIsEnforcingAccess(int managementPort, String what)
	{
		if (-1 == managementPort || SiteKey.isConfigured())
			return;

		if (noSiteKeyLogged.compareAndSet(false, true))
			logger.warn(MANAGEMENT_PORT_KEY + "=" + managementPort + " is set but " + SiteKey.SITE_KEY_PROPERTY
					+ " is not. The port check compares the Host header, which a caller controls, so it is"
					+ " not an access control on its own - set a site key to restrict " + what + ".");
	}
}
