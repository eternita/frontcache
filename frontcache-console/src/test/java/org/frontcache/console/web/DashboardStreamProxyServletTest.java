/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.console.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.frontcache.console.service.FrontcacheService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The dashboard stream relay, and specifically its edge allowlist.
 *
 * The Netflix {@code ProxyStreamServlet} this replaced would fetch whatever URL the caller named in
 * {@code origin} <em>and attach the console's API key to it</em>, which made anyone who could load the
 * console page able to point it at an internal host and be handed the shared secret. The replacement
 * only contacts edges the console already knows, and these tests are what pins that.
 */
public class DashboardStreamProxyServletTest {

	private DashboardStreamProxyServlet servlet;

	private FrontcacheService frontcacheService;

	private static Set<String> edges(String... urls) {
		return new LinkedHashSet<String>(Arrays.asList(urls));
	}

	@Before
	public void setUp() {
		servlet = new DashboardStreamProxyServlet();
		frontcacheService = Mockito.mock(FrontcacheService.class);
		ReflectionTestUtils.setField(servlet, "frontcacheService", frontcacheService);
	}

	// ---- the allowlist itself ------------------------------------------------------------------

	@Test
	public void aKnownEdgeResolvesToItsStreamUrl()
	{
		assertEquals("http://edge-1.example.com:8080/hystrix.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/",
						edges("http://edge-1.example.com:8080/")));
	}

	/**
	 * The page hands back the edge URL, but an operator types the edge list into
	 * frontcache-console.conf by hand - so a missing trailing slash must not read as a different host.
	 */
	@Test
	public void trailingSlashesDoNotDecideWhetherAnEdgeIsKnown()
	{
		Set<String> configured = edges("http://edge-1.example.com:8080");

		assertNotNull(DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080", configured));
		assertNotNull(DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/", configured));
		assertNotNull(DashboardStreamProxyServlet.resolveStreamUrl(" http://edge-1.example.com:8080/ ", configured));

		assertEquals("one slash, however it was written",
				"http://edge-1.example.com:8080/hystrix.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080//", configured));
	}

	/** Older bookmarks and the pre-2.7 page passed the full stream URL. Accept it, resolve the same. */
	@Test
	public void theFullStreamUrlAlsoResolves()
	{
		assertEquals("http://edge-1.example.com:8080/hystrix.stream",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/hystrix.stream",
						edges("http://edge-1.example.com:8080/")));
	}

	@Test
	public void anUnknownHostIsNotResolved()
	{
		Set<String> configured = edges("http://edge-1.example.com:8080/");

		assertNull("a host the console was never told about",
				DashboardStreamProxyServlet.resolveStreamUrl("http://169.254.169.254/latest/meta-data/", configured));
		assertNull("a different port on a known host is a different service",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:9999/", configured));
		assertNull("a prefix match is not a match",
				DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080.evil.test/", configured));
	}

	/**
	 * The path is the servlet's, not the caller's. An allowlist that matched on host alone and then used
	 * the caller's path would be a formality: the same known edge also serves /fc-admin, and the API key
	 * rides along on every proxied request.
	 */
	@Test
	public void aPathOnAKnownEdgeIsNotSmuggledThrough()
	{
		assertNull(DashboardStreamProxyServlet.resolveStreamUrl(
				"http://edge-1.example.com:8080/fc-admin?action=dump-keys",
				edges("http://edge-1.example.com:8080/")));
	}

	@Test
	public void noEdgesMeansNothingResolves()
	{
		assertNull(DashboardStreamProxyServlet.resolveStreamUrl("http://edge-1.example.com:8080/",
				Collections.<String>emptySet()));
	}

	// ---- what the servlet does with that -------------------------------------------------------

	@Test
	public void aMissingOriginIsABadRequest() throws Exception
	{
		when(frontcacheService.getFrontCacheAgentURLs()).thenReturn(edges("http://edge-1.example.com:8080/"));

		MockHttpServletResponse response = get(null);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getStatus());
	}

	/**
	 * The one that matters: an unknown origin is refused, and - the actual defect being closed - the
	 * API key is never even read, so it cannot travel to a host the console does not know.
	 */
	@Test
	public void anUnknownOriginIsRefusedAndNeverSeesTheApiKey() throws Exception
	{
		when(frontcacheService.getFrontCacheAgentURLs()).thenReturn(edges("http://edge-1.example.com:8080/"));

		MockHttpServletResponse response = get("http://169.254.169.254/latest/meta-data/");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
		verify(frontcacheService, never()).getApiKey();
	}

	private MockHttpServletResponse get(String origin) throws Exception
	{
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fc-dashboard-proxy.stream");
		if (null != origin)
			request.setParameter("origin", origin);

		MockHttpServletResponse response = new MockHttpServletResponse();
		servlet.doGet(request, response);

		return response;
	}

}
