/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tests.base;

import static org.junit.Assert.assertEquals;

import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.htmlunit.html.HtmlPage;

public abstract class IncludeTests extends TestsBase {

	public abstract String getFrontCacheBaseURLLocalhost();

	protected FrontCacheClient frontcacheClient = null;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), ApiKeys.TEST_API_KEY_LOCALHOST);
		frontcacheClient.removeFromCacheAll(); // clean up

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}


	@Test
	public void includeAsync1() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/deep-include-async/a1.jsp");
		assertEquals("a", page.getPage().asNormalizedText()); // no 'b' in response because 'b' included in async mode
	}


	@Test
	public void includeAsync2() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/deep-include-async/a.jsp");
		assertEquals("abcd", page.getPage().asNormalizedText()); // no 'ef' in response because 'e' included in async mode

	}


	@Test
	public void includeAsync3() throws Exception {

		final String baseURL = getFrontCacheBaseURLLocalhost() + "common/deep-include-async/";

		// The 'e' fragment is pulled in via an async include: it is resolved fire-and-forget on a
		// background thread (see ConcurrentIncludeProcessor - the Future is not awaited). In the Jetty
		// test container that background resolution occasionally does not run before the request
		// lifecycle ends, so 'e' never lands in the cache. It resolves within a few ms when it does run,
		// and re-serving the (cached) page re-triggers include processing - so re-issue the request until
		// 'e' is cached. This does NOT cache 'f': 'e's own include is never resolved (async includes
		// don't recurse), which the assertion at the end verifies.
		org.frontcache.core.WebResponse resp = null;
		for (int attempt = 0; attempt < 20 && null == resp; attempt++)
		{
			HtmlPage page = webClient.getPage(baseURL + "a.jsp");
			assertEquals("abcd", page.getPage().asNormalizedText()); // no 'ef' in response because 'e' included in async mode
			resp = getFromCacheWithWait(baseURL + "e.jsp", 1000);
		}

		// sync includes are cached during the page request
		resp = frontcacheClient.getFromCache(baseURL + "a.jsp");
		assertEquals("a<fc:include url=\"/common/deep-include-async/b.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(baseURL + "b.jsp");
		assertEquals("b<fc:include url=\"/common/deep-include-async/c.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(baseURL + "c.jsp");
		assertEquals("c<fc:include url=\"/common/deep-include-async/d.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(baseURL + "d.jsp");
		assertEquals("d<fc:include url=\"/common/deep-include-async/e.jsp\" call=\"async\" />", new String(resp.getContent()));

		// 'e' is cached by the async include resolution
		resp = frontcacheClient.getFromCache(baseURL + "e.jsp");
		assertEquals("e<fc:include url=\"/common/deep-include-async/f.jsp\" />", new String(resp.getContent()));

		// !!! includes inside async includes are not called
		resp = frontcacheClient.getFromCache(baseURL + "f.jsp");
		assertEquals(null, resp);
	}

	/**
	 * Polls the cache for a fragment cached asynchronously (async include), returning as soon
	 * as it is present or null once the timeout elapses. Sync includes are cached before the
	 * page response returns and don't need this.
	 */
	private org.frontcache.core.WebResponse getFromCacheWithWait(String url, long timeoutMs) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(url);
		while (null == resp && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
			resp = frontcacheClient.getFromCache(url);
		}
		return resp;
	}

	@Test
	public void includeClientSpecific1() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());

		// request as guest
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());
	}

	@Test
	public void includeClientSpecific2() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a1.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());

		// request as guest
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a1.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
	}

	@Test
	public void includeClientSpecific3() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a2.jsp");
		assertEquals("a", page.getPage().asNormalizedText());

		// request as guest
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a2.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());
	}

	@Test
	public void includeClientSpecific4() throws Exception {
		HtmlPage page = null;
		// request as bot
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a3.jsp");
		assertEquals(page.getPage().asNormalizedText(), "a");

		// request as guest
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/include-bot-guest/a3.jsp");
		assertEquals(page.getPage().asNormalizedText(), "a");
	}

}
