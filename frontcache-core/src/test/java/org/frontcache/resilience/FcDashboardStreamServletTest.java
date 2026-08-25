/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.frontcache.core.FCHeaders;
import org.frontcache.resilience.stream.FcDashboardStreamServlet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The metrics stream's refusal paths, and - since 2.7 - that an accepted connection does not keep the
 * container thread it arrived on.
 *
 * {@code testOnGetNoSiteKeyHeader} was {@code @Ignore}d because there was nothing to assert: the old
 * servlet's 503 branch was unreachable - {@code getDomainFromSiteKeyHeader()} returned the literal
 * "default-domain" for an unauthorized caller rather than nothing, so the request got a 200 and a stream
 * of empty frames. It now asserts the 401 from proposal section 14.6, and the two refusal reasons are
 * distinguishable: 401 for authorization, 503 for shutdown and the connection cap.
 */
public class FcDashboardStreamServletTest {

	/** front-cache.site-key in src/test/resources/fallback-home/conf/frontcache.properties */
	private static final String NODE_SITE_KEY = "test-site-key-fallback";

	@Mock HttpServletRequest mockReq;
	@Mock HttpServletResponse mockResp;

	FcDashboardStreamServlet servlet;

	@Before
	public void init() {
		MockitoAnnotations.initMocks(this);
		when(mockReq.getMethod()).thenReturn("GET");
	}

	@After
	public void tearDown() {
		servlet.destroy();
		FcDashboardStreamServlet.shutdown();
	}

	@Test
	public void shutdownServletShouldRejectRequests() throws ServletException, IOException {
		servlet = new FcDashboardStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		FcDashboardStreamServlet.shutdown();

		servlet.service(mockReq, mockResp);

		verify(mockResp).sendError(503, "Service has been shut down.");
	}

	@Test
	public void testOnGetNoSiteKeyHeader() throws ServletException, IOException {
		servlet = new FcDashboardStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		// no credential on the request at all
		servlet.service(mockReq, mockResp);

		verify(mockResp).sendError(401, "Missing or invalid site key - send 'Authorization: Bearer <site-key>'");
	}

	@Test
	public void testOnGetWrongSiteKeyHeader() throws ServletException, IOException {
		servlet = new FcDashboardStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		when(mockReq.getHeader("Authorization")).thenReturn("Bearer not-the-node-key");

		servlet.service(mockReq, mockResp);

		verify(mockResp).sendError(401, "Missing or invalid site key - send 'Authorization: Bearer <site-key>'");
	}

	/**
	 * The stream accepts the pre-2.9 header too, through the same {@code SiteKey.presented} the rest of
	 * the management surface uses - so an older console relaying this stream keeps working.
	 */
	@Test
	@SuppressWarnings("deprecation")
	public void theDeprecatedHeaderStillAuthenticatesTheStream() throws ServletException, IOException {
		servlet = new FcDashboardStreamServlet();
		try {
			servlet.init();
		} catch (ServletException ex) {
		}

		when(mockReq.getHeader(FCHeaders.X_FRONTCACHE_SITE_KEY)).thenReturn(NODE_SITE_KEY);
		when(mockReq.startAsync()).thenReturn(mock(AsyncContext.class));
		when(mockResp.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

		servlet.service(mockReq, mockResp);

		verify(mockResp, never()).sendError(anyInt(), anyString());
	}

	// ---- 2.7: async, and the concurrency cap that now depends on it -----------------------------

	/**
	 * The point of the async rewrite (proposal section 7.4): {@code doGet} accepts the connection and
	 * <b>returns</b>. Previously it sat in a poll loop for the life of the stream, so five idle
	 * dashboards meant five container threads doing nothing.
	 *
	 * The test passing at all is half the assertion - it would hang otherwise.
	 */
	@Test
	public void anAcceptedConnectionStartsAsyncAndGivesTheThreadBack() throws ServletException, IOException {
		servlet = new FcDashboardStreamServlet();
		servlet.init();

		AsyncContext async = accept();

		verify(mockReq).startAsync();
		// A metrics stream has no natural end. Leaving the container's default async timeout in place
		// would complete a perfectly healthy connection after 30 seconds.
		verify(async).setTimeout(0);
		verify(async, never()).complete();
		verify(mockResp, never()).sendError(anyInt(), anyString());
	}

	/**
	 * The cap survived the rewrite, and - the part that would have broken quietly - a finished
	 * connection gives its slot back. The decrement moved out of a {@code finally} around the request
	 * (which now returns immediately) into the subscriber's close. Had it been dropped, this endpoint
	 * would answer 503 forever after the fifth connection of the JVM's life.
	 */
	@Test
	public void theCapIsEnforcedAndAClosedConnectionFreesItsSlot() throws ServletException, IOException {
		servlet = new FcDashboardStreamServlet();
		servlet.init();

		// default resilience.config.stream.maxConcurrentConnections
		for (int i = 0; i < 5; i++)
			acceptOnAFreshRequest();

		HttpServletResponse refused = refuseOnAFreshRequest();
		verify(refused).sendError(503, "MaxConcurrentConnections reached: 5");

		// end every live connection the way a closing dashboard tab would
		FcDashboardStreamServlet.shutdown();
		servlet.init();

		HttpServletResponse accepted = mock(HttpServletResponse.class);
		when(accepted.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
		servlet.service(freshRequest(), accepted);

		verify(accepted, never()).sendError(anyInt(), anyString());
	}

	// ---- helpers ------------------------------------------------------------

	private AsyncContext accept() throws ServletException, IOException {
		AsyncContext async = mock(AsyncContext.class);
		ServletOutputStream out = mock(ServletOutputStream.class);

		when(mockReq.getHeader("Authorization")).thenReturn("Bearer " + NODE_SITE_KEY);
		when(mockReq.startAsync()).thenReturn(async);
		when(mockResp.getOutputStream()).thenReturn(out);

		servlet.service(mockReq, mockResp);

		verify(out).setWriteListener(any(WriteListener.class));

		return async;
	}

	private HttpServletRequest freshRequest() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("GET");
		when(request.getHeader("Authorization")).thenReturn("Bearer " + NODE_SITE_KEY);
		when(request.startAsync()).thenReturn(mock(AsyncContext.class));
		return request;
	}

	private void acceptOnAFreshRequest() throws ServletException, IOException {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

		servlet.service(freshRequest(), response);

		verify(response, never()).sendError(anyInt(), anyString());
	}

	private HttpServletResponse refuseOnAFreshRequest() throws ServletException, IOException {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

		servlet.service(freshRequest(), response);

		return response;
	}
}
