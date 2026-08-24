/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.core.ManagementPort;
import org.frontcache.resilience.stream.FcDashboardStreamServlet;

/**
 * Restricts the dashboard stream to {@code front-cache.management.port}.
 *
 * The gate itself lives in {@link ManagementPort}, shared with the Prometheus scrape endpoint - see that
 * class for why it is not inlined here any more.
 *
 * One servlet, two url-patterns: {@code /fc-dashboard.stream} and the pre-2.7 {@code /hystrix.stream}.
 * The old path is kept indefinitely rather than for one release - guard-rules.conf examples, the nginx
 * buffering guidance in README-INSTALL.md and any external Turbine all name it, and unlike a config file
 * an operator cannot grep their own reverse proxy from here.
 */
@SuppressWarnings("serial")
public class FcDashboardStreamSecurityServlet extends FcDashboardStreamServlet {

	public FcDashboardStreamSecurityServlet() {
		super();
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		// The endpoint name in the log line is the URI actually requested, since this servlet answers on
		// both the new and the legacy path and "which one did they call" is the useful half of the message.
		if (!ManagementPort.isAllowed(request, request.getRequestURI()))
		{
			// Historical behaviour: the message is written as the response body with a 200. Kept as-is -
			// it is a management endpoint on the wrong connector, and changing the shape of this reply is
			// not what this migration is for.
			response.getOutputStream().write(("Accessing Management URL with wrong port " + request.getServerPort()).getBytes());
			return;
		}

		super.doGet(request, response);
	}

}
