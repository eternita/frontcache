/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.cache.CacheStats;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FcHttpClient;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The include path's copy of the "one client type's expiry must not evict the other's live copy"
 * rule - {@code org.frontcache.cache.TwoLiveTtlEvictionTest} is the same assertion on the top-level
 * path.
 *
 * Two tests rather than one because the two paths carry two copies of the expiry-and-evict block
 * ({@code IncludeProcessorBase.getIncludeFromCache} and {@code CacheProcessorBase.processRequest}),
 * and fixing one and not the other is the classic way this regresses. The e2e suite's
 * {@code clientTypeSplitTtlInclude} does NOT cover it: there the origin is up, so the refetch puts
 * the entry straight back and the eviction is invisible. It is only visible when the repopulate is
 * refused - which on this path means a non-2xx, since {@code cacheInclude} cannot read
 * {@code isFallbackUsed()} (the RequestContext belongs to the whole page, so one include's fallback
 * would un-cache every later include on it). A fallback carries 503.
 */
public class IncludeTwoLiveTtlEvictionTest {

	private static final String EDGE_BASE   = "https://www.example-site.com";
	private static final String ORIGIN_BASE = "http://origin.example.com:8080";

	private static final String INCLUDE_URI = "/fc/fragment.htm?locale=en";
	private static final String PUBLIC_URL  = EDGE_BASE + INCLUDE_URI;

	private CacheProcessor previousCache;
	private RecordingCache cache;

	@Before
	public void installCache() throws Exception {
		cache = new RecordingCache();
		previousCache = swapCacheManagerInstance(cache);
	}

	@After
	public void restoreCache() throws Exception {
		swapCacheManagerInstance(previousCache);
	}

	/**
	 * The failure: a guest arrives after the guest TTL lapsed, the origin is down, and the copy that
	 * was valid for crawlers for another 29 days is gone - taking every crawler to the dead origin.
	 */
	@Test
	public void aGuestExpiryWithNoUsableOriginMustNotEvictTheBotCopy() throws Exception {

		cache.stored.put(PUBLIC_URL, liveForBotsExpiredForGuests());

		resolveOneInclude(guestContext());

		WebResponse survivor = cache.stored.get(PUBLIC_URL);
		assertNotNull("the fragment is still valid for bots - a guest's expiry must not evict it,"
				+ " and the 503 means nothing put it back", survivor);
		assertEquals("CACHED", new String(survivor.getContent()));
	}

	/** The other half, so the fix cannot be "never delete": expired for everyone still goes. */
	@Test
	public void aFragmentExpiredForEveryClientTypeIsStillEvicted() throws Exception {

		WebResponse expired = liveForBotsExpiredForGuests();
		Map<String, Long> expireTimeMap = new HashMap<String, Long>(expired.getExpireTimeMap());
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_BOT, System.currentTimeMillis() - 1000L);
		expired.setExpireTimeMap(expireTimeMap);
		cache.stored.put(PUBLIC_URL, expired);

		resolveOneInclude(guestContext());

		assertNull("nothing can be served from this fragment any more - it must be removed",
				cache.stored.get(PUBLIC_URL));
	}

	// ---- fixture ---------------------------------------------------------------------------------

	/** A fragment still valid for bots for 30 days, already expired for guests. */
	private WebResponse liveForBotsExpiredForGuests() {

		WebResponse response = new WebResponse(PUBLIC_URL, "CACHED".getBytes(StandardCharsets.UTF_8),
				"bot:30d, guest:1d", null);
		response.setStatusCode(200);
		response.addHeader(FCHeaders.CONTENT_TYPE, "text/html");

		Map<String, Long> expireTimeMap = new HashMap<String, Long>(response.getExpireTimeMap());
		expireTimeMap.put(FCHeaders.REQUEST_CLIENT_TYPE_GUEST, System.currentTimeMillis() - 1000L);
		response.setExpireTimeMap(expireTimeMap);

		return response;
	}

	/** One page carrying one include, run through the real processIncludes. */
	private void resolveOneInclude(RequestContext context) throws Exception {

		Properties properties = new Properties();
		properties.setProperty(IncludeProcessorBase.INCLUDE_FETCH_TARGET_PROPERTY,
				IncludeProcessorBase.INCLUDE_FETCH_TARGET_ORIGIN);

		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(properties);

		try {
			String page = "<html><body><fc:include url=\"" + INCLUDE_URI + "\"/></body></html>";
			WebResponse parent = new WebResponse(EDGE_BASE + "/index.htm", page.getBytes(StandardCharsets.UTF_8));

			processor.processIncludes(parent, EDGE_BASE, new HashMap<String, List<String>>(),
					new FallbackOnlyClient(), context, 1);
		} finally {
			processor.destroy();
		}
	}

	private RequestContext guestContext() throws Exception {

		RequestContext context = new RequestContext();
		context.setRequest(stubRequest());
		context.setOriginURL(new URL(ORIGIN_BASE));
		context.setDomain("www.example-site.com");
		context.setRequestId("test-request-id");
		context.setClientIP("203.0.113.7");
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_GUEST);
		return context;
	}

	/** An origin that is down: every fetch answers with a 503 fallback, which is never cached. */
	private static class FallbackOnlyClient implements FcHttpClient {

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {

			WebResponse fallback = new WebResponse(urlStr, "FALLBACK".getBytes(StandardCharsets.UTF_8), null, null);
			fallback.setStatusCode(503);
			fallback.addHeader(FCHeaders.CONTENT_TYPE, "text/html");
			return fallback;
		}

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
				throws FrontCacheException {
			return fetch(urlStr, requestHeaders, context);
		}

		@Override
		public WebResponse fetch(String urlStr) throws FrontCacheException {
			return fetch(urlStr, new HashMap<String, List<String>>(), null);
		}
	}

	/** Answers the handful of things RequestLogger and the include path ask of a request. */
	private static HttpServletRequest stubRequest() {
		return (HttpServletRequest) Proxy.newProxyInstance(
				IncludeTwoLiveTtlEvictionTest.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						if ("getMethod".equals(method.getName()))
							return "GET";

						if ("getHeader".equals(method.getName()))
							return null;

						if (boolean.class == method.getReturnType())
							return Boolean.FALSE;

						return null;
					}
				});
	}

	private static class RecordingCache implements CacheProcessor {

		final Map<String, WebResponse> stored = new LinkedHashMap<String, WebResponse>();

		@Override
		public synchronized void putToCache(String url, WebResponse component) {
			stored.put(url, component);
		}

		@Override
		public synchronized WebResponse getFromCache(String url, RequestContext context) {
			WebResponse hit = stored.get(url);
			return (null == hit) ? null : hit.copy();
		}

		@Override
		public synchronized void removeFromCache(String filter) {
			stored.remove(filter);
		}

		@Override
		public synchronized void removeFromCacheAll() {
			stored.clear();
		}

		@Override
		public WebResponse processRequest(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) throws FrontCacheException {
			throw new UnsupportedOperationException();
		}

		@Override
		public void doSoftInvalidation(String currentRequestURL, String originUrlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) {
		}

		@Override
		public void init(Properties properties) {
		}

		@Override
		public void destroy() {
		}

		@Override
		public Map<String, String> getCacheStatus() {
			return new HashMap<String, String>();
		}

		@Override
		public CacheStats getCacheStats() {
			return CacheStats.NOT_TRACKED;
		}

		@Override
		public synchronized List<String> getCachedKeys() {
			return new ArrayList<String>(stored.keySet());
		}

		@Override
		public void patch() {
		}

		@Override
		public void purge() {
		}
	}

	/**
	 * {@code CacheManager} holds one JVM-global processor and offers no setter - creating it opens
	 * the Lucene index and takes its write lock. Swapping the field is the only way to give this test
	 * a cache it can read back, and it is restored after every case.
	 */
	private static CacheProcessor swapCacheManagerInstance(CacheProcessor replacement) throws Exception {

		Field field = CacheManager.class.getDeclaredField("instance");
		field.setAccessible(true);

		CacheProcessor previous = (CacheProcessor) field.get(null);
		field.set(null, replacement);

		return previous;
	}
}
