/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frontcache.core.FcHttpClient;
import org.frontcache.cache.CacheManager;
import org.frontcache.cache.CacheProcessor;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
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


	private static final String[] NON_MERGEABLE_RESPONSE_HEADERS = new String[]{
			FCHeaders.X_FRONTCACHE_TRACE_REQUEST
		};

	public IncludeProcessorBase() {
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
	 *
	 * @param urlStr
	 * @param requestHeaders
	 * @param client
	 * @return
	 */
	protected WebResponse callInclude(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel, String includeType) throws FrontCacheException
    {

		// in case response is from cache -> log request
		// otherwise (response is not cached) it will be logged in FrontcacheEngine

		long start = System.currentTimeMillis();

		WebResponse cachedWebResponse = getIncludeFromCache(urlStr, requestHeaders, client, context, includeLevel, includeType, start);
		if (null != cachedWebResponse)
			return cachedWebResponse;

		return fetchIncludeFromOrigin(urlStr, requestHeaders, client, context, includeLevel, includeType, start);
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
	 */
	protected WebResponse getIncludeFromCache(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel, String includeType, long start)
    {

		CacheProcessor cacheProcessor = CacheManager.getInstance();
		WebResponse webResponse = cacheProcessor.getFromCache(urlStr, context);

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
					// soft expiration
					cacheProcessor.doSoftInvalidation(urlStr, urlStr, requestHeaders, client, context);
					softRefresh = true;
				} else {
					// regular expiration
					cacheProcessor.removeFromCache(urlStr);
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
	 * @param start when the include began - including the cache lookup that just missed, so the
	 *              reported time is the whole include and not just its origin leg
	 */
	protected WebResponse fetchIncludeFromOrigin(String urlStr, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel, String includeType, long start) throws FrontCacheException
    {
		boolean softRefresh = false;

		// recursive call to FCServlet
 		requestHeaders.put(FCHeaders.X_FRONTCACHE_INCLUDE_LEVEL, Arrays.asList(new String[]{includeLevel})); // add include-level header

 		WebResponse outWebResponse =  FCUtils.includeDynamicCallHttpClient(urlStr, requestHeaders, client, context);

 		// Async includes are resolved fire-and-forget in a background thread and their content is
 		// never part of the client response. By the time this thread runs, the top-level response is
 		// usually already committed and recycled by the container, so setting a trace header here
 		// would throw "Response does not exist (likely recycled)". Skip it - async includes are still
 		// captured in the log files via logRequest().
 		if (!INCLUDE_TYPE_ASYNC.equals(includeType))
			RequestLogger.logRequestToHeader(
					urlStr,
					includeType, // it's include (sync or asyn)   { toplevel | include | include-async }
					false, // isCached
					softRefresh,
					System.currentTimeMillis() - start,
					outWebResponse.getContentLenth(), // lengthBytes
					context, includeLevel);

 		return outWebResponse;
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

	@Override
	public void init(Properties properties) {
	}

	@Override
	public void destroy() {
	}

}

