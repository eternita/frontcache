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

import org.junit.Test;

/**
 * A cache key must equal the URL the request is actually made with.
 *
 * {@code buildRequestURI} re-serializes the query string on its way to the wire and drops any
 * parameter that has no value, so a URL keyed as written and fetched as normalised names two
 * different things. When that happened to {@code <fc:include>} the entry could never be read back:
 * the probe looked up {@code ?locale=zh&id=}, the fetch went out as {@code ?locale=zh}, the serving
 * node stored what it received, and the next probe missed again — 608 origin fetches per second on
 * a production node, all of them re-fetching fragments that were already cached and unexpired.
 *
 * The invariant these tests pin is a round trip: <b>canonicalizeURL(u) is what buildRequestURI(u)
 * will put on the wire</b>, for every u. Break that and the cache silently stops working, with no
 * error anywhere — only a load graph that will be blamed on something else.
 */
public class CanonicalizeURLTest {

	private static final String HOST = "https://www.example.com";

	/** The exact production case. */
	@Test
	public void anEmptyParameterIsDroppedFromTheKeyExactlyAsItIsFromTheFetch() {

		String asWritten = HOST + "/fc/recent-updates-add.htm?locale=zh&id=";

		assertEquals("the key must not carry a parameter the fetch will strip",
				HOST + "/fc/recent-updates-add.htm?locale=zh",
				FCUtils.canonicalizeURL(asWritten));
	}

	/**
	 * The invariant, stated directly: whatever the input, the canonical URL's path+query is what
	 * goes on the wire. Any future change to buildRequestURI's normalisation has to keep this true
	 * or reopen the hole.
	 */
	@Test
	public void theCanonicalUrlIsAlwaysWhatTheFetchWillRequest() {

		String[] urls = {
				HOST + "/fc/f.htm?locale=zh&id=",
				HOST + "/fc/f.htm?locale=zh",
				HOST + "/fc/f.htm?a=&b=&c=1",
				HOST + "/fc/f.htm?only=",
				HOST + "/fc/f.htm",
				HOST + "/fc/f.htm?q=hello+world",
		};

		for (String url : urls)
			assertEquals("canonical form must equal what buildRequestURI puts on the wire, for " + url,
					HOST + FCUtils.buildRequestURI(url),
					FCUtils.canonicalizeURL(url));
	}

	/**
	 * Idempotent - canonicalizing twice must not change anything, or a path that canonicalizes
	 * defensively would key differently from one that does it once.
	 */
	@Test
	public void canonicalizingIsIdempotent() {

		String[] urls = {
				HOST + "/fc/f.htm?locale=zh&id=",
				HOST + "/fc/f.htm?a=&b=&c=1",
				HOST + "/fc/f.htm",
		};

		for (String url : urls) {
			String once = FCUtils.canonicalizeURL(url);
			assertEquals("not idempotent for " + url, once, FCUtils.canonicalizeURL(once));
		}
	}

	/** A URL with nothing to normalise must come back byte-identical. */
	@Test
	public void anAlreadyCanonicalUrlIsUnchanged() {

		assertEquals(HOST + "/fc/f.htm?locale=zh", FCUtils.canonicalizeURL(HOST + "/fc/f.htm?locale=zh"));
		assertEquals(HOST + "/fc/f.htm", FCUtils.canonicalizeURL(HOST + "/fc/f.htm"));
	}

	/** The scheme, host and port are carried through untouched - only path and query are normalised. */
	@Test
	public void schemeHostAndPortAreNotTouched() {

		assertEquals("http://origin.example.com:8080/fc/f.htm?a=1",
				FCUtils.canonicalizeURL("http://origin.example.com:8080/fc/f.htm?a=1&empty="));
	}

	/** A path-only URL (no scheme://host) still gets its query normalised. */
	@Test
	public void aPathOnlyUrlIsStillNormalised() {

		assertEquals("/fc/f.htm?a=1", FCUtils.canonicalizeURL("/fc/f.htm?a=1&empty="));
	}

	@Test
	public void nullIsPassedThrough() {
		assertEquals(null, FCUtils.canonicalizeURL(null));
	}
}
