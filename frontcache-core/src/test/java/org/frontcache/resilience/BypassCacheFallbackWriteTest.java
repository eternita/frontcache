/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URL;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.http.client.HttpClient;
import org.frontcache.FCConfig;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Regression test for the double-written response on the bypass-cache (non-cacheable) path.
 *
 * getFallback() runs on a Hystrix thread while the request thread is about to write the response
 * itself. It used to write the fallback body straight to the servlet output stream, so the request
 * thread's own addResponseHeaders()/writeResponse() then ran on top of it: the context status code -
 * which defaults to 500 - was stamped over the fallback's status, and a late origin body was
 * appended to the fallback body. For any fallback smaller than the container's response buffer
 * (32KB in Jetty) the response was not yet committed, so the 500 actually reached the client.
 *
 * The fallback is now parked in the context and written once, by the request thread.
 */
public class BypassCacheFallbackWriteTest {

	@BeforeClass
	public static void setUpClass() throws Exception {
		// same fixture FRONTCACHE_HOME as FileBasedFallbackResolverTest - must be set before FCConfig
		// and the resolver are first touched
		URL confMarker = BypassCacheFallbackWriteTest.class.getClassLoader()
				.getResource("fallback-home/conf/fallbacks.conf");
		assertNotNull("fixture fallback-home/conf/fallbacks.conf not found on test classpath", confMarker);
		File fallbackHome = new File(confMarker.toURI()).getParentFile().getParentFile();
		System.setProperty(FCConfig.FRONT_CACHE_HOME_SYSTEM_KEY, fallbackHome.getAbsolutePath());

		// resolves against the fixture files already on disk; the client is never used for them
		FallbackResolverFactory.init(mock(HttpClient.class));
	}

	private RequestContext newContext(HttpServletResponse servletResponse) {
		RequestContext ctx = new RequestContext();
		ctx.setDomain("example.com"); // the Hystrix command group key
		ctx.setResponse(servletResponse);

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURL()).thenReturn(new StringBuffer("https://www.example.com/en/welcome.htm"));
		when(request.getMethod()).thenReturn("GET");
		when(request.getParameterMap()).thenReturn(Collections.<String, String[]>emptyMap());
		ctx.setRequest(request);

		return ctx;
	}

	/**
	 * The regression: the fallback must be parked for the request thread, never written from the
	 * Hystrix thread - so nothing touches the servlet response here.
	 */
	@Test
	public void fallbackIsParkedAndNotWrittenFromHystrixThread() throws Exception {

		HttpServletResponse servletResponse = mock(HttpServletResponse.class);
		RequestContext ctx = newContext(servletResponse);

		new FC_BypassCache(mock(HttpClient.class), ctx).getFallback();

		WebResponse parked = ctx.getFallbackWebResponse();
		assertNotNull("fallback must be parked in the context for the request thread", parked);
		assertTrue("parked fallback must carry a body", parked.getContent().length > 0);

		// the request thread is the only writer - this thread must not have touched the response
		verify(servletResponse, never()).getOutputStream();
		verify(servletResponse, never()).setStatus(anyInt());
		verify(servletResponse, never()).addHeader(anyString(), anyString());
	}

	/**
	 * The parked fallback carries its own status, which is what the client must end up with - not the
	 * context's default of 500 that used to be stamped over it.
	 */
	@Test
	public void parkedFallbackKeepsItsOwnStatus() throws Exception {

		HttpServletResponse servletResponse = mock(HttpServletResponse.class);
		RequestContext ctx = newContext(servletResponse);

		new FC_BypassCache(mock(HttpClient.class), ctx).getFallback();

		WebResponse parked = ctx.getFallbackWebResponse();
		assertNotNull(parked);
		// the resolver stamps fallbacks 503 - that status is what must reach the client, in place of
		// the 500 the context default used to overwrite it with
		assertEquals("fallback must keep its own status", 503, parked.getStatusCode());
		// what actually keeps a fallback out of the cache (CacheProcessorBase checks this flag)
		assertTrue("must be marked as a hystrix fallback so it is never cached", ctx.isFallbackUsed());
	}
}
