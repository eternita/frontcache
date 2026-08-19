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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.htmlunit.html.HtmlPage;

/**
 *
 * Defined tests are run in filter & standalone modes
 *
 */
public abstract class CommonTests extends TestsBase {

	public abstract String getFrontCacheBaseURLDomainFC1();

	protected FrontCacheClient frontcacheClient = null;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLDomainFC1(), SiteKeys.TEST_SITE_KEY_1);
		frontcacheClient.removeFromCacheAll(); // clean up

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}


	@Test
	public void jsp() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/jsp-read/a.jsp");
		WebResponse webResponse = page.getWebResponse();
		printHeaders(webResponse);
		assertEquals("Hi from JSP", page.getPage().asNormalizedText());

	}

	@Test
	public void jspInclude() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/jsp-include/a.jsp");
		WebResponse webResponse = page.getWebResponse();
		printHeaders(webResponse);
		assertEquals("ab", page.getPage().asNormalizedText());

	}

	@Test
	public void jspIncludeAndCache1() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/6ci/a.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());

	}

	@Test
	public void jspIncludeAndCache2() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/7ci/a.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());

	}

	@Test
	public void jspDeepInclude() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/deep-include/a.jsp");
		assertEquals("abcdef", page.getPage().asNormalizedText());

	}

	@Test
	public void redirect() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/redirect/a.jsp");
		assertEquals("redirecred", page.getPage().asNormalizedText());

	}

	/**
	 * check if debug mode available
	 *
	 * @throws Exception
	 */
	@Test
	public void debugMode() throws Exception {

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/debug/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();

//		x-frontcache-trace-request.0: success toplevel from-cache 1 1 "http://localhost:9080/common/debug/a.jsp" frontcache-localhost-1 browser
		String logStr = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");

		assertNotEquals(-1, logStr.indexOf("common/debug/a.jsp"));
		assertNotEquals(-1, logStr.indexOf("success"));
		assertNotEquals(-1, logStr.indexOf("toplevel"));
		assertNotEquals(-1, logStr.indexOf("dynamic"));
		assertNotEquals(-1, logStr.indexOf("1")); // size

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/debug/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();

		logStr = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0");

		assertNotEquals(-1, logStr.indexOf("common/debug/a.jsp"));
		assertNotEquals(-1, logStr.indexOf("success"));
		assertNotEquals(-1, logStr.indexOf("toplevel"));
		assertNotEquals(-1, logStr.indexOf("from-cache"));
		assertNotEquals(-1, logStr.indexOf("1")); // size

	}

	@Test
	public void httpHeadersMode() throws Exception {
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/fc-headers/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();

		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("-1", maxage);

	}

	@Test
	public void l1l2Cache() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/l1-l2-cache-level/a1.jsp");
		assertEquals("abc", page.getPage().asNormalizedText());

		Map<String, String> state = frontcacheClient.getCacheState();

		assertEquals("org.frontcache.cache.impl.L1L2CacheProcessor", state.get("impl"));

		assertEquals("3", state.get(CacheProcessor.CACHED_ENTRIES));
		assertEquals("1", state.get(CacheProcessor.CACHED_ENTRIES + "-L1"));
		assertEquals("2", state.get(CacheProcessor.CACHED_ENTRIES + "-L2"));

		assertEquals("EhCache", state.get("impl_L1"));
		assertEquals("Lucene", state.get("impl_L2"));

	}


	/**
	 * for single page maxage="bot:60"
	 *
	 * call with User-Agent GoogleBot -> dynamic
	 * call with User-Agent GoogleBot -> cached
	 *
	 * call with User-Agent chrome -> dynamic
	 * call with User-Agent chrome -> dynamic
	 *
	 * @throws Exception
	 */
	@Test
	public void clientTypeSpecificCache() throws Exception {
		// single page maxage="bot:60"

		webClient.addRequestHeader("User-Agent", "Googlebot");
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// call with User-Agent GoogleBot -> dynamic
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		String maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// call with User-Agent GoogleBot -> cached
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		webClient.addRequestHeader("User-Agent", "Chrome");

		// multiple times
		// call with User-Agent chrome -> dynamic
		for (int i = 0; i<3; i++)
		{
			page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/a.jsp");
			assertEquals("a", page.getPage().asNormalizedText());
			webResponse = page.getWebResponse();
			maxage = webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
			assertEquals("bot:60", maxage);
			assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		}

		return;
	}

	/**
	 *
	 * call with User-Agent Chrome -> include is in cache
	 * call with User-Agent Chrome to include -> it's dynamic
	 *
	 * call with User-Agent GoogleBot -> include is in cache
	 * call with User-Agent GoogleBot to include -> it's from cache
	 *
	 * @throws Exception
	 */
	@Test
	public void clientTypeSpecificIncludes() throws Exception {

		webClient.addRequestHeader("User-Agent", "Chrome");
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// call with User-Agent Chrome -> include is in cache
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/a1.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());
		org.frontcache.core.WebResponse webResponse = frontcacheClient.getFromCache( getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/b1.jsp");
		// b1.jsp has "bot:60"
		// request was from browser -> so, page is not cached
		assertEquals(null, webResponse);

		// call with User-Agent Chrome to include -> it's dynamic
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/b1.jsp");
		assertEquals("b", page.getPage().asNormalizedText());
		org.htmlunit.WebResponse pageWebResponse = page.getWebResponse();
		String maxage = pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		assertEquals(false, TestUtils.isRequestFromCache(pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));


		webClient.addRequestHeader("User-Agent", "Googlebot");

		// call with User-Agent GoogleBot -> include is in cache
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/a1.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());
		webResponse = frontcacheClient.getFromCache( getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/b1.jsp");
		assertEquals("b", new String(webResponse.getContent()));
		maxage = webResponse.getHeader(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);

		// call with User-Agent GoogleBot to include -> it's from cache
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/b1.jsp");
		assertEquals("b", page.getPage().asNormalizedText());
		pageWebResponse = page.getWebResponse();
		maxage = pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE);
		assertEquals("bot:60", maxage);
		assertEquals(true, TestUtils.isRequestFromCache(pageWebResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
	}


	@Test
	public void includeSync() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/a2.jsp");
		assertEquals("ab", page.getPage().asNormalizedText());

		//  b2 has maxage="0" -> should not be cached
		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache( getFrontCacheBaseURLDomainFC1() + "common/client-bot-browser/b2.jsp");
		assertEquals(null, resp);

	}

	@Test // it doesn't work in jetty test container (for standalone mode) however it works in tomcat prod env
	@Ignore
	public void cacheRefreshSoft1() throws Exception {

		// call
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a.jsp");
		long timestamp1 = Long.parseLong(page.getPage().asNormalizedText());

		// common/refresh-regular-soft/b.jsp has maxage 3sec
		// so, aster sleep it's expired
		Thread.sleep(5000);

		// call the same page
		// because of soft cache refresh expired data is returned
		// and will be refreshed in background
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a.jsp");
		long timestamp2 = Long.parseLong(page.getPage().asNormalizedText());

		assertEquals(timestamp1, timestamp2);

		Thread.sleep(1000); // wait a sec to make sure background update completed

		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a.jsp");
		long timestamp3 = Long.parseLong(page.getPage().asNormalizedText());

		assertNotEquals(timestamp1, timestamp3);
	}

	@Test // it doesn't work in jetty test container (for standalone mode) however it works in tomcat prod env
	@Ignore
	public void cacheRefreshSoft2() throws Exception {

		webClient.addRequestHeader("User-Agent", "Googlebot");

		// call
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a1.jsp");
		long timestamp1 = Long.parseLong(page.getPage().asNormalizedText());

		// common/refresh-regular-soft/b1.jsp has maxage=bot:3
		// so, aster sleep it's expired
		Thread.sleep(5000);

		// call the same page as browser (data should be dynamic)
		webClient.addRequestHeader("User-Agent", "Chrome");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a1.jsp");
		long timestamp21 = Long.parseLong(page.getPage().asNormalizedText());

		assertNotEquals(timestamp1, timestamp21);

		// call the same page
		// because of soft cache refresh expired data is returned
		// and will be refreshed in background
		webClient.addRequestHeader("User-Agent", "Googlebot");
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a1.jsp");
		long timestamp22 = Long.parseLong(page.getPage().asNormalizedText());

		assertEquals(timestamp1, timestamp22);

		Thread.sleep(1000); // wait a sec to make sure background update completed

		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/refresh-regular-soft/a1.jsp");
		long timestamp3 = Long.parseLong(page.getPage().asNormalizedText());

		assertNotEquals(timestamp1, timestamp3);
	}

	/**
	 * An intermediary (e.g. Cloudflare) may re-case the component cache-directive header
	 * on the origin response: the app emits "x-frontcache-component-maxage" but the edge
	 * receives "X-Frontcache-Component-Maxage". FC must parse it case-insensitively and
	 * cache the page. Regression for the case-sensitive header lookup that made every such
	 * page dynamic. Runs in both filter and standalone modes.
	 *
	 * @throws Exception
	 */
	@Test
	public void proxyRecasedComponentHeaderIsCached() throws Exception {

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// 1st request - nothing cached yet -> dynamic
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/proxy-cased-header/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// 2nd request - the title-cased maxage="1h" must have been honored -> from cache
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/proxy-cased-header/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
	}

	/**
	 * Set-Cookie must never be stored in the cache. A page can be marked cacheable by the app and
	 * still carry a per-visitor cookie - the visitor whose request went to the origin gets it, but
	 * replaying it from cache would hand that visitor's cookie to everybody else. FC strips it on
	 * the way into the cache (CacheProcessorBase.putToCache / NON_PERSISTENT_HEADERS).
	 * Runs in both filter and standalone modes.
	 *
	 * @throws Exception
	 */
	@Test
	public void setCookieIsNotCached() throws Exception {

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// 1st request - nothing cached yet -> dynamic, and the cookie must reach this visitor
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/set-cookie/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		assertTrue("cache miss must pass the origin's Set-Cookie through to the client", hasTestCookie(webResponse));

		// 2nd request - served from cache, and the previous visitor's cookie must be gone
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/set-cookie/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		assertFalse("Set-Cookie must not be cached/replayed on a cache hit", hasTestCookie(webResponse));
	}

	/** true when the response carries the fixture's Set-Cookie (any casing, any position). */
	private static boolean hasTestCookie(WebResponse webResponse) {
		for (NameValuePair header : webResponse.getResponseHeaders())
			if ("Set-Cookie".equalsIgnoreCase(header.getName()) && -1 < header.getValue().indexOf("fc-test-cookie="))
				return true;

		return false;
	}

	@Test
	public void testCacheForHTTPMethod() throws Exception {

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/methods/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));


		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLDomainFC1() + "common/methods/a.jsp");
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();

		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));


		// third request (HEAD METHOD) - the same request - response should be from the cache now

		String urlStr = getFrontCacheBaseURLDomainFC1() + "common/methods/a.jsp";
		CloseableHttpClient httpclient = HttpClients.createDefault();

		HttpResponse response = null;

		HttpHost httpHost = FCUtils.getHttpHost(new URL(urlStr));
		HttpRequest httpRequest = new HttpHead(FCUtils.buildRequestURI(urlStr));
		httpRequest.addHeader(FCHeaders.ACCEPT, "text/html");
		httpRequest.addHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		response = httpclient.execute(httpHost, httpRequest);

		assertEquals(true, TestUtils.isRequestFromCache(response.getFirstHeader(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0").getValue()));

		((CloseableHttpResponse) response).close();

		return;
	}


}
