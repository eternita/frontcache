/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.frontcache.core.FCHeaders;
import org.frontcache.hystrix.stream.FrontcacheHystrixMetricsStreamServlet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The metrics stream's refusal paths.
 *
 * {@code testOnGetNoSiteKeyHeader} was {@code @Ignore}d because there was nothing to assert: the old
 * servlet's 503 branch was unreachable - {@code getDomainFromSiteKeyHeader()} returned the literal
 * "default-domain" for an unauthorized caller rather than nothing, so the request got a 200 and a stream
 * of empty frames. It now asserts the 401 from proposal section 14.6, and the two refusal reasons are
 * distinguishable: 401 for authorization, 503 for shutdown and the connection cap.
 */
public class HystrixMetricsStreamServletUnitTest {

	@Mock HttpServletRequest mockReq;
	@Mock HttpServletResponse mockResp;

	FrontcacheHystrixMetricsStreamServlet servlet;

	@Before
	public void init() {
		MockitoAnnotations.initMocks(this);
		when(mockReq.getMethod()).thenReturn("GET");
	}

	@After
	public void tearDown() {
		servlet.destroy();
		FrontcacheHystrixMetricsStreamServlet.shutdown();
	}

	@Test
	public void shutdownServletShouldRejectRequests() throws ServletException, IOException {
		servlet = new FrontcacheHystrixMetricsStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		FrontcacheHystrixMetricsStreamServlet.shutdown();

		servlet.service(mockReq, mockResp);

		verify(mockResp).sendError(503, "Service has been shut down.");
	}

	@Test
	public void testOnGetNoSiteKeyHeader() throws ServletException, IOException {
		servlet = new FrontcacheHystrixMetricsStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		// no x-frontcache-site-key on the request at all
		servlet.service(mockReq, mockResp);

		verify(mockResp).sendError(401, "Missing or invalid " + FCHeaders.X_FRONTCACHE_SITE_KEY);
	}

	@Test
	public void testOnGetWrongSiteKeyHeader() throws ServletException, IOException {
		servlet = new FrontcacheHystrixMetricsStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		when(mockReq.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY)).thenReturn("not-the-node-key");

		servlet.service(mockReq, mockResp);

		verify(mockResp).sendError(401, "Missing or invalid " + FCHeaders.X_FRONTCACHE_SITE_KEY);
	}
}
