/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Test;

/**
 * {@link RequestContext#toString()}.
 *
 * All four command fallbacks put this in their error log line, which means it runs on EVERY request
 * exactly when the origin is failing and every request is taking the fallback path. It used to be
 * {@code super.toString()} - a dump of the whole map, so every entry's own toString(): the
 * HttpServletRequest, the HttpServletResponse, the open origin response, the full origin header map
 * - fed through a freshly compiled regex to squash newlines. The log line meant to diagnose an
 * outage was amplifying the load during one.
 *
 * It is now a fixed set of fields. These tests pin that it stays that way, and that the fields
 * anyone actually debugs with are still in it.
 */
public class RequestContextToStringTest {

	private static RequestContext context() {
		RequestContext context = new RequestContext();
		context.setRequestId("req-123");
		context.setRequestType(FCHeaders.COMPONENT_TOPLEVEL);
		context.setIncludeLevel("0");
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_BROWSER);
		context.setRequestURI("/en/page.htm");
		context.setRequestQueryString("?a=1&b=2");
		return context;
	}

	@Test
	public void carriesTheFieldsWorthDebuggingWith() {
		String rendered = context().toString();

		assertTrue(rendered, rendered.startsWith("RequestContext: "));
		for (String expected : new String[] {
				"req-123", FCHeaders.COMPONENT_TOPLEVEL, FCHeaders.REQUEST_CLIENT_TYPE_BROWSER,
				"/en/page.htm", "?a=1&b=2" })
			assertTrue("missing " + expected + " in: " + rendered, rendered.contains(expected));
	}

	/**
	 * The point of the change. The servlet request and response are in the map; dragging them through
	 * their own toString() is what made this expensive, and it also risks dumping header contents into
	 * an error log.
	 */
	@Test
	public void doesNotDumpTheRequestOrTheResponse() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.toString()).thenReturn("SERVLET-REQUEST-DUMP");
		when(response.toString()).thenReturn("SERVLET-RESPONSE-DUMP");

		RequestContext context = context();
		context.setRequest(request);
		context.setResponse(response);
		context.addOriginResponseHeader("Set-Cookie", "session=SECRET-COOKIE-VALUE");

		String rendered = context.toString();

		assertFalse("the servlet request must not be dumped: " + rendered,
				rendered.contains("SERVLET-REQUEST-DUMP"));
		assertFalse("the servlet response must not be dumped: " + rendered,
				rendered.contains("SERVLET-RESPONSE-DUMP"));
		assertFalse("origin response headers must not be dumped: " + rendered,
				rendered.contains("SECRET-COOKIE-VALUE"));
	}

	/** It goes into a single log line, so it must be one line - which is what the old regex was for. */
	@Test
	public void isAlwaysASingleLine() {
		RequestContext context = context();
		context.setRequestURI("/path/with\r\nembedded\nnewlines");

		String rendered = context.toString();

		assertEquals("no newlines may survive into the log line: " + rendered,
				-1, rendered.indexOf('\n'));
		assertEquals(-1, rendered.indexOf('\r'));
	}

	/** A crawler's absurd URL must not be able to dominate the log. */
	@Test
	public void cappsCallerSuppliedValues() {
		RequestContext context = context();
		context.setRequestURI("/" + "x".repeat(10000));
		context.setRequestQueryString("?" + "y".repeat(10000));

		String rendered = context.toString();

		assertTrue("a 20k URL must not produce a 20k log line, got " + rendered.length(),
				rendered.length() < 1000);
		assertTrue("and it should say it was clipped", rendered.contains("..."));
	}

	/** The fallback reason is the whole point of the line when a fallback fired. */
	@Test
	public void reportsTheFallbackReasonWhenThereIsOne() {
		RequestContext context = context();
		assertTrue(context.toString().contains("fallbackUsed=false"));

		context.setFallbackUsed("timeout");

		String rendered = context.toString();
		assertTrue(rendered, rendered.contains("fallbackUsed=true"));
		assertTrue(rendered, rendered.contains("timeout"));
	}

	/** A bare context (no request, nothing set) must render rather than throw. */
	@Test
	public void rendersAnEmptyContextWithoutThrowing() {
		String rendered = new RequestContext().toString();

		assertTrue(rendered, rendered.startsWith("RequestContext: "));
	}
}
