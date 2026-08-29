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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.frontcache.core.FcHttpClient;
import org.frontcache.FrontCacheEngine;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
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

	    executor = Executors.newFixedThreadPool(threadAmount);
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

        List<Future<IncludeResolutionPlaceholder>> futureList = new ArrayList<Future<IncludeResolutionPlaceholder>>(includes.size()); // for sync includes

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

			if (performInclude)
			{
				if (INCLUDE_TYPE_ASYNC.equals(inc.includeType))
				{
					// run concurrent include resolution
					// for Async includes - do NOT wait for response from Origin
					// response from Origin is NOT included to response to client
					// useful for counters - e.g. response totally from cache (fast) and async call to origin
					executor.submit(inc);
				} else {
					// run concurrent include resolution
					// for Sync includes - wait for response from Origin (until timeout)
					// origin responses are sent to client
		            futureList.add(executor.submit(inc));
				}
			} // if (performInclude)
		}

		// processing timeouts for Sync includes
        boolean timeoutReached = false;
        for (Future<IncludeResolutionPlaceholder> f : futureList)
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

					Map<String, List<String>> includeRequestHeaders = requestHeaders;
					if (INCLUDE_TYPE_ASYNC.equals(includeType))
					{
						// create a copy and extend it
						includeRequestHeaders = new HashMap<String, List<String>>();
						includeRequestHeaders.putAll(requestHeaders);
						includeRequestHeaders.put(FCHeaders.X_FRONTCACHE_ASYNC_INCLUDE, Arrays.asList(new String[]{"true"}));
						// The client IP was resolved on the request thread while the servlet request was
						// still live (FrontCacheEngine.init) and cached on the context. Async includes
						// run later in a background thread, by which time the request is recycled and
						// the IP would be unrecoverable (see FCUtils.getClientIP).
						includeRequestHeaders.put(FCHeaders.X_FRONTCACHE_CLIENT_IP,
								Arrays.asList(new String[]{ context.getClientIP() }));
					}

					String includeClientType = getIncludeClientType(includeTagStr);

					if (null == includes)
						includes = new ArrayList<IncludeResolutionPlaceholder>();

					// save placeholder
					includes.add(new IncludeResolutionPlaceholder(startIdx, endIdx, hostURL + includeURL, includeType, includeClientType, includeRequestHeaders, client, context));

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
		WebResponse webResponse;
		Map<String, List<String>> requestHeaders;
		FcHttpClient client;
		RequestContext context;
		boolean performInclude = true;
		String includeLevel = null;

		public IncludeResolutionPlaceholder(int startIdx, int endIdx, String includeURL, String includeType, String includeClientType, Map<String, List<String>> requestHeaders, FcHttpClient client, RequestContext context) {
			super();
			this.startIdx = startIdx;
			this.endIdx = endIdx;
			this.includeURL = includeURL;
			this.includeType = includeType;
			this.includeClientType = includeClientType;
			this.requestHeaders = requestHeaders;
			this.client = client;
			this.context = context;
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
}


