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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * {@code X-Frontcache-Client-Type} - the page's client type carried to its {@code <fc:include>}
 * fragments, so a fragment is not re-classified on evidence the page's request had and it does not.
 *
 * The whole feature is three rules, and each of them is silent when broken:
 * it is WRITTEN on the include fetch, DROPPED when a client sends it, and READ only from a trusted
 * peer on a request that arrived as an include.
 */
public class ClientTypePropagationTest {

	// ---- written on the include fetch --------------------------------------------------------

	@Test
	public void anIncludeFetchCarriesThePagesClientType() throws Exception {
		RequestContext context = new RequestContext();
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_BOT);
		context.setClientIP("203.0.113.9");
		context.setIncludeLevel("0");
		context.setRequestId("req-1");

		CapturingFcHttpClient client = new CapturingFcHttpClient();
		FCUtils.includeDynamicCallHttpClient("http://origin/inc.jsp",
				new HashMap<String, List<String>>(), client, context);

		assertEquals(Arrays.asList(FCHeaders.REQUEST_CLIENT_TYPE_BOT),
				client.headers.get(FCHeaders.X_FRONTCACHE_CLIENT_TYPE));
	}

	/**
	 * A header left over from an earlier hop must be replaced, not appended to: two values would
	 * make the receiving node's getHeader() answer whichever the container happened to keep.
	 */
	@Test
	public void anEarlierHopsValueIsReplacedNotAppended() throws Exception {
		RequestContext context = new RequestContext();
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_GUEST);
		context.setClientIP("203.0.113.9");
		context.setIncludeLevel("0");
		context.setRequestId("req-1");

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		headers.put(FCHeaders.X_FRONTCACHE_CLIENT_TYPE,
				new ArrayList<String>(Arrays.asList(FCHeaders.REQUEST_CLIENT_TYPE_BOT)));

		CapturingFcHttpClient client = new CapturingFcHttpClient();
		FCUtils.includeDynamicCallHttpClient("http://origin/inc.jsp", headers, client, context);

		assertEquals(Arrays.asList(FCHeaders.REQUEST_CLIENT_TYPE_GUEST),
				client.headers.get(FCHeaders.X_FRONTCACHE_CLIENT_TYPE));
	}

	// ---- dropped when a client sends it ------------------------------------------------------

	/**
	 * buildRequestHeaders copies everything it does not blacklist. If this header were copied, a
	 * client could send it and have Frontcache relay it onward with OUR address as the peer - which
	 * is precisely the peer the receiving node trusts.
	 */
	@Test
	public void anInboundClientTypeHeaderIsNotForwarded() {
		HttpServletRequest request = request(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, "203.0.113.9", true);

		Map<String, List<String>> headers = FCUtils.buildRequestHeaders(request);

		assertFalse("a client's own client-type claim must not be relayed: " + headers.keySet(),
				headers.containsKey(FCHeaders.X_FRONTCACHE_CLIENT_TYPE));
		assertTrue("everything else still travels", headers.containsKey("User-Agent"));
	}

	// ---- read only from a trusted peer, only on an include -----------------------------------

	@Test
	public void aTrustedPeersValueIsAdoptedOnAnInclude() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		assertEquals(FCHeaders.REQUEST_CLIENT_TYPE_BOT,
				FCUtils.propagatedClientType(context(FCHeaders.REQUEST_CLIENT_TYPE_BOT, "10.1.2.3", true)));
	}

	/**
	 * The one that matters. Untrusted peer = an arbitrary client, and its claim about its own client
	 * type is worth nothing: honouring it would let a scraper pick which branch of every
	 * expireTimeMap it is served from.
	 */
	@Test
	public void anUntrustedPeersValueIsIgnored() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		assertNull(FCUtils.propagatedClientType(context(FCHeaders.REQUEST_CLIENT_TYPE_BOT, "203.0.113.9", true)));
	}

	@Test
	public void withNoTrustedProxiesConfiguredNothingIsEverAdopted() {
		// the default, and therefore the default state of the whole feature: off, classify normally
		ClientIpResolver.install(new ClientIpResolver("", "X-Forwarded-For"));

		assertNull(FCUtils.propagatedClientType(context(FCHeaders.REQUEST_CLIENT_TYPE_BOT, "127.0.0.1", true)));
	}

	/**
	 * A top-level request is a client's, whoever relayed it. Only a request that arrived carrying a
	 * Frontcache request id can be a fragment of a page some node already classified.
	 */
	@Test
	public void aToplevelRequestNeverAdoptsIt() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		assertNull(FCUtils.propagatedClientType(context(FCHeaders.REQUEST_CLIENT_TYPE_BOT, "10.1.2.3", false)));
	}

	@Test
	public void anUnknownClientTypeIsIgnoredRatherThanStored() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		// 'browser' is the pre-2.9.0 spelling: it keys nothing in any expireTimeMap this node writes
		assertNull(FCUtils.propagatedClientType(context("browser", "10.1.2.3", true)));
		assertNull(FCUtils.propagatedClientType(context("", "10.1.2.3", true)));
	}

	@Test
	public void noHeaderMeansClassifyNormally() {
		ClientIpResolver.install(new ClientIpResolver("10.0.0.0/8", "X-Forwarded-For"));

		assertNull(FCUtils.propagatedClientType(context(null, "10.1.2.3", true)));
	}

	// ---- helpers -----------------------------------------------------------------------------

	private static RequestContext context(String clientTypeHeader, String remoteAddr, boolean fromFrontcache) {
		RequestContext context = new RequestContext();
		context.setRequest(request(clientTypeHeader, remoteAddr, false));

		if (fromFrontcache)
			context.setRequestFromFrontcache();

		return context;
	}

	private static HttpServletRequest request(final String clientTypeHeader, final String remoteAddr,
			final boolean withUserAgent) {

		return (HttpServletRequest) Proxy.newProxyInstance(
				ClientTypePropagationTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getHeader":
							return FCHeaders.X_FRONTCACHE_CLIENT_TYPE.equalsIgnoreCase((String) args[0])
									? clientTypeHeader : null;
						case "getHeaderNames":
						{
							List<String> names = new ArrayList<String>();
							if (null != clientTypeHeader)
								names.add(FCHeaders.X_FRONTCACHE_CLIENT_TYPE);
							if (withUserAgent)
								names.add("User-Agent");
							return Collections.enumeration(names);
						}
						case "getHeaders":
						{
							String name = (String) args[0];
							if (FCHeaders.X_FRONTCACHE_CLIENT_TYPE.equalsIgnoreCase(name) && null != clientTypeHeader)
								return Collections.enumeration(Arrays.asList(clientTypeHeader));
							if ("User-Agent".equalsIgnoreCase(name) && withUserAgent)
								return Collections.enumeration(Arrays.asList("Chrome/140"));
							return Collections.enumeration(Collections.<String>emptyList());
						}
						case "getRemoteAddr":
							return remoteAddr;
						case "getMethod":
							return "GET";
						default:
							return null;
						}
					}
				});
	}

	/** captures the headers an include fetch would have put on the wire */
	private static class CapturingFcHttpClient implements FcHttpClient {

		private Map<String, List<String>> headers;

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
			this.headers = requestHeaders;
			return new WebResponse(urlStr);
		}

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
			this.headers = requestHeaders;
			return new WebResponse(urlStr);
		}

		@Override
		public WebResponse fetch(String urlStr) {
			return new WebResponse(urlStr);
		}
	}

}
