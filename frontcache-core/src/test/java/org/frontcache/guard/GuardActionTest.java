/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.FCConfig;
import org.frontcache.core.RequestContext;
import org.junit.Test;

/**
 * What a guard redirect PUTS IN the URL it hands to a browser.
 *
 * The bug this covers (docs/archive/guard-redirect-url-host-fix.md): ${url} was built from the Host header
 * as it arrived, so a node reached under its own name, an internal alias, the origin's name or a
 * bare IP baked that into a {@code return=} parameter and handed it to a visitor. On one edge, one
 * day, 18.9% of the login redirects carried a hostname or scheme the public site does not use.
 *
 * Two substitutions, tested separately because they broke separately:
 *
 * <ul>
 * <li>the HOST comes from front-cache.default-domain, not the request;</li>
 * <li>the SCHEME comes from the public protocol (X-Forwarded-Proto / front-cache.public-scheme),
 *     not from the connection this process accepted - which behind a TLS terminator is plaintext.</li>
 * </ul>
 *
 * And the fallback: with no public host configured, everything behaves exactly as it did before.
 */
public class GuardActionTest {

	private static final String PUBLIC_HOST = "www.example-site.com";

	// ---- the host substitution -------------------------------------------------------------

	/**
	 * The four hostnames a node is actually reachable under. Every one of them used to reach a
	 * browser; all four must now produce the public name.
	 */
	@Test
	public void urlNamesThePublicHostWhateverHostTheRequestArrivedUnder() {
		String[] arrivingHosts = {
				"origin.example-site.com",   // the reported bug - anything resolving the origin name here
				"direct.example-site.com",   // the internal alias the edge nginx uses for its FC upstream
				"fc-edge.example-site.com",    // the node's own name - reproduced live, see the doc's §2.3
				"203.0.113.7",        // a bare IP - 1,735 redirects in one day
		};

		for (String arriving : arrivingHosts)
		{
			RequestContext context = context(arriving, "https", PUBLIC_HOST, 443);

			assertEquals("arrived as " + arriving,
					"https://" + PUBLIC_HOST + "/en/ccc/va/visited-coins-analytics.htm?bd=2",
					GuardAction.requestURL(context));
		}
	}

	@Test
	public void hostPlaceholderIsThePublicHostToo() {
		GuardAction action = GuardAction.redirect(302, "https://" + PUBLIC_HOST + "/login.htm?from=${host}");

		assertEquals("https://" + PUBLIC_HOST + "/login.htm?from=" + PUBLIC_HOST,
				action.resolveTarget(context("origin.example-site.com", "https", PUBLIC_HOST, 443)));
	}

	/**
	 * The exact rule and the exact defect from the report.
	 */
	@Test
	public void encodedReturnUrlPointsAtTheEdgeNotTheOrigin() {
		GuardAction action = GuardAction.redirect(302, "https://" + PUBLIC_HOST + "/login.htm?return=${url:enc}");

		String target = action.resolveTarget(context("origin.example-site.com", "https", PUBLIC_HOST, 443));

		assertFalse("the origin hostname must not survive into the return parameter: " + target,
				target.contains("origin.example-site.com"));
		assertEquals("https://" + PUBLIC_HOST + "/login.htm?return=https%3A%2F%2Fwww.example-site.com"
				+ "%2Fen%2Fccc%2Fva%2Fvisited-coins-analytics.htm%3Fbd%3D2", target);
	}

	// ---- the scheme substitution -----------------------------------------------------------

	/**
	 * The larger half of the defect by volume: 10,948 of one day's return URLs said http:// for an
	 * https-only site, because nginx proxies port-80 traffic to a plaintext port and
	 * request.getScheme() reports what this process saw.
	 */
	@Test
	public void schemeIsThePublicOneNotTheConnectionsOwn() {
		RequestContext context = context(PUBLIC_HOST, "http", PUBLIC_HOST, 80);
		context.setPublicProtocol("https");

		assertEquals("https://" + PUBLIC_HOST + "/en/ccc/va/visited-coins-analytics.htm?bd=2",
				GuardAction.requestURL(context));
	}

	@Test
	public void schemeFallsBackToTheConnectionWhenNoPublicOneIsKnown() {
		RequestContext context = context(PUBLIC_HOST, "http", PUBLIC_HOST, 80);
		context.setPublicProtocol(null);

		assertTrue(GuardAction.requestURL(context).startsWith("http://" + PUBLIC_HOST + "/"));
	}

	// ---- the port ---------------------------------------------------------------------------

	@Test
	public void defaultPortIsOmitted() {
		assertFalse(GuardAction.requestURL(context(PUBLIC_HOST, "https", PUBLIC_HOST, 443)).contains(":443"));
		assertFalse(GuardAction.requestURL(context(PUBLIC_HOST, "http", PUBLIC_HOST, 80)).contains(":80"));
	}

	/**
	 * With a public host, the port is the one the SITE is published on
	 * (front-cache.https-port), not the internal port this request landed on. Those differ on every
	 * deployment behind a proxy - here the request arrives on 9443 and the site is on 8443.
	 */
	@Test
	public void nonDefaultPublicPortComesFromConfigNotFromTheRequest() {
		RequestContext context = context("direct.example-site.com", "https", PUBLIC_HOST, 9443);
		context.setFrontCacheHttpsPort("8443");

		assertEquals("https://" + PUBLIC_HOST + ":8443/en/ccc/va/visited-coins-analytics.htm?bd=2",
				GuardAction.requestURL(context));
	}

	// ---- the fallback, when the node names no public host -----------------------------------

	/**
	 * The three values FrontCacheEngine.getPublicDomain() refuses, all of which reach this code as a
	 * null public host. A default FRONTCACHE_HOME leaves front-cache.default-domain unset, so its
	 * value is FCConfig.DEFAULT_DOMAIN - the literal string "default-domain". Emitting
	 * "https://default-domain/..." at a browser would be a worse bug than the one being fixed.
	 */
	@Test
	public void withNoPublicHostTheArrivingHostIsEchoedAsBefore() {
		String[] noPublicHost = { null, "", "   " };

		for (String host : noPublicHost)
		{
			RequestContext context = context("origin.example-site.com", "https", host, 443);

			assertEquals("public host " + ((null == host) ? "null" : "'" + host + "'"),
					"https://origin.example-site.com/en/ccc/va/visited-coins-analytics.htm?bd=2",
					GuardAction.requestURL(context));
		}
	}

	@Test
	public void thePlaceholderDomainIsNeverEmitted() {
		// what FrontCacheEngine.getPublicDomain() maps onto null, restated here so the constant
		// cannot quietly become a hostname
		RequestContext context = context("origin.example-site.com", "https", null, 443);

		String url = GuardAction.requestURL(context);
		assertFalse(url, url.contains(FCConfig.DEFAULT_DOMAIN));
	}

	/**
	 * Falling back means falling back completely: the request's own port comes with the request's
	 * own host, since there is no configured identity to take a port from either.
	 */
	@Test
	public void withNoPublicHostTheRequestPortIsEchoedToo() {
		RequestContext context = context("localhost", "http", null, 8080);

		assertEquals("http://localhost:8080/en/ccc/va/visited-coins-analytics.htm?bd=2",
				GuardAction.requestURL(context));
	}

	// ---- placeholders that are not about identity -------------------------------------------

	@Test
	public void uriAndQueryPlaceholdersAreUntouched() {
		GuardAction action = GuardAction.redirect(302, "/login.htm?path=${uri}&q=${query}");

		assertEquals("/login.htm?path=/en/ccc/va/visited-coins-analytics.htm&q=?bd=2",
				action.resolveTarget(context("origin.example-site.com", "https", PUBLIC_HOST, 443)));
	}

	@Test
	public void aTargetWithoutPlaceholdersIsReturnedUnchanged() {
		GuardAction action = GuardAction.redirect(302, "https://" + PUBLIC_HOST + "/login.htm");

		assertEquals("https://" + PUBLIC_HOST + "/login.htm",
				action.resolveTarget(context("origin.example-site.com", "https", PUBLIC_HOST, 443)));
	}

	// -----------------------------------------------------------------------------------------

	/**
	 * @param arrivingHost   the Host header, i.e. what getFrontCacheHost() answers
	 * @param arrivingScheme the scheme of the connection this process accepted
	 * @param publicHost     front-cache.default-domain, or null for a node that names no site
	 * @param serverPort     the port the request landed on
	 */
	private static RequestContext context(String arrivingHost, String arrivingScheme, String publicHost,
			final int serverPort) {

		RequestContext context = new RequestContext();
		context.setFrontCacheHost(arrivingHost);
		context.setFrontCacheProtocol(arrivingScheme);
		context.setPublicHost((null != publicHost && 0 < publicHost.trim().length()) ? publicHost.trim() : null);
		context.setPublicProtocol(arrivingScheme);
		context.setFrontCacheHttpPort("80");
		context.setFrontCacheHttpsPort("443");
		context.setRequestURI("/en/ccc/va/visited-coins-analytics.htm");
		context.setRequestQueryString("?bd=2");

		context.setRequest((HttpServletRequest) Proxy.newProxyInstance(
				GuardActionTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName())
						{
						case "getServerPort":
							return Integer.valueOf(serverPort);
						case "getMethod":
							return "GET";
						default:
							return null;
						}
					}
				}));

		return context;
	}
}
