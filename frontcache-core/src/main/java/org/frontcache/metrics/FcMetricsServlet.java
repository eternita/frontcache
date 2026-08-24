/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.metrics;

import java.io.IOException;

import org.frontcache.core.ManagementPort;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The Prometheus scrape endpoint, {@code /fc-metrics}.
 *
 * Behind the same {@code front-cache.management.port} gate as the dashboard stream, and for the same
 * reason: this exposes traffic volumes, error rates, circuit state and command names. A scrape endpoint
 * is unauthenticated by convention, so the connector it answers on IS the access control.
 *
 * Deliberately not {@code /fc-metrics.stream} - that name is reserved for the SSE stream's eventual
 * alias, and two endpoints one suffix apart, one a scrape and one an infinite stream, is a support
 * ticket waiting to happen.
 */
public class FcMetricsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final String WHAT = "/fc-metrics";

	/** Prometheus text exposition format 0.0.4 - what a scraper expects when it sends no Accept header. */
	private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (!ManagementPort.isAllowed(request, WHAT))
		{
			response.sendError(403, "Metrics are served on the management port only");
			return;
		}

		// Commands and pools register their meters on first execution, so a scrape is also when a
		// newly-active command gets picked up. Cheap: it only walks what is already registered.
		FcMetricsExport.refresh();

		String scrape = FcMetricsExport.scrape();
		if (null == scrape)
		{
			// 404 rather than an empty 200: a scraper that silently receives nothing looks like a healthy
			// target with no metrics, which is indistinguishable from the feature being misconfigured.
			response.sendError(404, "Prometheus export is not enabled - set "
					+ FcMetricsExport.EXPORT_KEY + "=prometheus (or both)");
			return;
		}

		response.setContentType(CONTENT_TYPE);
		response.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
		response.getWriter().write(scrape);
	}
}
