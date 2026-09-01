/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.include.impl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.frontcache.FCConfig;
import org.frontcache.core.FcHttpClient;
import org.frontcache.FrontCacheEngine;
import org.frontcache.cache.CacheManager;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.include.combine.CombineEnvelope;
import org.frontcache.include.combine.CombinePart;
import org.frontcache.include.combine.CombineProtocol;
import org.frontcache.include.combine.CombineUnsupportedMemo;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.frontcache.include.IncludeProcessor;
import org.frontcache.include.IncludeProcessorBase;

import org.frontcache.resilience.FcCommandException;

/**
 *
 * Processing URL example <fc:include url="/some/url/here" />
 *
 */
public class ConcurrentIncludeProcessor extends IncludeProcessorBase implements IncludeProcessor {

	private int threadAmount = 10; // default 10 thread
	private long timeout = 6*1000; // default 6 second

	/** Content type given to a combined part whose own headers name none. */
	private static final String DEFAULT_PART_CONTENT_TYPE = "text/html;charset=UTF-8";

	// --- combine-and-reduce (docs/combine-reduce-proposal.md) ---------------------------------
	// A kill switch, not the default posture: combining only ever acts on includes that carry a
	// combine="..." attribute, so leaving this true changes nothing for markup that does not ask.
	private boolean combineEnabled = true;
	private int combineMaxBatchSize = 30;
	private int combineMaxUrlLength = 2000;
	private long combineUnsupportedTtl = 60*1000;

	private final CombineUnsupportedMemo combineUnsupportedMemo = new CombineUnsupportedMemo();

    ExecutorService executor = null;

	public ConcurrentIncludeProcessor() {
	}

	@Override
	public void init(Properties properties) {
		try
		{
			String threadAmountStr = properties.getProperty("front-cache.include-processor.impl.concurrent.thread-amount");
			if (null != threadAmountStr && threadAmountStr.trim().length() > 0)
				threadAmount = Integer.parseInt(threadAmountStr);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		logger.info("amount of threads: " + threadAmount);

		try
		{
			String timeoutStr = properties.getProperty("front-cache.include-processor.impl.concurrent.timeout");
			if (null != timeoutStr && timeoutStr.trim().length() > 0)
				timeout = Integer.parseInt(timeoutStr);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		logger.info("timeout: " + timeout);

		combineEnabled        = boolProperty(properties, "combine", combineEnabled);
		combineMaxBatchSize   = intProperty(properties,  "combine.max-batch-size", combineMaxBatchSize);
		combineMaxUrlLength   = intProperty(properties,  "combine.max-url-length", combineMaxUrlLength);
		combineUnsupportedTtl = intProperty(properties,  "combine.unsupported-ttl", (int) combineUnsupportedTtl);
		combineUnsupportedMemo.clear(); // a reload may have changed the TTL - do not carry old deadlines

		logger.info("combine includes: " + combineEnabled
				+ " (max batch " + combineMaxBatchSize
				+ ", max url length " + combineMaxUrlLength
				+ ", unsupported ttl " + combineUnsupportedTtl + "ms)");

	    executor = Executors.newFixedThreadPool(threadAmount);
	}

	private int intProperty(Properties properties, String suffix, int defaultValue) {
		String value = properties.getProperty("front-cache.include-processor.impl.concurrent." + suffix);
		if (null == value || 0 == value.trim().length())
			return defaultValue;

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			logger.warn("front-cache.include-processor.impl.concurrent." + suffix + " is not a number (" + value + ") - using " + defaultValue);
			return defaultValue;
		}
	}

	private boolean boolProperty(Properties properties, String suffix, boolean defaultValue) {
		String value = properties.getProperty("front-cache.include-processor.impl.concurrent." + suffix);
		if (null == value || 0 == value.trim().length())
			return defaultValue;

		return "true".equalsIgnoreCase(value.trim());
	}

	@Override
	public void destroy() {
		executor.shutdown();
	}

	/**
	 *
	 * @param content
	 * @param hostURL
	 * @return
	 */
	public WebResponse processIncludes(WebResponse parentWebResponse, String hostURL, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, int recursionLevel)
	{
		// The ONE decode of the parent page per recursion level. hasIncludes() used to decode it too,
		// immediately before this, purely to test for the marker - it now scans the bytes instead
		// (IncludeProcessorBase.hasIncludes), so this is no longer the second copy of the same page.
		String contentStr = new String(parentWebResponse.getContent(), StandardCharsets.UTF_8);
		List<IncludeResolutionPlaceholder> includes = parseIncludes(contentStr, hostURL, requestHeaders, client, context);

		if (null == includes)
			return parentWebResponse;

        List<Future<?>> futureList = new ArrayList<Future<?>>(includes.size()); // for sync includes

        // Combinable includes are held back rather than submitted: which of them go into a batch
        // depends on which of them missed the cache, and that is not known yet. See section 4.3 of
        // docs/combine-reduce-proposal.md - everything else is submitted first, so the non-combinable
        // includes of a page overlap with the combinables' cache probe instead of queueing behind it.
        List<IncludeResolutionPlaceholder> combinables = null;

        for (int i=0; i < includes.size(); i++)
		{
        	IncludeResolutionPlaceholder inc = includes.get(i);
        	inc.includeLevel = "" + recursionLevel + "." + i;

			boolean performInclude = false; // check for client specific include
			if (null == inc.includeClientType)
			{
				performInclude = true;

			} else if (inc.includeClientType.equalsIgnoreCase(context.getClientType())) {

				// client type specific include
				performInclude = true;
			} else {

				// client type for include and request doesnt match
				performInclude = false;
			}

        	inc.performInclude = performInclude; // save for include resolution

			if (!performInclude)
				continue;

			if (INCLUDE_TYPE_ASYNC.equals(inc.includeType))
			{
				// run concurrent include resolution
				// for Async includes - do NOT wait for response from Origin
				// response from Origin is NOT included to response to client
				// useful for counters - e.g. response totally from cache (fast) and async call to origin
				executor.submit(inc);
				continue;
			}

			if (isCombinable(inc))
			{
				if (null == combinables)
					combinables = new ArrayList<IncludeResolutionPlaceholder>();

				combinables.add(inc);
				continue;
			}

			// run concurrent include resolution
			// for Sync includes - wait for response from Origin (until timeout)
			// origin responses are sent to client
			futureList.add(executor.submit(inc));
		}

        if (null != combinables)
        	futureList.addAll(submitCombined(combinables, requestHeaders, client, context, recursionLevel));

		// processing timeouts for Sync includes
        boolean timeoutReached = false;
        for (Future<?> f : futureList)
        {
            try {
            	if (timeoutReached)
            		f.get(1, TimeUnit.MILLISECONDS);
            	else
            		f.get(timeout, TimeUnit.MILLISECONDS);

            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                f.cancel(true);
                timeoutReached =  true;
                logger.debug("timeout (" + timeout + ") reached for resolving includes. Some includes may not be resolved ");
            }
        }

        // replace placeholders with content
		WebResponse agregatedWebResponse = replaceIncludePlaceholders(contentStr, includes);

		return agregatedWebResponse;
	}

	/**
	 * Can this include take part in a combined origin call?
	 *
	 * Four ways to answer no, in the order they are cheapest to check. The reserved-parameter refusal
	 * is the one worth explaining: an include whose own query carries an {@code fc-} parameter would
	 * arrive at the origin beside the ones the protocol puts there, and the origin has no way to tell
	 * them apart - so that include is fetched on its own rather than combined into something
	 * ambiguous.
	 */
	private boolean isCombinable(IncludeResolutionPlaceholder inc)
	{
		if (!combineEnabled)
			return false;

		if (null == inc.combineGroup)
			return false;

		if (CombineProtocol.hasReservedParam(CombineProtocol.queryOf(inc.includeURL)))
		{
			logger.warn("include " + inc.includeURL + " carries a reserved '" + CombineProtocol.PARAM_PREFIX
					+ "' query parameter and cannot be combined - resolving it individually");
			return false;
		}

		if (combineUnsupportedMemo.isUnsupported(CombineProtocol.groupKey(inc.includeURL, inc.combineGroup)))
		{
			logger.debug("combine is memoised as unsupported for " + inc.includeURL + " - resolving it individually");
			return false;
		}

		return true;
	}

	/**
	 * Probe the cache, group what missed, and submit one batch per chunk.
	 *
	 * @return futures the caller must await alongside the individually-submitted includes
	 */
	private List<Future<?>> submitCombined(List<IncludeResolutionPlaceholder> combinables, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, int recursionLevel)
	{
		probeCache(combinables);

		// group key -> member URL -> the placeholders wanting that URL.
		// Two placeholders for the same URL send one part and share one answer; both maps are ordered
		// so the batch's parts arrive at the origin in page order, which is what the response is
		// positional against.
		Map<String, Map<String, List<IncludeResolutionPlaceholder>>> groups =
				new LinkedHashMap<String, Map<String, List<IncludeResolutionPlaceholder>>>();

		for (IncludeResolutionPlaceholder inc : combinables)
		{
			if (null != inc.webResponse)
				continue; // the probe served it from cache - nothing to ask the origin for

			String groupKey = CombineProtocol.groupKey(inc.includeURL, inc.combineGroup);

			Map<String, List<IncludeResolutionPlaceholder>> byUrl = groups.get(groupKey);
			if (null == byUrl)
			{
				byUrl = new LinkedHashMap<String, List<IncludeResolutionPlaceholder>>();
				groups.put(groupKey, byUrl);
			}

			List<IncludeResolutionPlaceholder> members = byUrl.get(inc.includeURL);
			if (null == members)
			{
				members = new ArrayList<IncludeResolutionPlaceholder>(1);
				byUrl.put(inc.includeURL, members);
			}
			members.add(inc);
		}

		List<Future<?>> futures = new ArrayList<Future<?>>();
		int batchIdx = 0;

		for (Map.Entry<String, Map<String, List<IncludeResolutionPlaceholder>>> group : groups.entrySet())
		{
			Map<String, List<IncludeResolutionPlaceholder>> byUrl = group.getValue();

			// A group down to one distinct URL is not worth a batch: the origin does exactly the same
			// work either way, and the ordinary include path reaches it in one hop instead of two and
			// caches it through the loopback rather than by hand. This is the steady state for a page
			// whose fragments are nearly all cached.
			if (1 == byUrl.size())
			{
				for (List<IncludeResolutionPlaceholder> members : byUrl.values())
					for (IncludeResolutionPlaceholder member : members)
						futures.add(executor.submit(member));

				continue;
			}

			for (List<String> chunk : chunkUrls(byUrl.keySet()))
			{
				String includeLevel = "" + recursionLevel + ".c" + (batchIdx++);

				// One header map per batch, made here on the request thread and owned by the batch -
				// the same rule the per-include copies follow, and for the same reason: the batch adds
				// trace headers of its own, and the parent's map is only ever read after parse.
				CombinedIncludeBatch batch = new CombinedIncludeBatch(
						group.getKey(),
						chunk,
						byUrl,
						new HashMap<String, List<String>>(requestHeaders),
						client, context, includeLevel);

				futures.add(executor.submit(batch));
			}
		}

		return futures;
	}

	/**
	 * Resolve every combinable include against the cache, in parallel, before deciding what to batch.
	 *
	 * On the include pool rather than inline on the request thread: cache lookups run under the
	 * Cache-Hits command, which is SEMAPHORE-isolated and therefore executes on whichever thread
	 * calls it - so doing fifteen of them here would serialise fifteen L2 Lucene reads that today run
	 * concurrently. {@code invokeAll} returns when the last member reports, which is the same instant
	 * a per-member coordinator would have fired the batch, for a fraction of the machinery.
	 */
	private void probeCache(List<IncludeResolutionPlaceholder> combinables)
	{
		List<Callable<Void>> probes = new ArrayList<Callable<Void>>(combinables.size());
		for (IncludeResolutionPlaceholder inc : combinables)
			probes.add(inc.cacheProbe());

		try {
			executor.invokeAll(probes, timeout, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			logger.debug("interrupted while probing the cache for combinable includes");
		}
	}

	/**
	 * Splits a group's member URLs into batches, on whichever cap is reached first.
	 *
	 * Both caps exist because both failure modes are real: a page with two hundred combinable
	 * includes must not become one two-hundred-member origin call (memory at the origin, and one slow
	 * member holding up all of them), and the combined query must not outgrow the request-line limit
	 * of whatever proxy sits in the path - which fails as a 414 that looks like nothing else.
	 */
	private List<List<String>> chunkUrls(Collection<String> urls)
	{
		List<List<String>> chunks = new ArrayList<List<String>>();

		List<String> current = new ArrayList<String>();
		int currentLength = 0;

		for (String url : urls)
		{
			// "&fc-part-<i>=" plus the encoded query. The index is over-allowed at 4 digits, which is
			// well past max-batch-size and keeps this a cheap estimate rather than a formatting step.
			int partLength = CombineProtocol.PARAM_PART.length() + 6
					+ CombineProtocol.encodePart(CombineProtocol.queryOf(url)).length();

			if (!current.isEmpty()
					&& (current.size() >= combineMaxBatchSize || currentLength + partLength > combineMaxUrlLength))
			{
				chunks.add(current);
				current = new ArrayList<String>();
				currentLength = 0;
			}

			current.add(url);
			currentLength += partLength;
		}

		if (!current.isEmpty())
			chunks.add(current);

		return chunks;
	}

	/**
	 * Would {@code dynamic-urls.conf} have stopped the loopback from caching this member?
	 *
	 * Matched against the URI path, which is what {@code FrontCacheEngine.ignoreCache} matches - a
	 * combined member must be cacheable exactly when a single include for the same URL would have
	 * been.
	 */
	private static boolean isDynamicURL(String url)
	{
		String uri = CombineProtocol.pathOf(url);

		for (Pattern p : FCConfig.getDynamicURLPatterns())
			if (p.matcher(uri).find())
				return true;

		return false;
	}

	/**
	 *
	 * @param content
	 * @param hostURL
	 * @return
	 */
	private List<IncludeResolutionPlaceholder> parseIncludes(String content, String hostURL, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context)
	{
		List<IncludeResolutionPlaceholder> includes = null;

		int scanIdx = 0;
		while(scanIdx < content.length())
		{
			int startIdx = content.indexOf(START_MARKER, scanIdx);
			if (-1 < startIdx)
			{
				int endIdx = content.indexOf(END_MARKER, startIdx);
				if (-1 < endIdx)
				{
					endIdx = endIdx + END_MARKER.length();
					String includeTagStr = content.substring(startIdx, endIdx);
					String includeURL = getIncludeURL(includeTagStr);
					String includeType = getIncludeType(includeTagStr);

					// Every include gets its OWN copy of the headers, made here on the request thread
					// before any task is submitted. The includes of one page are resolved concurrently,
					// and each of them adds trace headers to the map it was handed - the include level
					// (IncludeProcessorBase.callInclude), the request id and the client IP
					// (FCUtils.includeDynamicCallHttpClient). Sharing the parent's map used to mean N
					// pool threads mutating one HashMap: entries written for one include leaked into
					// the next one's request, and a concurrent read of that map (the snapshot in
					// CacheProcessorBase.doSoftInvalidation) threw ConcurrentModificationException.
					// With a copy per include the parent map is only ever read after this point.
					Map<String, List<String>> includeRequestHeaders = new HashMap<String, List<String>>(requestHeaders);
					if (INCLUDE_TYPE_ASYNC.equals(includeType))
					{
						includeRequestHeaders.put(FCHeaders.X_FRONTCACHE_ASYNC_INCLUDE, Arrays.asList(new String[]{"true"}));
						// The client IP was resolved on the request thread while the servlet request was
						// still live (FrontCacheEngine.init) and cached on the context. Async includes
						// run later in a background thread, by which time the request is recycled and
						// the IP would be unrecoverable (see FCUtils.getClientIP).
						includeRequestHeaders.put(FCHeaders.X_FRONTCACHE_CLIENT_IP,
								Arrays.asList(new String[]{ context.getClientIP() }));
					}

					String includeClientType = getIncludeClientType(includeTagStr);

					// null unless the markup says combine="..."; an async include is never combined,
					// because nothing waits for it and its content never reaches the client, so
					// batching it buys nothing and would give it a shared deadline it does not have.
					String combineGroup = INCLUDE_TYPE_ASYNC.equals(includeType) ? null : getIncludeCombineGroup(includeTagStr);

					if (null == includes)
						includes = new ArrayList<IncludeResolutionPlaceholder>();

					// save placeholder
					includes.add(new IncludeResolutionPlaceholder(startIdx, endIdx, hostURL + includeURL, includeType, includeClientType, combineGroup, includeRequestHeaders, client, context));

					scanIdx = endIdx;
				} else {
					// can't find closing
					scanIdx = content.length(); // scan complete
				}
			} else {
				scanIdx = content.length(); // scan complete
			}
		}

		return includes;
	}

	/**
	 *
	 * @param content
	 * @param includes
	 * @return
	 */
	private WebResponse replaceIncludePlaceholders(String content, List<IncludeResolutionPlaceholder> includes)
	{
		int scanIdx = 0;

		// paste content from includes to output doc
		WebResponse webResponse = new WebResponse("aggregation in " + this.getClass().getName());

		// StringBuilder, not StringBuffer: this is thread-confined, and StringBuffer synchronized every
		// single append. Pre-sized from the page plus its resolved includes, so assembling a large page
		// does not walk the array-doubling ladder, copying everything written so far at each step.
		StringBuilder outSb = new StringBuilder(estimateAggregatedLength(content, includes));
		for (int i = 0; i < includes.size(); i++)
		{
			IncludeResolutionPlaceholder inc = includes.get(i);

	        if (null != inc.webResponse) {
	        	logger.debug("include "  + inc.includeURL + " has content");

	        } else if (!inc.performInclude) {
	        	// it's client specific include and should not be performed -> replace include tag with blank string
	        } else {
				// dont get fallback for async calls
				// when async call -> webResponse is null because we don't wait for response
				// get fallback for SYNC includes only
				if (!INCLUDE_TYPE_ASYNC.equals(inc.includeType))
				{
		        	logger.debug("include detais "  + inc.includeURL + " content is not resolved due to timeout (" + timeout + ")  getting defaults");
		        	inc.webResponse = FallbackResolverFactory.getInstance().getFallback(this.getClass().getName(), inc.includeURL);
				}
	        }


			// append(CharSequence, start, end) copies the chars straight into the builder;
			// append(substring(...)) allocated an intermediate String per segment.
			outSb.append(content, scanIdx, inc.startIdx);

			if (INCLUDE_TYPE_SYNC.equals(inc.includeType))
			{
				if (FrontCacheEngine.debugComments)
					outSb.append("<!-- start fc:include ").append(inc.includeURL).append(" -->");

				if (null != inc.webResponse)
					// explicit UTF-8: this was the platform default, which is only incidentally the same
					outSb.append(new String(inc.webResponse.getContent(), StandardCharsets.UTF_8));

				if (FrontCacheEngine.debugComments)
					outSb.append("<!-- end fc:include ").append(inc.includeURL).append(" -->");

				if (null != inc.webResponse)
					mergeIncludeResponseHeaders(webResponse.getHeaders(), inc.webResponse.getHeaders());
			}

			scanIdx = inc.endIdx;
		}
		outSb.append(content, scanIdx, content.length()); // data after the last include
		webResponse.setContent(outSb.toString().getBytes(StandardCharsets.UTF_8));
		return webResponse;
	}

	/**
	 * Capacity hint for the aggregated page: the parent's own characters, plus each resolved include's
	 * body, minus nothing for the tags they replace. Deliberately a slight over-estimate - one
	 * allocation that is a few percent too large beats the copy-everything-so-far doubling it avoids.
	 * Include bodies are measured in bytes and appended as chars, which for HTML is close enough for a
	 * capacity hint.
	 */
	private static int estimateAggregatedLength(String content, List<IncludeResolutionPlaceholder> includes)
	{
		long estimate = content.length();
		for (IncludeResolutionPlaceholder inc : includes)
			if (null != inc.webResponse && null != inc.webResponse.getContent())
				estimate += inc.webResponse.getContent().length;

		return (int) Math.min(estimate, Integer.MAX_VALUE - 8);
	}

	/**
	 *
	 *
	 *
	 */
	class IncludeResolutionPlaceholder implements Callable<IncludeResolutionPlaceholder> {
		int startIdx;
		int endIdx;
		String includeURL;
		String includeType; // sync|async
		String includeClientType; // bot|browser
		String combineGroup; // null unless the markup says combine="..."
		WebResponse webResponse;
		Map<String, List<String>> requestHeaders;
		FcHttpClient client;
		RequestContext context;
		boolean performInclude = true;
		String includeLevel = null;

		public IncludeResolutionPlaceholder(int startIdx, int endIdx, String includeURL, String includeType, String includeClientType, String combineGroup, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) {
			super();
			this.startIdx = startIdx;
			this.endIdx = endIdx;
			this.includeURL = includeURL;
			this.includeType = includeType;
			this.includeClientType = includeClientType;
			this.combineGroup = combineGroup;
			this.requestHeaders = requestHeaders;
			this.client = client;
			this.context = context;
		}

		/**
		 * The cache half of this include, as a task - see {@link #probeCache}. Never throws: a probe
		 * that fails is a miss, and a miss is a member of the next batch.
		 */
		Callable<Void> cacheProbe() {
			return new Callable<Void>() {
				@Override
				public Void call() {
					try {
						webResponse = getIncludeFromCache(includeURL, requestHeaders, client, context,
								includeLevel, includeType, System.currentTimeMillis());
					} catch (Throwable e) {
						logger.error("unexpected error probing cache for include " + includeURL, e);
					}
					return null;
				}
			};
		}

	    @Override
	    public IncludeResolutionPlaceholder call() throws Exception {

			try {
				this.webResponse = callInclude(this.includeURL, this.requestHeaders, this.client, this.context, this.includeLevel, this.includeType);

			} catch (FrontCacheException e) {
				logger.error("FrontCacheException: unexpected error processing include " + includeURL, e);
			} catch (FcCommandException e) {
				// Reached only when the command failed AND its fallback failed or was absent: FcCommand
				// resolves the fallback from its own catch block, on this thread, so the "in multi
				// threaded environment hystrix does not resolve fallback ??" case this comment used to work
				// around cannot happen any more. The manual resolution in the finally block below is
				// kept as the answer for the remaining case, where the fallback itself failed.
				logger.error("FcCommandException: unexpected error processing include " + includeURL, e);
			} catch (Throwable e) {
				logger.error("Throwable: unexpected error processing include " + includeURL, e);
			} finally {
				if (null == this.webResponse)
				{
					// dont get fallback for async calls
					// when async call -> webResponse is null because we don't wait for response
					// get fallback for SYNC includes only
					if (!INCLUDE_TYPE_ASYNC.equals(includeType))
						this.webResponse = FallbackResolverFactory.getInstance().getFallback(this.getClass().getName(), includeURL);
				}
			}

	        return this;
	    }

	}

	/**
	 * One combined origin call: N cache-missing members of one group, asked for and answered together.
	 * See docs/combine-reduce-proposal.md.
	 *
	 * Leaving a member's {@code webResponse} null is a valid outcome and the way every failure here is
	 * reported: {@link #replaceIncludePlaceholders} resolves a fallback for any sync include still
	 * unresolved when the page is assembled, keyed on that member's own URL - so
	 * {@code fallbacks.conf} entries written for single includes keep working untouched.
	 */
	class CombinedIncludeBatch implements Callable<CombinedIncludeBatch> {

		private final String groupKey;
		private final String endpointURL;                                    // edge-facing, no query
		private final List<String> urls;                                     // distinct member URLs, in order
		private final Map<String, List<IncludeResolutionPlaceholder>> byUrl; // URL -> placeholders sharing it
		private final Map<String, List<String>> requestHeaders;              // owned by this batch
		private final FcHttpClient client;
		private final RequestContext context;
		private final String includeLevel;

		CombinedIncludeBatch(String groupKey, List<String> urls, Map<String, List<IncludeResolutionPlaceholder>> byUrl,
				Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context, String includeLevel) {
			this.groupKey = groupKey;
			this.endpointURL = CombineProtocol.endpointOf(urls.get(0));
			this.urls = urls;
			this.byUrl = byUrl;
			this.requestHeaders = requestHeaders;
			this.client = client;
			this.context = context;
			this.includeLevel = includeLevel;
		}

		@Override
		public CombinedIncludeBatch call() {

			long start = System.currentTimeMillis();

			try {
				resolve(start);
			} catch (Throwable e) {
				logger.error("unexpected error resolving combined include batch " + endpointURL
						+ " (" + urls.size() + " members)", e);
			}

			return this;
		}

		private void resolve(long start) throws FrontCacheException {

			List<String> queries = new ArrayList<String>(urls.size());
			for (String url : urls)
				queries.add(CombineProtocol.queryOf(url));

			String combinedURL = endpointURL + CombineProtocol.buildCombinedQuery(queries);

			// The envelope must be cached by NOBODY. Its URL carries whichever members happened to
			// miss, so the set of distinct combined URLs for one page is combinatorial in its members -
			// an entry per subset, every one of them useless on the next request. And the reference
			// topology has two Frontcache nodes on the path, either of which would store it. This is
			// the existing signal for exactly that, understood by every node: bypass the cache and
			// forward. See section 4.1 of the proposal.
			requestHeaders.put(FCHeaders.X_FRONTCACHE_DYNAMIC_REQUEST, Arrays.asList(new String[]{"true"}));

			WebResponse envelopeResponse = fetchIncludeFromOrigin(combinedURL, requestHeaders, client, context,
					includeLevel, FCHeaders.COMPONENT_COMBINED_INCLUDE, start);

			if (null == envelopeResponse || null == envelopeResponse.getContent())
			{
				logger.error("combined call to " + combinedURL + " returned nothing - its " + urls.size()
						+ " members fall back individually");
				return;
			}

			if (null == envelopeResponse.getHeader(CombineProtocol.HEADER_COMBINE))
			{
				handleNoEnvelope(envelopeResponse);
				return;
			}

			CombineEnvelope envelope;
			try {
				envelope = CombineEnvelope.fromJson(envelopeResponse.getContent());
			} catch (Exception e) {
				logger.error("malformed combine envelope from " + combinedURL + " - its " + urls.size()
						+ " members fall back individually", e);
				return;
			}

			List<CombinePart> parts = envelope.getParts();
			if (null == parts || parts.size() != urls.size())
			{
				logger.error("combine envelope from " + combinedURL + " has "
						+ (null == parts ? "no" : "" + parts.size()) + " parts for " + urls.size()
						+ " members - its members fall back individually");
				return;
			}

			// Echoes are checked for the WHOLE envelope before anything is applied. A mismatch means
			// the origin reordered its results, which invalidates every part, not the one that
			// happened to be noticed - and applying the good ones first would cache fragments under
			// the wrong keys, which nothing later would detect.
			if (!echoesMatch(parts, queries, combinedURL))
				return;

			long elapsed = System.currentTimeMillis() - start;
			for (int i = 0; i < urls.size(); i++)
				applyPart(urls.get(i), parts.get(i), elapsed);
		}

		/**
		 * A 2xx with no {@code x-frontcache-combine} header is an origin that does not implement
		 * combining: it answered the request as an ordinary one. Anything else - a fallback
		 * WebResponse, which carries no status - is a failure, and must NOT be memoised as
		 * "unsupported", or a passing origin outage would disable combining for a minute afterwards.
		 */
		private void handleNoEnvelope(WebResponse envelopeResponse) {

			int status = envelopeResponse.getStatusCode();

			if (200 > status || 300 <= status)
			{
				logger.error("combined call to " + endpointURL + " failed (status " + status + ") - its "
						+ urls.size() + " members fall back individually");
				return;
			}

			combineUnsupportedMemo.mark(groupKey, combineUnsupportedTtl);
			logger.warn("origin does not implement combine for " + endpointURL
					+ " - resolving its " + urls.size() + " members individually, and not combining this group for "
					+ combineUnsupportedTtl + "ms");

			resolveMembersInline();
		}

		/**
		 * Resolve this batch's members one by one, on this thread.
		 *
		 * Inline and sequential on purpose. Submitting them to {@code executor} and waiting would be
		 * faster and is a fixed-pool deadlock waiting to happen - this task already occupies one of
		 * that pool's threads, and enough concurrent batches doing the same would leave every thread
		 * waiting on work that has nowhere to run. The serialisation is bounded by max-batch-size and
		 * happens at most once per group per unsupported-ttl.
		 */
		private void resolveMembersInline() {

			for (String url : urls)
				for (IncludeResolutionPlaceholder member : byUrl.get(url))
				{
					try {
						member.call();
					} catch (Exception e) {
						logger.error("error resolving combined member " + url + " individually", e);
					}
				}
		}

		private boolean echoesMatch(List<CombinePart> parts, List<String> queries, String combinedURL) {

			for (int i = 0; i < parts.size(); i++)
			{
				CombinePart part = parts.get(i);
				if (null == part)
				{
					logger.error("combine envelope from " + combinedURL + " has a null part at " + i
							+ " - its members fall back individually");
					return false;
				}

				// Optional, and verified when present - proposal section 18, decision 1.
				String echo = part.getQ();
				if (null != echo && !echo.equals(queries.get(i)))
				{
					logger.error("combine envelope from " + combinedURL + " echoed \"" + echo + "\" at position "
							+ i + " where \"" + queries.get(i) + "\" was sent - the origin's parts are out of order;"
							+ " its members fall back individually");
					return false;
				}
			}

			return true;
		}

		/**
		 * Turn one part into the {@link WebResponse} a single include for the same URL would have
		 * produced, cache it under that URL, and hand it to every placeholder waiting for it.
		 *
		 * The cache key is the member's own edge-facing URL, NOT the combined URL this was fetched
		 * with. That asymmetry is the whole feature's one silent failure mode: get it wrong and the
		 * cache splits into combined writes nothing reads and single-include reads that never hit,
		 * with no exception anywhere and no symptom but a hit rate that never rises.
		 */
		private void applyPart(String url, CombinePart part, long elapsed) {

			List<IncludeResolutionPlaceholder> members = byUrl.get(url);

			if (200 > part.getStatus() || 300 <= part.getStatus())
			{
				logger.warn("combined member " + url + " answered " + part.getStatus()
						+ " - not cached, that member falls back");
				return;
			}

			byte[] body = (null == part.getBody())
					? new byte[0]
					: part.getBody().getBytes(StandardCharsets.UTF_8);

			// Case-insensitive, like every header map FCUtils.revertHeaders builds: header names are
			// case-insensitive (RFC 7230), so an origin writing "content-type" must mean what one
			// writing "Content-Type" means - and WebResponse.isCacheable() reads it by name.
			Map<String, List<String>> headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
			if (null != part.getHeaders())
				for (Map.Entry<String, List<String>> header : part.getHeaders().entrySet())
					if (null != header.getValue())
						headers.put(header.getKey(), new ArrayList<String>(header.getValue()));

			if (!headers.containsKey(FCHeaders.CONTENT_TYPE))
				headers.put(FCHeaders.CONTENT_TYPE, Arrays.asList(new String[]{DEFAULT_PART_CONTENT_TYPE}));

			WebResponse webResponse = FCUtils.buildWebComponent(url, body, headers);
			webResponse.setStatusCode(part.getStatus());

			if (isPartCacheable(webResponse, body))
				// copy(), because putToCache strips the non-persistent headers from what it is handed
				// and this instance is about to be served.
				CacheManager.getInstance().putToCache(url, webResponse.copy());

			for (IncludeResolutionPlaceholder member : members)
			{
				member.webResponse = webResponse;
				logCombinedInclude(url, context, member.includeLevel, elapsed, body.length);
			}
		}

		/**
		 * The same four conditions {@code CacheProcessorBase.processRequest} applies to an origin
		 * response before storing it, applied to a part.
		 *
		 * {@code isCacheable()} on its own is NOT the guard, and that is the trap: it answers true for
		 * a response whose expireTimeMap says NO_CACHE for every client type, because it only checks
		 * that the map is non-empty. A part with no maxage would therefore be stored as an entry that
		 * is expired the instant it is written - churning the index and never serving anything.
		 *
		 * One condition of the four is deliberately not carried over: {@code !isFallbackUsed()}. There
		 * it reads a context belonging to that one loopback request; here the context belongs to the
		 * whole page, so an unrelated include's fallback would stop this batch's parts from ever being
		 * cached. This batch is known not to be a fallback - it is a 2xx that carried the combine
		 * header, which no fallback resolver produces.
		 */
		private boolean isPartCacheable(WebResponse webResponse, byte[] body) {

			if (0 == body.length) // don't cache empty responses
				return false;

			if (!webResponse.isCacheable())
				return false;

			if (!FCUtils.isWebComponentCacheableForClientType(webResponse.getExpireTimeMap(), context.getClientType()))
				return false;

			return !isDynamicURL(webResponse.getUrl());
		}
	}
}
