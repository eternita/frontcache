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

import java.net.URL;

import org.junit.Test;

/**
 * {@link FCUtils#transformRedirectURL} - turning a Location the ORIGIN sent into one that names
 * this site.
 *
 * The origin sees {@code Host: origin.example.com}, because the client's Host cannot be forwarded
 * on a connection that goes to the origin, so every absolute URL it builds names the origin. This
 * method is what repairs that on the way back, and until docs/guard-redirect-url-host-fix.md it
 * repaired only the outer host - leaving the origin's name in any absolute URL inside the query
 * string, which is exactly where a login flow keeps its {@code return=}.
 */
public class TransformRedirectURLTest {

	private static final String PUBLIC_HOST = "www.example-site.com";
	private static final String ORIGIN_HOST = "origin.example-site.com";

	@Test
	public void theOuterHostBecomesThePublicOne() {
		assertEquals("https://" + PUBLIC_HOST + "/en/welcome.htm",
				FCUtils.transformRedirectURL("https://" + ORIGIN_HOST + "/en/welcome.htm", context()));
	}

	/**
	 * The port used to be appended unconditionally, so every app redirect on a normal deployment
	 * went out as https://www.example-site.com:443/... - valid, ugly, and in the address bar.
	 */
	@Test
	public void theDefaultPortIsNotAppended() {
		String location = FCUtils.transformRedirectURL("https://" + ORIGIN_HOST + "/en/welcome.htm", context());

		assertFalse(location, location.contains(":443"));

		RequestContext plain = context();
		plain.setPublicProtocol("http");
		assertFalse(FCUtils.transformRedirectURL("http://" + ORIGIN_HOST + "/x.htm", plain).contains(":80"));
	}

	@Test
	public void aNonDefaultPublicPortIsAppended() {
		RequestContext context = context();
		context.setFrontCacheHttpsPort("8443");

		assertEquals("https://" + PUBLIC_HOST + ":8443/en/welcome.htm",
				FCUtils.transformRedirectURL("https://" + ORIGIN_HOST + "/en/welcome.htm", context));
	}

	/**
	 * The defect: rewriting only the outer host let the origin's name reach browsers through the one
	 * part of the Location nobody was looking at.
	 */
	@Test
	public void anOriginUrlInsideTheQueryStringIsRebasedToo() {
		String location = FCUtils.transformRedirectURL(
				"https://" + ORIGIN_HOST + "/login.htm?return=https%3A%2F%2F" + ORIGIN_HOST + "%2Fen%2Fx.htm",
				context());

		assertFalse("the origin hostname must not survive anywhere in the Location: " + location,
				location.contains(ORIGIN_HOST));
		assertEquals("https://" + PUBLIC_HOST + "/login.htm?return=https%3A%2F%2F" + PUBLIC_HOST + "%2Fen%2Fx.htm",
				location);
	}

	/**
	 * Deliberately narrow. A parameter carrying a third-party URL - an OAuth callback, an image
	 * proxy source - is somebody else's address and must come back untouched.
	 */
	@Test
	public void aThirdPartyUrlInTheQueryStringIsLeftAlone() {
		String location = FCUtils.transformRedirectURL(
				"https://" + ORIGIN_HOST + "/oauth.htm?callback=https%3A%2F%2Fpartner.example.com%2Fdone",
				context());

		assertEquals("https://" + PUBLIC_HOST + "/oauth.htm?callback=https%3A%2F%2Fpartner.example.com%2Fdone",
				location);
	}

	@Test
	public void aRelativeLocationTakesThePublicSchemeAndHost() {
		assertEquals("https://" + PUBLIC_HOST + "/en/welcome.htm",
				FCUtils.transformRedirectURL("/en/welcome.htm", context()));
	}

	/**
	 * With no public host configured the arriving Host is used, exactly as before.
	 */
	@Test
	public void withNoPublicHostTheArrivingHostIsUsed() {
		RequestContext context = context();
		context.setPublicHost(null);

		assertEquals("https://cdn.example-site.com/en/welcome.htm",
				FCUtils.transformRedirectURL("https://" + ORIGIN_HOST + "/en/welcome.htm", context));
	}

	/**
	 * A request context as the engine builds one: the Host that arrived is NOT the public host, and
	 * not the origin's either - that is the ordinary case on an edge.
	 */
	private static RequestContext context() {
		RequestContext context = new RequestContext();
		context.setFrontCacheHost("cdn.example-site.com");
		context.setFrontCacheProtocol("http");        // the internal leg, behind TLS termination
		context.setPublicHost(PUBLIC_HOST);
		context.setPublicProtocol("https");
		context.setFrontCacheHttpPort("80");
		context.setFrontCacheHttpsPort("443");

		try {
			context.setOriginURL(new URL("http://" + ORIGIN_HOST));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}

		return context;
	}
}
