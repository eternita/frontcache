/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.frontcache.FCConfig;
import org.frontcache.core.FcHttpClient;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.combine.CombineProtocol;
import org.frontcache.reqlog.RequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public abstract class IncludeProcessorBase implements IncludeProcessor {


	protected Logger logger = LoggerFactory.getLogger(getClass());

	protected static final String START_MARKER = "<fc:include";
	protected static final String END_MARKER = "/>";

	protected static final String INCLUDE_TYPE_SYNC = "sync"; // default
	protected static final String INCLUDE_TYPE_ASYNC = "async";

	/** {@code front-cache.include-fetch-target} - where a cache-missing include is fetched from. */
	public static final String INCLUDE_FETCH_TARGET_PROPERTY = "front-cache.include-fetch-target";

	/** Fetch from {@code front-cache.origin-host} and cache the answer here. */
	public static final String INCLUDE_FETCH_TARGET_ORIGIN = "origin";

	/** Pre-2.9 behaviour: fetch from this node's own public URL and let the loopback do the caching. */
	public static final String INCLUDE_FETCH_TARGET_EDGE = "edge";

	/** What a node with nothing configured does. See {@link #includeFetchTarget}. */
	public static final String INCLUDE_FETCH_TARGET_DEFAULT = INCLUDE_FETCH_TARGET_ORIGIN;


	private static final String[] NON_MERGEABLE_RESPONSE_HEADERS = new String[]{
			FCHeaders.X_FRONTCACHE_TRACE_REQUEST
		};

	/**
	 * Defaults to {@link #INCLUDE_FETCH_TARGET_ORIGIN} as of 2.9: a cache-missing include is fetched
	 * from {@code front-cache.origin-host} rather than from this node's own front door.
	 *
	 * {@code edge} remains as an escape hatch that needs no code rollback. A subclass that overrides
	 * {@link #init(Properties)} without calling {@code super} gets this default too - which is the
	 * right way round now that it is the supported behaviour, and which is why the field is
	 * initialised here rather than only in {@code init}.
	 */
	private String includeFetchTarget = INCLUDE_FETCH_TARGET_DEFAULT;

	public IncludeProcessorBase() {
	}

	/**
	 * Does a cache-missing include go straight to {@code front-cache.origin-host}?
	 *
	 * When it does, this side of the call owns the three things the loopback used to do on its way
	 * back in - the cache write, the request-log line, and the dynamic-URL check before storing. When
	 * it does not, none of them happen here, which is what makes {@code edge} a true no-op rather
	 * than a second implementation of the same behaviour.
	 *
	 * Filter mode is included, but only because {@link #isLoopbackOriginFetch} makes the ownership
	 * true there - read that first, it is where the interesting part is.
	 */
	protected boolean isDirectOriginFetch(RequestContext context)
	{
		return INCLUDE_FETCH_TARGET_ORIGIN.equals(includeFetchTarget);
	}

	/**
	 * Will this fetch come back through THIS Frontcache?
	 *
	 * Only in filter mode, and there always: the origin is the app this filter is installed in, so an
	 * HTTP call to {@code front-cache.origin-host} necessarily re-enters the filter. (In standalone
	 * mode the origin is a different process. A chained topology puts another Frontcache in the path,
	 * but that is a different node with its own keyspace, which is not this problem.)
	 *
	 * <h3>Why this has to be known, and what it costs to get wrong</h3>
	 *
	 * A re-entered request keys its own cache write on {@code context.getCurrentRequestURL()}, which
	 * is built from the {@code Host} it arrived at. So the moment the fetch is re-addressed from the
	 * public hostname to {@code origin-host}, that node - us - starts writing the fragment under a
	 * SECOND key, while this side writes it under the public one. Two entries per fragment: exactly
	 * the failure this whole change exists to avoid, arriving through a door section 8 of
	 * docs/include-fetch-fix.md did not know about.
	 *
	 * It is invisible in the obvious deployment, because {@code origin-host} usually resolves to the
	 * same name the node is reached at - the two keys coincide and nothing looks wrong. It took
	 * giving the e2e filter node an origin hostname distinct from its public one to see it: every
	 * missing include wrote two request-log lines, and {@code CommonTests.l1l2Cache} counted four
	 * cached entries where three were expected.
	 *
	 * <h3>The answer</h3>
	 *
	 * The fetch is marked {@code x-frontcache-dynamic-request}, which is the existing, node-wide
	 * signal for "serve this, do not cache it" ({@code FrontCacheEngine.processRequestInternal} gates
	 * its whole cacheable branch on it, and the combine envelope has always used it for the same
	 * reason). The re-entry then runs the app and returns its response without storing anything, and
	 * this side keeps sole ownership of the single public key - the same division of labour
	 * standalone mode gets for free. What filter mode buys is smaller than standalone's: the request
	 * still lands on this node's own front door, so it is one local hop rather than a trip out to the
	 * CDN and back.
	 */
	protected boolean isLoopbackOriginFetch(RequestContext context)
	{
		return isDirectOriginFetch(context) && context.isFilterMode();
	}

	/**
	 * The base URL includes are FETCHED from - the origin's when this node fetches includes directly,
	 * and otherwise the edge base it has always used.
	 *
	 * @param edgeBaseURL this node's own {@code scheme://host[:port]}, as passed to
	 *                    {@link #processIncludes}
	 */
	protected String includeFetchBaseURL(String edgeBaseURL, RequestContext context)
	{
		if (!isDirectOriginFetch(context))
			return edgeBaseURL;

		URL originURL = context.getOriginURL();
		if (null == originURL)
			return edgeBaseURL; // no origin resolved (no servlet request) - nothing to rebase onto

		return originURL.toString();
	}


	/**
	 * Does this response look like it contains an {@code <fc:include .../>} worth parsing?
	 *
	 * <h3>Why ISO-8859-1 and not the page's real charset</h3>
	 *
	 * This is a byte scan wearing a String's clothes. Latin-1 maps bytes 0x00-0xFF one-to-one onto
	 * chars U+0000-U+00FF, so with compact strings {@code new String(bytes, ISO_8859_1)} performs no
	 * charset conversion at all - it copies the byte array and labels it. What that buys is
	 * {@link String#indexOf}, which is a vectorized JVM intrinsic, over the raw bytes.
	 *
	 * It replaced {@code new String(content)}, which decoded the whole page with the platform charset
	 * - for anything non-ASCII, expanding it into a UTF-16 char array twice the size - purely to
	 * answer a boolean and throw the result away. FrontCacheEngine calls this for EVERY cacheable
	 * response that did not come from another Frontcache, and the overwhelmingly common answer is
	 * "no includes here".
	 *
	 * Measured on a ~166 KB page, per call: 64 us -> 57 us for pure ASCII, and 133 us -> 53 us once
	 * the page contains multi-byte characters. (A hand-written Boyer-Moore-Horspool scan over the raw
	 * bytes was tried and is <em>slower</em> - 89 us / 79 us. It allocates nothing, but no Java loop
	 * beats the intrinsic.)
	 *
	 * <h3>Why it is correct</h3>
	 *
	 * The markers are pure ASCII, and every encoding this can receive - UTF-8, Latin-1, the other
	 * single-byte sets - is ASCII-transparent: an ASCII byte never occurs inside a multi-byte
	 * sequence. So a marker is found exactly when it is really there. It is also strictly more robust
	 * than decoding: a malformed UTF-8 sequence decodes to a replacement char and SHIFTS every later
	 * index, where the Latin-1 mapping is total and index-preserving. (A UTF-16 body would not match -
	 * but neither did the old code, which decoded as UTF-8.)
	 *
	 * One nuance: MAX_INCLUDE_LENGHT is now a distance in bytes rather than in decoded chars. For a
	 * tag of the form {@code <fc:include url="..." />} the two are identical; where they differ the
	 * byte distance is the larger, so this is marginally the more conservative check. It only gates
	 * whether the real parse runs - {@code parseIncludes} is authoritative and applies no such limit.
	 */
	public boolean hasIncludes(WebResponse webResponse, int recursionLevel)
	{
		byte[] content = webResponse.getContent();

		if (null == content)
			return false;

		if (recursionLevel >= MAX_RECURSION_LEVEL)
			return false;

		if (!webResponse.isText()) // includes for text only
			return false;

		String scannable = new String(content, StandardCharsets.ISO_8859_1);

		int startIdx = scannable.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = scannable.indexOf(END_MARKER, startIdx);
			if (-1 < endIdx && (endIdx - startIdx) < MAX_INCLUDE_LENGHT)
			{
				return true;
			}
		}

		return false;

	}

	protected void mergeIncludeResponseHeaders(Map<String, List<String>> outHeaders, Map<String, List<String>> includeResponseHeaders)
	{
		for (String removeKey : NON_MERGEABLE_RESPONSE_HEADERS)
			includeResponseHeaders.remove(removeKey);

		synchronized (outHeaders) {
			for (String name : includeResponseHeaders.keySet()) {
				for (String value : includeResponseHeaders.get(name)) {

					List<String> outHeadersValues = outHeaders.get(name);
					if(null == outHeadersValues)
					{
						outHeadersValues = new ArrayList<String>();
						outHeaders.put(name, outHeadersValues);
					}
					if (!outHeadersValues.contains(value))
						outHeadersValues.add(value);
				}
			}
		}
		return;
	}


	/**
	 *
	 * @param content
	 * @return
	 */
	protected String getIncludeURL(String content)
	{
		logger.debug("include tag - " + content);
		final String START_MARKER = "url=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String urlValue = content.substring(startIdx + START_MARKER.length(), endIdx);
				logger.debug("include URL - " + urlValue);
				return urlValue;
			} else {
				// can't find closing
				return null;
			}


		} else {
			// no url attribute
			return null;
		}

	}

	/**
	 *
	 * @param content
	 * @return
	 */
	protected String getIncludeType(String content)
	{
		logger.debug("include tag - " + content);
		final String START_MARKER = "call=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String typeValue = content.substring(startIdx + START_MARKER.length(), endIdx);

				if (INCLUDE_TYPE_ASYNC.equalsIgnoreCase(typeValue))
				{
					logger.debug("include call-type - " + INCLUDE_TYPE_ASYNC);
					return INCLUDE_TYPE_ASYNC;
				}

			} else {
				// can't find closing
			}
		} else {
			// no type attribute
		}

		logger.debug("include call-type - " + INCLUDE_TYPE_SYNC);
		return INCLUDE_TYPE_SYNC; // default
	}

	protected String getIncludeClientType(String content)
	{
		logger.debug("include tag - " + content);
		final String START_MARKER = "client=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 < startIdx)
		{
			int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
			if (-1 < endIdx)
			{
				String typeValue = content.substring(startIdx + START_MARKER.length(), endIdx);

				if (FCHeaders.REQUEST_CLIENT_TYPE_BOT.equalsIgnoreCase(typeValue))
				{
					logger.debug("include client-type - " + FCHeaders.REQUEST_CLIENT_TYPE_BOT);
					return FCHeaders.REQUEST_CLIENT_TYPE_BOT;
				}
				if (FCHeaders.REQUEST_CLIENT_TYPE_BROWSER.equalsIgnoreCase(typeValue))
				{
					logger.debug("include client-type - " + FCHeaders.REQUEST_CLIENT_TYPE_BROWSER);
					return FCHeaders.REQUEST_CLIENT_TYPE_BROWSER;
				}

			} else {
				// can't find closing
			}
		} else {
			// no type attribute
		}

		return null; // default
	}

	/**
	 * The {@code combine} attribute's value, or null when the include is not marked combinable.
	 *
	 * {@code combine="true"} groups by the include's endpoint alone; any other value adds itself to
	 * the group key, so one endpoint can serve two logically different sets on the same page without
	 * their members being merged into one batch. See docs/archive/combine-reduce-proposal.md section 8.
	 *
	 * Absent means today's behaviour, unchanged: combining is opt-in per include, because it is a
	 * contract with the origin rather than a transparent edge optimisation.
	 */
	protected String getIncludeCombineGroup(String content)
	{
		final String START_MARKER = "combine=\"";
		int startIdx = content.indexOf(START_MARKER);
		if (-1 == startIdx)
			return null; // no combine attribute - not combinable

		int endIdx = content.indexOf("\"", startIdx + START_MARKER.length());
		if (-1 == endIdx)
			return null; // can't find closing

		String combineValue = content.substring(startIdx + START_MARKER.length(), endIdx).trim();

		if (combineValue.isEmpty() || "false".equalsIgnoreCase(combineValue))
			return null;

		logger.debug("include combine group - " + combineValue);
		return combineValue;
	}

	/**
	 * Resolve one include: from cache when it is there, from the origin when it is not.
	 *
	 * The two URLs are the whole of docs/include-fetch-fix.md. {@code publicURL} is this node's own
	 * address for the fragment and is what the entry is KEYED on - rebasing the key would orphan
	 * every existing entry and leave two keys per fragment depending on which path filled it.
	 * {@code fetchURL} is what goes on the wire. They are the same string until
	 * {@code front-cache.include-fetch-target=origin} pulls the fetch off the node's own front door.
	 *
	 * @param publicURL cache key, request log, fallback lookup
	 * @param fetchURL  what is actually requested
	 */
	protected WebResponse callInclude(String publicURL, String fetchURL, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel, String includeType) throws FrontCacheException
    {

		// in case response is from cache -> log request
		// otherwise (response is not cached) it is logged by fetchIncludeFromOrigin when this node
		// fetched the origin itself, and by FrontcacheEngine when the fetch looped back through it

		long start = System.currentTimeMillis();

		WebResponse cachedWebResponse = getIncludeFromCache(publicURL, fetchURL, requestHeaders, client, context, includeLevel, includeType, start);
		if (null != cachedWebResponse)
			return cachedWebResponse;

		return fetchIncludeFromOrigin(publicURL, fetchURL, requestHeaders, client, context, includeLevel, includeType, start);
    }

	/**
	 * The cache half of {@link #callInclude}: the entry to serve, or null when this include must go
	 * to the origin.
	 *
	 * Split out for the combine path, which has to know which members missed <em>before</em> it can
	 * decide what goes into a batch (docs/archive/combine-reduce-proposal.md section 4.3). Everything the
	 * single-include path did on a hit - the client-type cacheability check, soft vs regular
	 * expiration, both request-log writes - still happens here and only here, so the two paths cannot
	 * drift on what a hit means.
	 *
	 * @param start when the include began, so a hit reports the same elapsed time it always did
	 * @param fetchURL used for one thing only - the soft refresh below, which is a fetch. Everything
	 *                 else here keys, logs and serves on {@code publicURL}
	 */
	protected WebResponse getIncludeFromCache(String publicURL, String fetchURL, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel, String includeType, long start)
    {

		String urlStr = publicURL;

		CacheProcessor cacheProcessor = CacheManager.getInstance();

		// The key must be the URL this include is actually FETCHED with, not the URL as written in
		// the tag. fetchIncludeFromOrigin goes out through FCUtils.buildRequestURI, which drops
		// valueless query parameters, and the serving node stores the entry under what it received -
		// so keying the lookup on the raw tag URL means an include carrying an empty parameter
		// (?locale=zh&id=) looks up a key nothing is ever stored under, misses forever, and refetches
		// on every single request. See FCUtils.canonicalizeURL.
		String cacheKey = FCUtils.canonicalizeURL(urlStr);

		WebResponse webResponse = cacheProcessor.getFromCache(cacheKey, context);

		boolean isCacheableForClientType = true;

		boolean softRefresh = false;

		if (null != webResponse)
		{
			String clientType = context.getClientType(); // bot | browser
			Map<String, Long> expireTimeMap = webResponse.getExpireTimeMap();

			isCacheableForClientType = FCUtils.isWebComponentCacheableForClientType(expireTimeMap, clientType);

			// if data is cacheable for client type -> check data for expiration (only)
			// if data is dynamic for client type -> no expiration / invalidation check
			if (isCacheableForClientType && FCUtils.isWebComponentExpired(expireTimeMap, clientType))
			{

				String refreshType = webResponse.getRefreshType();
				if (FCHeaders.COMPONENT_REFRESH_TYPE_SOFT.equalsIgnoreCase(refreshType))
				{
					// Soft expiration. First argument is the cache key, second is what the refresh
					// fetches - the same distinction callInclude draws, and the reason they are not one
					// argument any more: a soft-refreshed include must reach the origin the same way a
					// missing one does, or one fragment has two routes to the origin. Both canonical.
					cacheProcessor.doSoftInvalidation(cacheKey, FCUtils.canonicalizeURL(fetchURL), requestHeaders, client, context);
					softRefresh = true;
				} else {
					// regular expiration
					cacheProcessor.removeFromCache(cacheKey);
					webResponse = null; // refresh from origin
				}
			}
		}


		if (null != webResponse && isCacheableForClientType)
		{
			{ // request logging
				RequestContext contextCopy = new RequestContext();
				contextCopy.putAll(context);
				contextCopy.setRequestType(FCHeaders.COMPONENT_INCLUDE);

				RequestLogger.logRequest(
						urlStr,
						true, // isRequestCacheable
						true, // isCached
						System.currentTimeMillis() - start,
						webResponse.getContentLenth(), // lengthBytes
						contextCopy);

				if (!INCLUDE_TYPE_ASYNC.equals(includeType))
					RequestLogger.logRequestToHeader(
							urlStr,
							includeType, // it's include (sync or asyn)   { toplevel | include | include-async }
							true, // isCached
							softRefresh,
							System.currentTimeMillis() - start,
							webResponse.getContentLenth(), // lengthBytes
							contextCopy, includeLevel);
			}
			return webResponse;
		}

		return null; // not in cache (or not cacheable for this client type) - go to origin
    }

	/**
	 * The origin half of {@link #callInclude}, reached when {@link #getIncludeFromCache} found
	 * nothing serveable.
	 *
	 * <h3>Who caches what this returns</h3>
	 *
	 * Historically: nobody here. The fetch went to this node's own {@code Host}, so it arrived back
	 * at the front door as an ordinary request and {@code CacheProcessorBase.processRequest} stored
	 * it on the way through, and {@code FrontCacheEngine} wrote its request-log line. Fetch the
	 * origin directly and both of those stop happening - so both are done here instead, and only
	 * then ({@link #isDirectOriginFetch}). Miss the cache write and every include becomes an
	 * uncached origin call, forever, with nothing in the logs to say so: failure mode 1 of
	 * docs/include-fetch-fix.md section 8.
	 *
	 * @param publicURL cache key and log line - never the URL this fetched with
	 * @param fetchURL  what goes on the wire
	 * @param start when the include began - including the cache lookup that just missed, so the
	 *              reported time is the whole include and not just its origin leg
	 */
	protected WebResponse fetchIncludeFromOrigin(String publicURL, String fetchURL, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel, String includeType, long start) throws FrontCacheException
    {
		boolean softRefresh = false;

		// Read BEFORE the loopback marker is added below - the two mean opposite things. A caller that
		// marked its own call dynamic (the combine envelope) is saying "this response is not
		// cacheable"; the marker we add is saying "we will cache it ourselves, you must not".
		boolean callerMarkedDynamic = isDynamicRequest(requestHeaders);

		// recursive call to FCServlet, unless include-fetch-target says to go straight to the origin
 		requestHeaders.put(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL, Arrays.asList(new String[]{includeLevel})); // add include-level header

 		// In filter mode the origin is this same node, so this fetch comes back through our own front
 		// door. Tell it not to cache what it serves: this side owns the entry, under the public URL.
 		// See isLoopbackOriginFetch.
 		if (isLoopbackOriginFetch(context))
 			requestHeaders.put(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST, Arrays.asList(new String[]{"true"}));

 		WebResponse outWebResponse =  FCUtils.includeDynamicCallHttpClient(fetchURL, requestHeaders, client, context);

 		// A combined batch's ENVELOPE is not an include leg and gets neither of the two below. It is
 		// never cached - its URL carries whichever members happened to miss, so the set of distinct
 		// envelope URLs for one page is combinatorial in its members - and it is not logged either,
 		// because logCombinedInclude already writes a line per member, which is the accounting that
 		// matches what the page actually used. Logging the envelope too adds one phantom include leg
 		// per batch: measured as 11 such lines in one e2e run before this guard was hoisted here.
 		if (isDirectOriginFetch(context) && !FCHeaders.COMPONENT_COMBINED_INCLUDE.equals(includeType))
 		{
 			// The URL the response reports must be the public one: putToCache is keyed on it, and
 			// FCUtils.httpResponse2WebComponent stamped the FETCH url on the way in. Forget this and
 			// entries land under the origin host - the cache half-works, which is worse than not at
 			// all (failure mode 3).
 			outWebResponse.setUrl(publicURL);

 			cacheInclude(publicURL, outWebResponse, callerMarkedDynamic, context);
 			logIncludeFromOrigin(publicURL, context, includeType, System.currentTimeMillis() - start, outWebResponse.getContentLenth());
 		}

 		// Async includes are resolved fire-and-forget in a background thread and their content is
 		// never part of the client response. By the time this thread runs, the top-level response is
 		// usually already committed and recycled by the container, so setting a trace header here
 		// would throw "Response does not exist (likely recycled)". Skip it - async includes are still
 		// captured in the log files via logRequest().
 		if (!INCLUDE_TYPE_ASYNC.equals(includeType))
			RequestLogger.logRequestToHeader(
					publicURL,
					includeType, // it's include (sync or asyn)   { toplevel | include | include-async }
					false, // isCached
					softRefresh,
					System.currentTimeMillis() - start,
					outWebResponse.getContentLenth(), // lengthBytes
					context, includeLevel);

 		return outWebResponse;
    }

	/**
	 * Store an include this node fetched from the origin itself, under its public URL.
	 *
	 * Reproduces what the loopback request used to be gated by, in the two places that gating lived:
	 * {@code FrontCacheEngine.processRequestInternal} refused to cache a request the CALLER marked
	 * dynamic, and {@code CacheProcessorBase.processRequest} applied
	 * {@link #isIncludeResponseCacheable}. Note that {@code callerMarkedDynamic} is read before this
	 * path adds its own loopback marker - see {@link #isLoopbackOriginFetch}; the two carry opposite
	 * meanings and conflating them would stop filter mode from ever caching an include.
	 *
	 * The status check is this path's substitute for {@code !context.isFallbackUsed()}. That flag
	 * cannot be read here: the {@link RequestContext} belongs to the whole page, so one include's
	 * fallback would stop every later include on the page from ever being cached - the same reason
	 * the combine path leaves it out. A fallback carries 503 instead (see
	 * {@code FileBasedFallbackResolver.markNonCacheable}), and a resolver that sets no status at all
	 * leaves -1, so 2xx-only is the conservative reading either way.
	 */
	private void cacheInclude(String publicURL, WebResponse webResponse, boolean callerMarkedDynamic, RequestContext context)
	{
		if (callerMarkedDynamic)
			return;

		int status = webResponse.getStatusCode();
		if (200 > status || 300 <= status)
			return;

		if (!isIncludeResponseCacheable(webResponse, context))
			return;

		// copy(), because putToCache strips the non-persistent headers from what it is handed and
		// this instance is about to be stitched into the page
		CacheManager.getInstance().putToCache(publicURL, webResponse.copy());
	}

	/** Did the caller mark this call dynamic, the way {@code FrontCacheEngine} reads it off a request? */
	private static boolean isDynamicRequest(Map<String, List<String>> requestHeaders)
	{
		List<String> values = requestHeaders.get(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST);
		if (null == values)
			return false;

		for (String value : values)
			if ("true".equals(value))
				return true;

		return false;
	}

	/**
	 * The request-log line that {@code FrontCacheEngine} writes for an include that loops back, and
	 * that nothing writes once the include stops looping back.
	 *
	 * Losing it breaks nothing and is invisible: includes simply stop appearing in the request log,
	 * and every later measurement of include behaviour reads low. {@link #logCombinedInclude}
	 * documents the same trap for combined members. The {@code dynamic} marker on include legs is
	 * what to watch after a deploy - it must stay in step with {@code Cache-Origin-http}.
	 */
	private void logIncludeFromOrigin(String publicURL, RequestContext context, String includeType, long elapsed, long lengthBytes)
	{
		RequestContext contextCopy = new RequestContext();
		contextCopy.putAll(context);
		contextCopy.setRequestType(INCLUDE_TYPE_ASYNC.equals(includeType)
				? FCHeaders.COMPONENT_ASYNC_INCLUDE
				: FCHeaders.COMPONENT_INCLUDE);

		RequestLogger.logRequest(
				publicURL,
				true,  // isRequestCacheable - it reached here through the cacheable include path
				false, // isCached - this is the miss
				elapsed,
				lengthBytes,
				contextCopy);
	}

	/**
	 * The four conditions an include response must meet before it is stored, shared by the single
	 * include path and the combine path so the two cannot drift on what "cacheable" means. They are
	 * the same decision.
	 *
	 * {@code isCacheable()} on its own is NOT the guard, and that is the trap: it answers true for a
	 * response whose expireTimeMap says NO_CACHE for every client type, because it only checks that
	 * the map is non-empty. A response with no maxage would therefore be stored as an entry that is
	 * expired the instant it is written - churning the index and never serving anything.
	 */
	protected boolean isIncludeResponseCacheable(WebResponse webResponse, RequestContext context)
	{
		if (0 >= webResponse.getContentLenth()) // don't cache empty responses
			return false;

		if (!webResponse.isCacheable())
			return false;

		if (!FCUtils.isWebComponentCacheableForClientType(webResponse.getExpireTimeMap(), context.getClientType()))
			return false;

		return !isDynamicURL(webResponse.getUrl());
	}

	/**
	 * Would {@code dynamic-urls.conf} have stopped the loopback from caching this?
	 *
	 * Matched against the URI path, which is what {@code FrontCacheEngine.ignoreCache} matches - an
	 * include cached on this side must be cacheable exactly when the loopback would have cached it.
	 */
	protected static boolean isDynamicURL(String url)
	{
		String uri = CombineProtocol.pathOf(url);

		for (Pattern p : FCConfig.getDynamicURLPatterns())
			if (p.matcher(uri).find())
				return true;

		return false;
	}

	/**
	 * Request-log and trace-header lines for one member of a combined batch.
	 *
	 * A single include that misses the cache is logged twice: once here as a trace header, and once
	 * by {@code FrontCacheEngine} when the loopback request for it arrives. A combined member has no
	 * loopback request of its own - that is the saving - so without this it would vanish from the
	 * request log entirely, and the log would show one origin request where fifteen fragments were
	 * served. Both lines are therefore written here.
	 *
	 * @param elapsed the BATCH's elapsed time; see {@link FCHeaders#COMPONENT_COMBINED_INCLUDE}
	 */
	protected void logCombinedInclude(String urlStr, RequestContext context, String includeLevel, long elapsed, long lengthBytes)
	{
		RequestContext contextCopy = new RequestContext();
		contextCopy.putAll(context);
		contextCopy.setRequestType(FCHeaders.COMPONENT_INCLUDE);

		RequestLogger.logRequest(
				urlStr,
				true,  // isRequestCacheable
				false, // isCached - a combined member is by construction one that missed
				elapsed,
				lengthBytes,
				contextCopy);

		RequestLogger.logRequestToHeader(
				urlStr,
				FCHeaders.COMPONENT_COMBINED_INCLUDE,
				false, // isCached
				false, // softRefresh
				elapsed,
				lengthBytes,
				contextCopy, includeLevel);
	}

	/**
	 * Reads {@code front-cache.include-fetch-target}. A subclass that overrides this must call
	 * {@code super.init(properties)} to honour the property; one that does not keeps {@code edge},
	 * which is what it does today.
	 */
	@Override
	public void init(Properties properties) {

		if (null == properties)
			return;

		String target = properties.getProperty(INCLUDE_FETCH_TARGET_PROPERTY);
		if (null == target || 0 == target.trim().length())
			return;

		target = target.trim().toLowerCase();

		if (!INCLUDE_FETCH_TARGET_ORIGIN.equals(target) && !INCLUDE_FETCH_TARGET_EDGE.equals(target))
		{
			logger.warn(INCLUDE_FETCH_TARGET_PROPERTY + " is neither '" + INCLUDE_FETCH_TARGET_ORIGIN
					+ "' nor '" + INCLUDE_FETCH_TARGET_EDGE + "' (" + target + ") - using " + includeFetchTarget);
			return;
		}

		includeFetchTarget = target;
		logger.info("include fetch target: " + includeFetchTarget);
	}

	@Override
	public void destroy() {
	}

}

