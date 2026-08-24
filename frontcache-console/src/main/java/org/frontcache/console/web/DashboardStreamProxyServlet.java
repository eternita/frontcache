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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.frontcache.console.service.FrontcacheService;
import org.frontcache.core.FCHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Relays an edge's dashboard SSE stream to the browser, because the console is served from a different
 * origin than the node it monitors and {@code EventSource} has no CORS story worth relying on.
 *
 * Replaces the Netflix {@code com.netflix.hystrix.dashboard.stream.ProxyStreamServlet} that the console
 * carried since 1.x, along with the rest of the vendored dashboard.
 *
 * <h2>The edge allowlist</h2>
 *
 * The Netflix original proxied a GET to <em>whatever URL the caller put in {@code origin}</em>, forwarded
 * every other query parameter to it, and attached the console's {@code front-cache.site-key} on the way -
 * so anyone who could reach the console could aim it at an arbitrary internal host and be handed the
 * shared secret in the request headers. That made sense for a dashboard whose whole purpose was pointing
 * at ad-hoc streams; it does not for a console with a configured edge list.
 *
 * So {@code origin} must name an edge the console already knows ({@code frontcache.console.urls}, plus
 * anything added at runtime through the Edges page). Nothing else is contacted, and no other query
 * parameter is passed on. This costs nothing operationally - the page only ever asks for edges the
 * console itself listed.
 */
public class DashboardStreamProxyServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(DashboardStreamProxyServlet.class);

	/**
	 * The path the node serves the stream on. Appended to an allowlisted edge URL - never taken from the
	 * request, so the browser cannot choose what is fetched from the edge.
	 *
	 * Deliberately still the pre-2.7 path, not {@code /fc-dashboard.stream}: the console is deployed
	 * separately from the nodes it monitors, so it has to work against a mixed fleet, and only the old
	 * path exists on a 2.6 node. 2.7+ serves both. Switch this when the oldest supported node is 2.7 -
	 * see docs/hystrix-to-resilience4j-proposal.md section 8.2.
	 */
	static final String STREAM_PATH = "hystrix.stream";

	@Autowired
	private FrontcacheService frontcacheService;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(getServletContext());
		AutowireCapableBeanFactory beanFactory = context.getAutowireCapableBeanFactory();
		beanFactory.autowireBean(this);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		String origin = request.getParameter("origin");
		if (null == origin) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Required parameter 'origin' missing");
			return;
		}

		String streamUrl = resolveStreamUrl(origin, frontcacheService.getFrontCacheAgentURLs());
		if (null == streamUrl) {
			logger.warn("Refusing to proxy '{}' - not one of the console's edges", origin);
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Unknown edge");
			return;
		}

		proxy(streamUrl, frontcacheService.getSiteKey(), response);
	}

	/**
	 * @return the URL of the dashboard stream on that edge, or null if {@code origin} is not an edge this
	 *         console knows.
	 */
	static String resolveStreamUrl(String origin, Set<String> edgeUrls) {

		String requested = normalize(origin);

		for (String edgeUrl : edgeUrls)
			if (normalize(edgeUrl).equals(requested))
				return normalize(edgeUrl) + STREAM_PATH;

		return null;
	}

	/** Edge URLs are configured by hand, so tolerate a missing or doubled trailing slash. */
	private static String normalize(String url) {

		String normalized = url.trim();

		// the page asks for "<edge>/hystrix.stream"; compare on the edge itself
		if (normalized.endsWith(STREAM_PATH))
			normalized = normalized.substring(0, normalized.length() - STREAM_PATH.length());

		while (normalized.endsWith("/"))
			normalized = normalized.substring(0, normalized.length() - 1);

		return normalized + "/";
	}

	private void proxy(String streamUrl, String siteKey, HttpServletResponse response) throws IOException {

		HttpGet httpGet = new HttpGet(streamUrl);
		InputStream is = null;

		if (null != siteKey)
			httpGet.addHeader(FCHeaders.X_FRONTCACHE_SITE_KEY, siteKey);

		try {
			HttpResponse upstream = Connections.httpClient.execute(httpGet);
			int statusCode = upstream.getStatusLine().getStatusCode();

			if (HttpStatus.SC_OK != statusCode) {
				// Propagate the refusal instead of swallowing it: an upstream 401/503 reaching the browser
				// as an empty 200 with nothing in the log is exactly where an operator looks first when the
				// dashboard is blank. The node answers 401 for a missing or wrong site key
				// (docs/hystrix-to-resilience4j-proposal.md section 14.6), and that has to be visible.
				logger.warn("Upstream {} returned {} - propagating to the client", streamUrl, statusCode);
				response.sendError(statusCode, "Metrics stream returned " + statusCode);
				return;
			}

			response.setContentType("text/event-stream");
			response.setCharacterEncoding("UTF-8");
			response.setHeader("Cache-Control", "no-cache, no-store");
			// the stream is a relay of SSE frames, and a proxy buffering it would defeat the point
			response.setHeader("X-Accel-Buffering", "no");

			is = upstream.getEntity().getContent();
			relay(is, response.getOutputStream());

		} catch (Exception e) {
			logger.error("Error proxying " + streamUrl, e);

			// If we never got as far as writing a frame - the edge is down, DNS fails, the connection is
			// refused - the browser would otherwise be handed an empty 200 and show a blank panel with
			// nothing anywhere to say why. Same reasoning as the non-200 branch above.
			if (!response.isCommitted())
				response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Could not read the metrics stream");
		} finally {
			// abort() MUST come before close(): the entity is an endless stream, so closing it first blocks
			try { httpGet.abort(); } catch (Exception e) { logger.debug("failed aborting proxy connection", e); }
			if (null != is)
				try { is.close(); } catch (Exception e) { /* already closed by abort() */ }
		}
	}

	/** Copies SSE frames through, flushing on each line feed so the browser sees them as they arrive. */
	private void relay(InputStream is, OutputStream os) throws IOException {

		int b;
		while (-1 != (b = is.read())) {
			try {
				os.write(b);
				if ('\n' == b)
					os.flush();
			} catch (Exception e) {
				String exName = e.getClass().getSimpleName();
				if (exName.equalsIgnoreCase("ClientAbortException") || exName.equals("EofException")) {
					// the operator closed the dashboard tab - the expected way one of these ends
					logger.debug("Connection closed by client ({}). Will stop proxying ...", exName);
					return;
				}
				throw new RuntimeException(e);
			}
		}
	}

	private static class Connections {

		private static final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();

		private static final HttpClient httpClient;

		static {
			// one long-lived connection per dashboard viewer per edge, so the pool is sized by viewers
			connectionManager.setDefaultMaxPerRoute(400);
			connectionManager.setMaxTotal(400);

			// The node emits a frame every 500 ms, so 10 s of silence means the stream is wedged, not idle.
			// Letting the read time out ends the relay and the browser's EventSource reconnects; an
			// infinite socket timeout would instead park this container thread for the life of the process.
			RequestConfig requestConfig = RequestConfig.custom()
					.setConnectTimeout(5000)
					.setConnectionRequestTimeout(5000)
					.setSocketTimeout(10000)
					.build();

			httpClient = HttpClients.custom()
					.setConnectionManager(connectionManager)
					.setDefaultRequestConfig(requestConfig)
					.build();
		}
	}

}
