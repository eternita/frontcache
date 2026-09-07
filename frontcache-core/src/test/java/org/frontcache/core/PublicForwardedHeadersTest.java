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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.frontcache.FCConfig;
import org.frontcache.FrontCacheEngine;
import org.junit.After;
import org.junit.Test;

/**
 * {@link FCUtils#addPublicForwardedHeaders} - telling the origin what the visitor typed.
 *
 * The origin cannot work it out: buildRequestHeaders has to drop the client's Host, because the
 * connection goes to the origin, so the app sees {@code Host: origin.example.com} and builds
 * absolute URLs naming it. See docs/archive/guard-redirect-url-host-fix.md §3.
 *
 * Off by default, and that default is the thing most worth pinning: switching it on changes every
 * absolute URL an app with a forwarded-headers filter emits.
 */
public class PublicForwardedHeadersTest {

	private static final String PUBLIC_HOST = "www.example-site.com";

	@After
	public void resetProperty() {
		FCConfig.setProperty(FrontCacheEngine.FORWARD_PUBLIC_HOST_KEY, "false");
	}

	@Test
	public void nothingIsAddedByDefault() {
		Map<String, List<String>> headers = new HashMap<String, List<String>>();

		FCUtils.addPublicForwardedHeaders(headers, context(PUBLIC_HOST));

		assertNull(headers.get(FCHeaders.X_FORWARDED_HOST));
		assertNull(headers.get(FCHeaders.X_FORWARDED_PROTO));
	}

	@Test
	public void bothHeadersAreAddedWhenTheOptionIsOn() {
		FCConfig.setProperty(FrontCacheEngine.FORWARD_PUBLIC_HOST_KEY, "true");

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		FCUtils.addPublicForwardedHeaders(headers, context(PUBLIC_HOST));

		assertEquals(PUBLIC_HOST, headers.get(FCHeaders.X_FORWARDED_HOST).get(0));
		assertEquals("https", headers.get(FCHeaders.X_FORWARDED_PROTO).get(0));
	}

	/**
	 * A node that cannot name its site has nothing true to say here, and a made-up value would be
	 * worse for the app than the origin host it already has.
	 */
	@Test
	public void nothingIsAddedWithoutAPublicHost() {
		FCConfig.setProperty(FrontCacheEngine.FORWARD_PUBLIC_HOST_KEY, "true");

		Map<String, List<String>> headers = new HashMap<String, List<String>>();
		FCUtils.addPublicForwardedHeaders(headers, context(null));

		assertFalse(headers.containsKey(FCHeaders.X_FORWARDED_HOST));
		assertFalse(headers.containsKey(FCHeaders.X_FORWARDED_PROTO));
	}

	private static RequestContext context(String publicHost) {
		RequestContext context = new RequestContext();
		context.setFrontCacheHost("cdn.example-site.com");
		context.setFrontCacheProtocol("http");
		context.setPublicHost(publicHost);
		context.setPublicProtocol("https");
		return context;
	}
}
