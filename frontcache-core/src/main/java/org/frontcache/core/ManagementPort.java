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
 * The {@code front-cache.management.port} gate, in one place.
 *
 * Management endpoints - the metrics stream and the Prometheus scrape - expose traffic volumes, error
 * rates and URL-shaped command names, so they belong on an internal connector rather than the public
 * one. This used to be inlined in {@code HystrixStreamSecurityWrapperServlet}; it was extracted when a
 * second endpoint needed the same rule, because two implementations of one security check is one too
 * many and the copy is always the one that goes stale.
 *
 * When the property is unset the gate is open, and says so once at startup. That is the historical
 * default and changing it would lock operators out of their own dashboards on upgrade - but it is worth
 * a warning, since an open management endpoint on a public connector is not what most people intend.
 */
public class ManagementPort {

	private static final String MANAGEMENT_PORT_KEY = "front-cache.management.port";

	private static final Logger logger = LoggerFactory.getLogger(ManagementPort.class);

	private static final AtomicBoolean unrestrictedLogged = new AtomicBoolean(false);

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

		if (-1 == managementPort)
		{
			if (unrestrictedLogged.compareAndSet(false, true))
				logger.warn(MANAGEMENT_PORT_KEY + " is not set - management endpoints (" + what
						+ ") are reachable on every connector");
			return true;
		}

		if (managementPort == request.getServerPort())
			return true;

		logger.info("Refusing " + what + ": management endpoint accessed on port " + request.getServerPort()
				+ ", " + MANAGEMENT_PORT_KEY + "=" + managementPort);
		return false;
	}
}
