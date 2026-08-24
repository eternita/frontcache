/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frontcache.core.ManagementPort;
import org.frontcache.hystrix.stream.FrontcacheHystrixMetricsStreamServlet;

/**
 * Restricts the metrics stream to {@code front-cache.management.port}.
 *
 * The gate itself lives in {@link ManagementPort}, shared with the Prometheus scrape endpoint - see that
 * class for why it is not inlined here any more.
 */
@SuppressWarnings("serial")
public class HystrixStreamSecurityWrapperServlet extends FrontcacheHystrixMetricsStreamServlet {

	private static final String WHAT = "/hystrix.stream";

	public HystrixStreamSecurityWrapperServlet() {
		super();
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (!ManagementPort.isAllowed(request, WHAT))
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
