/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.metrics;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.frontcache.FCConfig;
import org.frontcache.hystrix.FcTestSupport;
import org.junit.After;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The gate on {@code /fc-metrics}.
 *
 * A Prometheus scrape endpoint is unauthenticated by convention, so the connector it answers on is the
 * access control. Getting this wrong publishes traffic volumes, error rates and circuit state on the
 * public port - which is why proposal section 7.5 says not to skip it, and why it has its own test rather
 * than being assumed from the fact that the code reads the same property as the metrics stream.
 */
public class FcMetricsServletTest {

	private static final String MANAGEMENT_PORT_KEY = "front-cache.management.port";

	@After
	public void tearDown()
	{
		FcMetricsExport.stop();
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FCConfig.setProperty(MANAGEMENT_PORT_KEY, "");
		FcTestSupport.resetAll();
	}

	private static HttpServletRequest requestOnPort(int port)
	{
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		when(request.getServerPort()).thenReturn(port);
		return request;
	}

	@Test
	public void refusedOnANonManagementPort() throws Exception
	{
		FCConfig.setProperty(MANAGEMENT_PORT_KEY, "8081");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		new FcMetricsServlet().service(requestOnPort(80), response);

		verify(response).sendError(anyInt(), contains("management port"));
		verify(response, never()).getWriter();
	}

	@Test
	public void servedOnTheManagementPort() throws Exception
	{
		FCConfig.setProperty(MANAGEMENT_PORT_KEY, "8081");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		StringWriter body = new StringWriter();
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(body));

		new FcMetricsServlet().service(requestOnPort(8081), response);

		verify(response).setContentType(contains("version=0.0.4"));
		verify(response, never()).sendError(anyInt(), anyString());
	}

	/**
	 * 404, not an empty 200. A scraper receiving nothing looks like a healthy target with no metrics,
	 * which is indistinguishable from the export being misconfigured.
	 */
	@Test
	public void notFoundWhenExportIsDisabled() throws Exception
	{
		FCConfig.setProperty(MANAGEMENT_PORT_KEY, "");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "none");
		FcMetricsExport.start();

		HttpServletResponse response = mock(HttpServletResponse.class);
		new FcMetricsServlet().service(requestOnPort(80), response);

		verify(response).sendError(anyInt(), contains("not enabled"));
	}

	/** No management port configured keeps the historical behaviour: reachable on every connector. */
	@Test
	public void unrestrictedWhenNoManagementPortIsSet() throws Exception
	{
		FCConfig.setProperty(MANAGEMENT_PORT_KEY, "");
		FCConfig.setProperty(FcMetricsExport.EXPORT_KEY, "prometheus");
		FcMetricsExport.start();

		StringWriter body = new StringWriter();
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(body));

		new FcMetricsServlet().service(requestOnPort(12345), response);

		verify(response, never()).sendError(anyInt(), anyString());
	}
}
