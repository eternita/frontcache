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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.frontcache.cache.CacheProcessor;
import org.frontcache.client.FrontCacheClient;
import org.frontcache.client.FrontCacheCluster;
import org.frontcache.core.FCHeaders;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;

/**
 *
 *
 */
public abstract class ClientTests extends TestsBase {

	private String FRONTCACHE_CLUSTER_NODE1 = getFrontCacheBaseURLLocalhost();

	private String FRONTCACHE_CLUSTER_NODE2 = getFrontCacheBaseURLLoopback();

	protected FrontCacheClient frontcacheClient = null;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST),
						new FrontCacheClient(getFrontCacheBaseURLLoopback(), SiteKeys.TEST_SITE_KEY_LOCALHOST)}
			));

		// clean up
		fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);


		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);

		webClient.addRequestHeader(FCHeaders.ACCEPT, "text/html");
	}

	@After
	public void tearDown() throws Exception {
		super.tearDown();
	}

	public abstract String getFrontCacheBaseURLLocalhost();

	public abstract String getFrontCacheBaseURLLoopback();

	@Test
	public void getFromCacheClient() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);

		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_A);
		assertEquals("a", page.getPage().asNormalizedText());

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI_A);

		assertEquals("a", new String(resp.getContent()));
		return;
	}

	@Test
	public void deepInclude() throws Exception {

		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/a.jsp");
		assertEquals("abcdef", page.getPage().asNormalizedText());

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/a.jsp");
		assertEquals("a<fc:include url=\"/common/deep-include-cache/b.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/b.jsp");
		assertEquals("b<fc:include url=\"/common/deep-include-cache/c.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/c.jsp");
		assertEquals("c<fc:include url=\"/common/deep-include-cache/d.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/d.jsp");
		assertEquals("d<fc:include url=\"/common/deep-include-cache/e.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/e.jsp");
		assertEquals("e<fc:include url=\"/common/deep-include-cache/f.jsp\" />", new String(resp.getContent()));

		resp = frontcacheClient.getFromCache(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/f.jsp");
		assertEquals("f", new String(resp.getContent()));

	}

	@Test
	public void getFromCacheClientNull() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);

		// clean up
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		org.frontcache.core.WebResponse resp = frontcacheClient.getFromCacheActionResponse(getFrontCacheBaseURLLocalhost() + TEST_URI_A).getValue();

		assertNull(resp);
		return;
	}


	@Test
	public void getCacheStatusClient() throws Exception {

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);

		Map<String, String> response = frontcacheClient.getCacheState();
		Assert.assertNotNull(response);
		logger.debug("response " + response);
	}

	@Test
	public void getCachedKeysClient() throws Exception {

		final String TEST_URI = "common/fc-client/a.jsp";

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);
		frontcacheClient.removeFromCacheAll(); // clean up

		// the request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());

		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		boolean success = frontcacheClient.getCachedKeys(baos);
		String keys = baos.toString();

		// getCachedKeys must pass the site-key gate (Authorization: Bearer <site-key>)
		Assert.assertTrue(success);
		Assert.assertEquals("getCachedKeys was denied - site key not sent: " + keys, -1, keys.indexOf("Access denied"));
		Assert.assertNotEquals("cached key not listed: " + keys, -1, keys.indexOf(TEST_URI));
		return;
	}

//	@Test
//	public void getCacheStatusCluster() throws Exception {
//
//		FrontCacheClient frontCacheClient1 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE1, SiteKeys.TEST_SITE_KEY_LOCALHOST);
//		FrontCacheClient frontCacheClient2 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE2, SiteKeys.TEST_SITE_KEY_LOCALHOST);
//		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(new FrontCacheClient[]{frontCacheClient1, frontCacheClient2}));
//
//		Map<String, String> response = fcCluster.getCacheState().get(frontCacheClient1);
//		Assert.assertNotNull(response);
//		logger.debug("response " + response);
//	}

//	@Test
//	public void getCachedKeysClient() throws Exception {
//
//		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURL());
//
//		CachedKeysActionResponse response = frontcacheClient.getCachedKeys();
//		Assert.assertNotNull(response);
//		Assert.assertEquals("cached keys", response.getAction());
//		logger.debug("response " + response);
//	}
//
//	@Test
//	public void getCachedKeysCluster() throws Exception {
//
//		FrontCacheClient frontCacheClient1 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE1);
//		FrontCacheClient frontCacheClient2 = new FrontCacheClient(FRONTCACHE_CLUSTER_NODE2);
//		FrontCacheCluster fcCluster = new FrontCacheCluster(frontCacheClient1, frontCacheClient2);
//
//		CachedKeysActionResponse response = fcCluster.getCachedKeys().get(frontCacheClient1);
//		Assert.assertNotNull(response);
//		Assert.assertEquals("cached keys", response.getAction());
//		logger.debug("response " + response);
//	}

	@Test
	public void invalidationByFilterTestClient() throws Exception {

		final String TEST_URI = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// cache invalidation (both standalone and filter)
		String response = frontcacheClientStandalone.removeFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI); // clean up FC standalone
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));
		response = frontcacheClientFilter.removeFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI); // clean up FC filter
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		return;
	}

	@Test
	public void invalidationByFilterTestCluster() throws Exception {

		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), SiteKeys.TEST_SITE_KEY_LOCALHOST);
		final String TEST_URI = "common/fc-client/a.jsp";

		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		// the first request - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());

		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// second request - the same request - response should be from the cache now
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(true, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// cache invalidation
		String response = fcCluster.removeFromCache(getFrontCacheBaseURLLocalhost() + TEST_URI).get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// third request - the same request - response is dynamic
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI);
		assertEquals("a", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();

		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));
		return;
	}

	@Test
	public void invalidationAllTestClient() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");

		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
				new FrontCacheClient[]{
						new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST),
						new FrontCacheClient(getFrontCacheBaseURLLoopback(), SiteKeys.TEST_SITE_KEY_LOCALHOST)}
			));

		fcCluster.removeFromCacheAll(); // clean up

		frontcacheClient = new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST);

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_A);
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_B);
		assertEquals("b", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		Map<String, String> cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		// cache invalidation
		String response = frontcacheClient.removeFromCacheAll();
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));
		return;
	}


	@Test
	public void invalidationAllTestCluster() throws Exception {

		final String TEST_URI_A = "common/fc-client/a.jsp";
		final String TEST_URI_B = "common/fc-client/b.jsp";
		webClient.addRequestHeader(FCHeaders.X_FRONTCACHE_TRACE, "true");
		FrontCacheCluster fcCluster = new FrontCacheCluster(Arrays.asList(
					new FrontCacheClient[]{
							new FrontCacheClient(getFrontCacheBaseURLLocalhost(), SiteKeys.TEST_SITE_KEY_LOCALHOST),
							new FrontCacheClient(getFrontCacheBaseURLLoopback(), SiteKeys.TEST_SITE_KEY_LOCALHOST)}
				));

		// clean up
		String response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

//		fcCluster = new FrontCacheCluster(Arrays.asList(new String[]{FRONTCACHE_CLUSTER_NODE1, FRONTCACHE_CLUSTER_NODE2}), SiteKeys.TEST_SITE_KEY_LOCALHOST);
//
//		// clean up
//		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
//		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		// the first request a - response should be cached
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_A);
		assertEquals("a", page.getPage().asNormalizedText());
		WebResponse webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		// the first request b - response should be cached
		page = webClient.getPage(getFrontCacheBaseURLLocalhost() + TEST_URI_B);
		assertEquals("b", page.getPage().asNormalizedText());
		webResponse = page.getWebResponse();
		assertEquals(false, TestUtils.isRequestFromCache(webResponse.getResponseHeaderValue(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + ".0")));

		Map<String, String> cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("2", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		// cache invalidation
		response = fcCluster.removeFromCacheAll().get(FRONTCACHE_CLUSTER_NODE1);
		Assert.assertNotEquals(-1, response.indexOf("invalidate"));

		cacheState = frontcacheClient.getCacheState();
		Assert.assertEquals("0", cacheState.get(CacheProcessor.CACHED_ENTRIES));

		return;
	}


	@Test
	public void httpHeadersDuplication() throws Exception {
		HtmlPage page = webClient.getPage(getFrontCacheBaseURLLocalhost() + "common/deep-include-cache/e.jsp");
		assertEquals("ef", page.getPage().asNormalizedText());

		List<NameValuePair> headers = page.getWebResponse().getResponseHeaders();
		for (NameValuePair nv : headers)
			System.out.println("HTTP HEADER " + nv.getName() + " -- " + nv.getValue());

		Set<String> names = new HashSet<String>();
		for (NameValuePair nv : headers)
		{
			String name = nv.getName();
			if (names.contains(name))
				fail("HTTP header duplicate name - " + name);

			names.add(name);
		}
		return;
	}

	/**
	 * The console's read-only config views, end to end.
	 *
	 * The suite never exercised these actions before, which is how a domain-keyed shape survived in the
	 * wire format for a release after the feature behind it was retired. Asserted against a live node so
	 * the flattening is proven through real HTTP and real Jackson, not only in a unit test.
	 */
	@Test
	public void consoleConfigViewsReturnFlatCollections() throws Exception {
		Set<String> botKeywords = frontcacheClient.getBotKeywords();
		Assert.assertNotNull("get-bots returned nothing", botKeywords);
		Assert.assertFalse("conf/bots.conf is not empty, so neither should this be", botKeywords.isEmpty());

		Assert.assertNotNull("get-dynamic-urls returned nothing", frontcacheClient.getDynamicUrlPatterns());
		Assert.assertNotNull("get-fallback-configs returned nothing", frontcacheClient.getFallbackEntries());

		// the pre-2.7 shape has to keep arriving too - a 2.6 console reads it
		@SuppressWarnings("deprecation")
		Map<String, Set<String>> legacyShape = frontcacheClient.getBots();
		Assert.assertNotNull("the legacy domain-keyed field is gone from the wire", legacyShape);
		Assert.assertEquals("one node serves one site, so exactly one entry", 1, legacyShape.size());
		Assert.assertEquals("a 2.6 console's JSPs switch on this literal",
				"default-domain", legacyShape.keySet().iterator().next());
		Assert.assertEquals("both shapes must carry the same data",
				botKeywords, legacyShape.values().iterator().next());
	}

	/**
	 * The resilience config panel, and its pre-2.7 action name. Both must return the file the node is
	 * actually running on - which is conf/resilience.properties in standalone mode and the legacy
	 * conf/hystrix.properties in filter mode, so this runs against both spellings of the file too.
	 */
	@Test
	public void resilienceConfigIsServedUnderBothActionNames() throws Exception {
		String config = frontcacheClient.getResilienceConfig();

		Assert.assertNotNull("get-resilience-configs returned nothing", config);
		Assert.assertTrue("expected the raw properties file, got: " + config,
				config.contains("command.default.circuitBreaker.errorThresholdPercentage"));

		@SuppressWarnings("deprecation")
		String viaLegacyAccessor = frontcacheClient.getHystrixConfig();
		Assert.assertEquals("the pre-2.7 path must return the same file", config, viaLegacyAccessor);
	}

	/**
	 * conf/guard-rules.conf is optional and neither e2e node ships one, so this asserts the ABSENT case:
	 * a well-formed ERROR response, not a blown-up action.
	 *
	 * It is here because that is not what used to happen. The handler caught only IOException while
	 * FCConfig signals a missing file by throwing a RuntimeException, so the action failed outright and
	 * the "not found" branch it was written for was unreachable. Nothing called the action in any test,
	 * which is how it stayed that way.
	 */
	@Test
	public void guardConfigActionReportsAMissingFileInsteadOfFailing() throws Exception {
		org.frontcache.io.GetGuardConfigActionResponse response = frontcacheClient.getGuardConfigActionResponse();

		Assert.assertNotNull("the action must answer, even with no guard-rules.conf on the node", response);
		Assert.assertEquals("no guard-rules.conf on this node, so: a clean error",
				org.frontcache.io.ActionResponse.RESPONSE_STATUS_ERROR, response.getResponseStatus());
		Assert.assertEquals("guard-rules.conf not found", response.getErrorDescription());
		Assert.assertNull(response.getGuardConfig());
	}


}
