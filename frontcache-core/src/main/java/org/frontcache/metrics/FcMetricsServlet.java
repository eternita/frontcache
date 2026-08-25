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
import org.frontcache.core.SiteKey;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The Prometheus scrape endpoint, {@code /fc-metrics}.
 *
 * Behind the same {@code front-cache.management.port} check as the dashboard stream, and for the same
 * reason: this exposes traffic volumes, error rates, circuit state and command names - and, since the
 * JVM binders were added, this node's heap and CPU.
 *
 * <h2>The site key, not the port, is what protects it</h2>
 *
 * A scrape endpoint is unauthenticated by convention, and this one used to rely on that convention plus
 * the port check. That was not a boundary: the port check compares {@code getServerPort()}, which comes
 * from the Host header, so any caller able to reach any connector could ask for {@code Host: host:443}
 * and be served. The dashboard stream and the management API both require a site key underneath the
 * same port check; this endpoint was the one that did not, which made it the way in.
 *
 * So when the node has a {@code front-cache.site-key}, a scrape must carry it - as
 * {@code X-frontcache-site-key}, or as {@code Authorization: Bearer <key>} because Prometheus cannot
 * send an arbitrary header. A node with no site key configured is unchanged, and {@link ManagementPort}
 * warns about that at startup.
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

		if (!SiteKey.isAuthorized(request))
		{
			// The message names the fix, because the symptom - a scrape target that went red on
			// upgrade - does not. Prometheus sends this as authorization: { credentials: <key> }.
			response.setHeader("WWW-Authenticate", "Bearer realm=\"frontcache\"");
			response.sendError(401, "Metrics require this node's front-cache.site-key: send it as the "
					+ "X-frontcache-site-key header, or as Authorization: Bearer <site-key>");
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
