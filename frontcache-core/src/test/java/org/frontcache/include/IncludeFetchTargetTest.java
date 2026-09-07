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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import jakarta.servlet.FilterChain;
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
import org.frontcache.include.combine.CombineProtocol;
import org.frontcache.include.combine.FcCombineMetrics;
import org.frontcache.include.impl.ConcurrentIncludeProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Where a cache-missing {@code <fc:include>} is fetched from, and who caches the answer.
 *
 * See docs/archive/include-fetch-fix.md. One string used to do two jobs: the include's absolute URL was both
 * the cache key and the fetch target, so a miss was fetched from this node's own {@code Host} - back
 * out to the CDN and in at its own front door, which fetched the origin and cached the result on the
 * way through. {@code front-cache.include-fetch-target=origin} splits the two: the key stays the
 * public URL and only the wire URL moves.
 *
 * Everything here is about a failure that is silent. Section 8 of that document ranks them, and the
 * top three are all "the fetch works, the page renders, and the cache quietly stops working":
 * no {@code putToCache} at all, the key rebased onto the origin host, or the stored response still
 * carrying the origin URL. Each has a test below that fails on it and on nothing else.
 */
public class IncludeFetchTargetTest {

	private static final String EDGE_BASE   = "https://www.example-site.com";
	private static final String ORIGIN_BASE = "http://origin.example.com:8080";

	private static final String INCLUDE_URI = "/fc/x.htm?locale=en";
	private static final String PUBLIC_URL  = EDGE_BASE   + INCLUDE_URI;
	private static final String ORIGIN_URL  = ORIGIN_BASE + INCLUDE_URI;

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

		// the combine counters are JVM-global, and the combine cases below move them
		FcCombineMetrics.reset();
	}

	// ---- the split ------------------------------------------------------------------------------

	/**
	 * The one assertion that has to be made as a pair. A test that only checks the fetch URL passes
	 * with the key rebased too - which is failure mode 2: two entries per fragment, every pre-existing
	 * entry orphaned, and a cache that reads like one that never warms.
	 */
	@Test
	public void theFetchGoesToTheOriginWhileTheKeyStaysPublic() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(originTarget(), client, context());

		assertEquals("fetched with the wrong URL", Arrays.asList(new String[]{ORIGIN_URL}), client.fetched);
		assertEquals("cached under the wrong key", Arrays.asList(new String[]{PUBLIC_URL}), cache.putKeys);
	}

	/**
	 * {@code FCUtils.httpResponse2WebComponent} stamps the FETCH url onto the WebResponse, so without
	 * an explicit {@code setUrl} the stored entry says it is the origin's. Failure mode 3: the cache
	 * half-works, which is worse than not working at all.
	 */
	@Test
	public void theStoredAndServedResponseCarryThePublicURL() throws Exception {

		WebResponse served = resolveOneInclude(originTarget(), new RecordingClient(), context());

		assertEquals("served response carries the origin URL", PUBLIC_URL, served.getUrl());
		assertEquals("stored response carries the origin URL", PUBLIC_URL, cache.stored.get(PUBLIC_URL).getUrl());
	}

	/**
	 * Failure mode 1, and the expensive one: with no {@code putToCache} on this side every include
	 * becomes an uncached origin call forever, and nothing errors. Resolving the same include twice is
	 * what catches it.
	 */
	@Test
	public void aSecondResolutionOfTheSameIncludeMakesNoOriginCall() throws Exception {

		ConcurrentIncludeProcessor processor = originTarget();
		RecordingClient client = new RecordingClient();
		try {
			resolve(processor, client, context());
			resolve(processor, client, context());
		} finally {
			processor.destroy();
		}

		assertEquals("the second resolution went to the origin again", 1, client.fetched.size());
	}

	// ---- the escape hatch, and filter mode -------------------------------------------------------

	/** {@code edge} must be a no-op, not a second implementation: no rebase, and no cache write here. */
	@Test
	public void edgeTargetFetchesTheEdgeAndCachesNothingHere() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(edgeTarget(), client, context());

		assertEquals(Arrays.asList(new String[]{PUBLIC_URL}), client.fetched);
		assertTrue("edge mode cached on the requesting side - the loopback already does that",
				cache.putKeys.isEmpty());
	}

	/** The default is {@code origin} as of 2.9 - a node with nothing configured fetches the origin. */
	@Test
	public void theDefaultIsOrigin() throws Exception {

		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(new Properties());

		RecordingClient client = new RecordingClient();
		try {
			resolve(processor, client, context());
		} finally {
			processor.destroy();
		}

		assertEquals(Arrays.asList(new String[]{ORIGIN_URL}), client.fetched);
	}

	/**
	 * Filter mode fetches the origin too - but only because the fetch is marked
	 * {@code x-frontcache-dynamic-request} on its way out, which is what keeps the re-entry from
	 * caching it under a second key. See {@link IncludeProcessorBase#isLoopbackOriginFetch}.
	 */
	@Test
	public void filterModeFetchesTheOriginAndOwnsTheEntry() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(originTarget(), client, filterModeContext());

		assertEquals(Arrays.asList(new String[]{ORIGIN_URL}), client.fetched);
		assertEquals("filter mode must still key on the public URL",
				Arrays.asList(new String[]{PUBLIC_URL}), cache.putKeys);
	}

	/**
	 * The marker that makes the above safe. Without it the re-entered request caches the fragment
	 * under the origin-facing URL while this side caches it under the public one - two entries per
	 * fragment, silently, and only visible when the two hostnames differ.
	 */
	@Test
	public void aFilterModeIncludeFetchIsMarkedDynamic() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(originTarget(), client, filterModeContext());

		assertEquals("the re-entry was not told to skip its own cache write",
				Arrays.asList(new String[]{"true"}),
				client.lastRequestHeaders.get(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST));
	}

	/**
	 * And it must NOT be marked in standalone mode. There the fetch reaches the origin app, which
	 * does not read the header - but a chained topology puts another Frontcache in the path, and
	 * telling that node not to cache would quietly disable caching on it.
	 */
	@Test
	public void aStandaloneIncludeFetchIsNotMarkedDynamic() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(originTarget(), client, context());

		assertNull("a standalone include fetch must not carry the dynamic marker",
				client.lastRequestHeaders.get(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST));
		assertEquals(Arrays.asList(new String[]{PUBLIC_URL}), cache.putKeys);
	}

	/** The escape hatch has to work in filter mode too, or there is no way back. */
	@Test
	public void filterModeHonoursTheEdgeEscapeHatch() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(edgeTarget(), client, filterModeContext());

		assertEquals(Arrays.asList(new String[]{PUBLIC_URL}), client.fetched);
		assertTrue("edge mode cached on the requesting side - the loopback already does that",
				cache.putKeys.isEmpty());
		assertNull(client.lastRequestHeaders.get(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST));
	}

	// ---- what is not cached ----------------------------------------------------------------------

	/** An empty body is an entry that serves nothing. */
	@Test
	public void anEmptyResponseIsNotCached() throws Exception {

		RecordingClient client = new RecordingClient();
		client.body = new byte[0];
		resolveOneInclude(originTarget(), client, context());

		assertTrue(cache.putKeys.isEmpty());
	}

	/**
	 * No maxage means an empty expireTimeMap, and {@code isCacheable()} is false for it. Storing it
	 * would write an entry that is expired the instant it lands - index churn that never serves.
	 */
	@Test
	public void aResponseWithNoMaxAgeIsNotCached() throws Exception {

		RecordingClient client = new RecordingClient();
		client.maxAge = null;
		resolveOneInclude(originTarget(), client, context());

		assertTrue(cache.putKeys.isEmpty());
	}

	/** {@code maxAge="bot:30d"} is dynamic for a guest - cache it and every visitor gets the bot's page. */
	@Test
	public void aResponseDynamicForThisClientTypeIsNotCached() throws Exception {

		RecordingClient client = new RecordingClient();
		client.maxAge = "bot:30d";
		resolveOneInclude(originTarget(), client, context());

		assertTrue(cache.putKeys.isEmpty());
	}

	/**
	 * {@code dynamic-urls.conf} stopped the loopback from caching; it has to stop this side too, or a
	 * URL the operator marked never-cache starts being cached the day includes move to the origin.
	 * The fixture home's patterns are {@code ^/admin/.*} and {@code ^/login.htm.*}.
	 */
	@Test
	public void aDynamicURLIsNotCached() throws Exception {

		RecordingClient client = new RecordingClient();
		resolveOneInclude(originTarget(), client, context(), "/admin/users.htm");

		assertTrue("dynamic-urls.conf was ignored on the requesting side", cache.putKeys.isEmpty());
		assertEquals("it should still be FETCHED, just not stored",
				Arrays.asList(new String[]{ORIGIN_BASE + "/admin/users.htm"}), client.fetched);
	}

	/** A fallback carries 503, and the page-wide context cannot be asked whether one was used. */
	@Test
	public void aNon2xxResponseIsNotCached() throws Exception {

		RecordingClient client = new RecordingClient();
		client.status = 503;
		resolveOneInclude(originTarget(), client, context());

		assertTrue(cache.putKeys.isEmpty());
	}

	// ---- the request log -------------------------------------------------------------------------

	/**
	 * A missing include is logged by {@code FrontCacheEngine} when its loopback arrives. Stop looping
	 * back and that line has to be written here instead - failure mode 4, which breaks nothing and
	 * makes every later measurement of include behaviour read low.
	 */
	@Test
	public void aCacheMissingIncludeWritesExactlyOneRequestLogLine() throws Exception {

		List<ILoggingEvent> lines = captureRequestLog();
		try {
			resolveOneInclude(originTarget(), new RecordingClient(), context());
		} finally {
			releaseRequestLog();
		}

		assertEquals("expected exactly one include line, got " + lines, 1, lines.size());

		String line = lines.get(0).getFormattedMessage();
		assertTrue("the line does not name the public URL: " + line, line.contains(PUBLIC_URL));
		assertFalse("the line names the origin URL: " + line, line.contains(ORIGIN_BASE));
		assertTrue("a miss must be logged as dynamic, not from-cache: " + line, line.contains("dynamic"));
		assertTrue("wrong request type: " + line, line.contains(FCHeaders.COMPONENT_INCLUDE));
	}

	/** In edge mode the loopback still writes that line, so writing one here too would double-count. */
	@Test
	public void edgeTargetWritesNoRequestLogLineHere() throws Exception {

		List<ILoggingEvent> lines = captureRequestLog();
		try {
			resolveOneInclude(edgeTarget(), new RecordingClient(), context());
		} finally {
			releaseRequestLog();
		}

		assertTrue("edge mode logged the include twice: " + lines, lines.isEmpty());
	}

	// ---- the combine path ------------------------------------------------------------------------

	/**
	 * Step 5. {@code CombineProtocol.endpointOf} derives the batch endpoint from a member's URL, so it
	 * inherits whatever base that member carries. Leave it on the edge base and single includes go to
	 * the origin while combined ones keep looping back - the same fragment reachable two ways, which
	 * is how section 2 of docs/archive/input-request-count-fix.md happened.
	 *
	 * The members are the other half: they must stay keyed on their public URLs, or a combined write
	 * and a single-include read never meet.
	 */
	@Test
	public void aCombinedBatchIsFetchedFromTheOriginBaseWhileMembersStayKeyedPublicly() throws Exception {

		EnvelopeClient client = new EnvelopeClient();
		ConcurrentIncludeProcessor processor = originTarget();
		try {
			resolveCombinedPage(processor, client, context());
		} finally {
			processor.destroy();
		}

		assertEquals("the batch was not one request: " + client.fetched, 1, client.fetched.size());

		String combined = client.fetched.get(0);
		assertTrue("the batch went to the edge, not the origin: " + combined, combined.startsWith(ORIGIN_BASE + "/fc/part.htm?"));
		assertTrue("not a combined request: " + combined, combined.contains("fc-combine=1"));

		assertEquals("members were not keyed on their public URLs: " + cache.putKeys,
				Arrays.asList(new String[]{ EDGE_BASE + "/fc/part.htm?id=1", EDGE_BASE + "/fc/part.htm?id=2" }),
				cache.putKeys);
	}

	/**
	 * The envelope is not an include leg, so it gets no request-log line either - the members do,
	 * through {@code logCombinedInclude}. Logging it as well adds one phantom include per batch:
	 * nothing breaks, and "includes per page" quietly reads high forever. Failure mode 4 wearing the
	 * other face, and it took comparing marker counts across two e2e runs to notice.
	 */
	@Test
	public void theCombinedEnvelopeWritesNoRequestLogLineOfItsOwn() throws Exception {

		List<ILoggingEvent> lines = captureRequestLog();
		ConcurrentIncludeProcessor processor = originTarget();
		try {
			resolveCombinedPage(processor, new EnvelopeClient(), context());
		} finally {
			processor.destroy();
			releaseRequestLog();
		}

		for (ILoggingEvent line : lines)
			assertFalse("the combine envelope was logged as an include leg: " + line.getFormattedMessage(),
					line.getFormattedMessage().contains(CombineProtocol.PARAM_COMBINE));
	}

	/**
	 * The envelope itself must be cached by NOBODY: its URL carries whichever members happened to
	 * miss, so the set of distinct envelope URLs for one page is combinatorial in its members.
	 */
	@Test
	public void theCombinedEnvelopeIsNeverCached() throws Exception {

		EnvelopeClient client = new EnvelopeClient();
		ConcurrentIncludeProcessor processor = originTarget();
		try {
			resolveCombinedPage(processor, client, context());
		} finally {
			processor.destroy();
		}

		for (String key : cache.putKeys)
			assertFalse("the combine envelope was cached: " + key, key.contains("fc-combine"));
	}

	// ---- rebasing --------------------------------------------------------------------------------

	@Test
	public void rebasingKeepsThePathAndQueryExactly() {

		assertEquals(ORIGIN_URL, FCUtils.rebaseURL(PUBLIC_URL, ORIGIN_BASE));
		assertEquals(ORIGIN_BASE + "/a/b.htm", FCUtils.rebaseURL("https://www.example-site.com/a/b.htm", ORIGIN_BASE));
		assertEquals(ORIGIN_BASE + "/", FCUtils.rebaseURL("https://www.example-site.com", ORIGIN_BASE));
		assertEquals(ORIGIN_BASE + "/a.htm", FCUtils.rebaseURL("/a.htm", ORIGIN_BASE));

		// an empty parameter survives rebasing - canonicalizeURL, not this, is what drops it
		assertEquals(ORIGIN_BASE + "/a.htm?locale=zh&id=",
				FCUtils.rebaseURL(EDGE_BASE + "/a.htm?locale=zh&id=", ORIGIN_BASE));

		assertNull(FCUtils.rebaseURL(null, ORIGIN_BASE));
	}

	// ---- harness ---------------------------------------------------------------------------------

	private ConcurrentIncludeProcessor originTarget() {
		Properties properties = new Properties();
		properties.setProperty(IncludeProcessorBase.INCLUDE_FETCH_TARGET_PROPERTY,
				IncludeProcessorBase.INCLUDE_FETCH_TARGET_ORIGIN);

		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(properties);
		return processor;
	}

	private ConcurrentIncludeProcessor edgeTarget() {
		Properties properties = new Properties();
		properties.setProperty(IncludeProcessorBase.INCLUDE_FETCH_TARGET_PROPERTY,
				IncludeProcessorBase.INCLUDE_FETCH_TARGET_EDGE);

		ConcurrentIncludeProcessor processor = new ConcurrentIncludeProcessor();
		processor.init(properties);
		return processor;
	}

	private WebResponse resolveOneInclude(ConcurrentIncludeProcessor processor, RecordingClient client, RequestContext context) throws Exception {
		return resolveOneInclude(processor, client, context, INCLUDE_URI);
	}

	private WebResponse resolveOneInclude(ConcurrentIncludeProcessor processor, RecordingClient client, RequestContext context, String includeURI) throws Exception {
		try {
			resolve(processor, client, context, includeURI);
		} finally {
			processor.destroy();
		}

		return client.lastServed;
	}

	private void resolve(ConcurrentIncludeProcessor processor, RecordingClient client, RequestContext context) {
		resolve(processor, client, context, INCLUDE_URI);
	}

	/** One page carrying one include, run through the real {@code processIncludes}. */
	private void resolve(ConcurrentIncludeProcessor processor, RecordingClient client, RequestContext context, String includeURI) {

		String page = "<html><body><fc:include url=\"" + includeURI + "\"/></body></html>";
		WebResponse parent = new WebResponse(EDGE_BASE + "/index.htm", page.getBytes(StandardCharsets.UTF_8));

		processor.processIncludes(parent, EDGE_BASE, new HashMap<String, List<String>>(), client, context, 1);
	}

	/** One page carrying two combinable includes of the same endpoint. */
	private void resolveCombinedPage(ConcurrentIncludeProcessor processor, EnvelopeClient client, RequestContext context) {

		String page = "<html><body>"
				+ "<fc:include url=\"/fc/part.htm?id=1\" combine=\"true\"/>"
				+ "<fc:include url=\"/fc/part.htm?id=2\" combine=\"true\"/>"
				+ "</body></html>";

		WebResponse parent = new WebResponse(EDGE_BASE + "/index.htm", page.getBytes(StandardCharsets.UTF_8));
		processor.processIncludes(parent, EDGE_BASE, new HashMap<String, List<String>>(), client, context, 1);
	}

	/** A request being served by the servlet FILTER: it carries a chain. */
	private RequestContext filterModeContext() throws Exception {

		RequestContext context = context();
		context.setFilterChain((FilterChain) Proxy.newProxyInstance(
				IncludeFetchTargetTest.class.getClassLoader(),
				new Class[] { FilterChain.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						return null;
					}
				}));

		return context;
	}

	private RequestContext context() throws Exception {

		RequestContext context = new RequestContext();
		context.setRequest(stubRequest());
		context.setOriginURL(new URL(ORIGIN_BASE));
		context.setDomain("www.example-site.com");
		context.setRequestId("test-request-id");
		context.setClientIP("203.0.113.7");
		context.setClientType(FCHeaders.REQUEST_CLIENT_TYPE_GUEST);
		return context;
	}

	/** Answers the handful of things RequestLogger and the include path ask of a request. */
	private static HttpServletRequest stubRequest() {
		return (HttpServletRequest) Proxy.newProxyInstance(
				IncludeFetchTargetTest.class.getClassLoader(),
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

	// ---- log capture -----------------------------------------------------------------------------

	private Logger requestLogger;
	private ListAppender<ILoggingEvent> requestLogAppender;
	private Level requestLoggerLevel;

	private List<ILoggingEvent> captureRequestLog() {

		requestLogger = (Logger) LoggerFactory.getLogger("org.frontcache.reqlog.RequestLogger");
		requestLoggerLevel = requestLogger.getLevel();

		requestLogAppender = new ListAppender<ILoggingEvent>();
		requestLogAppender.start();

		requestLogger.setLevel(Level.TRACE);
		requestLogger.addAppender(requestLogAppender);

		return requestLogAppender.list;
	}

	private void releaseRequestLog() {
		requestLogger.detachAppender(requestLogAppender);
		requestLogger.setLevel(requestLoggerLevel);
	}

	// ---- doubles ---------------------------------------------------------------------------------

	/**
	 * An origin that answers every include with one cacheable fragment, and remembers what it was
	 * asked for.
	 */
	private static class RecordingClient implements FcHttpClient {

		final List<String> fetched = new ArrayList<String>();

		byte[] body = "fragment".getBytes(StandardCharsets.UTF_8);
		String maxAge = "30d";
		int status = 200;

		volatile WebResponse lastServed;
		volatile Map<String, List<String>> lastRequestHeaders = new HashMap<String, List<String>>();

		@Override
		public WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {
			return fetchOverHttp(urlStr, requestHeaders, context);
		}

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {

			synchronized (fetched) {
				fetched.add(urlStr);
			}

			lastRequestHeaders = new HashMap<String, List<String>>(requestHeaders);

			Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
			headers.put(FCHeaders.CONTENT_TYPE, Arrays.asList(new String[]{"text/html;charset=UTF-8"}));
			if (null != maxAge)
				headers.put(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, Arrays.asList(new String[]{maxAge}));

			// exactly how an origin response is built on the real path - the fetch URL is stamped on
			WebResponse webResponse = FCUtils.buildWebComponent(urlStr, body, headers);
			webResponse.setStatusCode(status);

			lastServed = webResponse;
			return webResponse;
		}

		@Override
		public WebResponse fetch(String urlStr) {
			return fetchOverHttp(urlStr, new HashMap<String, List<String>>(), null);
		}
	}

	/** An origin that implements combining: one envelope with one part per member, in order. */
	private static class EnvelopeClient extends RecordingClient {

		@Override
		public WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context) {

			synchronized (fetched) {
				fetched.add(urlStr);
			}

			lastRequestHeaders = new HashMap<String, List<String>>(requestHeaders);

			StringBuilder json = new StringBuilder("{\"v\":1,\"parts\":[");
			for (int i = 0; i < partCount(urlStr); i++)
			{
				if (0 < i)
					json.append(',');

				json.append("{\"status\":200,\"headers\":{\"Content-Type\":[\"text/html;charset=UTF-8\"],")
					.append("\"x-frontcache-component-maxage\":[\"30d\"]},\"body\":\"<div>")
					.append(i).append("</div>\"}");
			}
			json.append("]}");

			Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
			headers.put(FCHeaders.CONTENT_TYPE, Arrays.asList(new String[]{CombineProtocol.ENVELOPE_CONTENT_TYPE}));
			headers.put(CombineProtocol.HEADER_COMBINE, Arrays.asList(new String[]{"1"}));

			WebResponse envelope = FCUtils.buildWebComponent(urlStr, json.toString().getBytes(StandardCharsets.UTF_8), headers);
			envelope.setStatusCode(200);

			lastServed = envelope;
			return envelope;
		}

		/** How many members the batch asked for - one part is echoed back per fc-part-N parameter. */
		private static int partCount(String urlStr) {

			int count = 0;
			int idx = urlStr.indexOf(CombineProtocol.PARAM_PART);
			while (-1 < idx)
			{
				count++;
				idx = urlStr.indexOf(CombineProtocol.PARAM_PART, idx + CombineProtocol.PARAM_PART.length());
			}

			return count;
		}
	}

	/** A cache that stores, so a second resolution can hit it, and remembers every key written. */
	private static class RecordingCache implements CacheProcessor {

		final Map<String, WebResponse> stored = new LinkedHashMap<String, WebResponse>();
		final List<String> putKeys = new ArrayList<String>();

		@Override
		public synchronized void putToCache(String url, WebResponse component) {
			putKeys.add(url);
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
	 * {@code CacheManager} holds one JVM-global processor and offers no setter - by design, since
	 * creating it opens the Lucene index and takes its write lock. Swapping the field is the only way
	 * to give this test a cache it can read back, and it is restored after every case.
	 */
	private static CacheProcessor swapCacheManagerInstance(CacheProcessor replacement) throws Exception {

		Field field = CacheManager.class.getDeclaredField("instance");
		field.setAccessible(true);

		CacheProcessor previous = (CacheProcessor) field.get(null);
		field.set(null, replacement);

		if (null != replacement)
			assertNotNull("the swap did not take", CacheManager.getInstance());

		return previous;
	}
}
