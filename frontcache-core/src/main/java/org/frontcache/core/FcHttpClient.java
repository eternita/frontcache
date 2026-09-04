/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.core;

import java.util.List;
import java.util.Map;

/**
 * Frontcache's own origin-fetch abstraction - the extension points' view of "go get this URL".
 *
 * Until 2.8 the three reflection-loaded SPIs ({@code CacheProcessor}, {@code IncludeProcessor},
 * {@code FallbackResolver}) took an Apache HttpClient in their signatures - the 4.5-era
 * {@code org.apache.http.client.HttpClient}. That leaked a third-party type into a public contract:
 * the HttpClient 4.5 -&gt; 5.x upgrade broke every implementation, and so would the next such upgrade.
 *
 * The parameter was only ever threaded through to three leaf calls, and all three mean the same
 * thing - fetch a URL, hand back a {@link WebResponse}. Nothing needed a connection pool, a route
 * planner or an entity. So the SPIs take this instead, and the Apache types stay inside
 * {@link ApacheFcHttpClient} and the resilience commands. See
 * docs/archive/httpclient5-upgrade-proposal.md section 5.1.
 *
 * The three methods are not redundant - each corresponds to one of those leaf uses, and they differ
 * in ways a caller cannot paper over (which transport, whether a circuit breaker is involved, and
 * whether a non-2xx is an exception or a result).
 *
 * Implementations are supplied by Frontcache; third parties consume this, they do not implement it.
 */
public interface FcHttpClient {

	/**
	 * Fetch through whichever origin transport this node uses: an HTTP call in standalone mode, the
	 * origin app's own filter chain in webfilter mode. Resilience-wrapped (circuit breaker, bulkhead,
	 * time limiter, fallback).
	 *
	 * @param urlStr         absolute origin URL
	 * @param requestHeaders headers to forward; the caller owns the map and it may be mutated
	 * @param context        the per-request context this fetch belongs to
	 * @return the origin response as a cacheable {@link WebResponse}
	 */
	WebResponse fetch(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
			throws FrontCacheException;

	/**
	 * Fetch over HTTP specifically, never the filter chain. Includes always use this: an include is a
	 * different URL from the one being served, so re-running the current request's filter chain would
	 * return the wrong page. Resilience-wrapped like {@link #fetch}.
	 */
	WebResponse fetchOverHttp(String urlStr, Map<String, List<String>> requestHeaders, RequestContext context)
			throws FrontCacheException;

	/**
	 * One-shot GET with no resilience wrapper and no request context - for bootstrap-time downloads
	 * such as prefetching fallback pages. The body is materialised into the returned
	 * {@link WebResponse}, which is fine for the small, few, once-per-startup fetches this is for.
	 *
	 * Unlike the two request-path methods, a non-2xx status is a normal return, not an exception:
	 * callers decide what a 404 on a fallback page means.
	 */
	WebResponse fetch(String urlStr) throws FrontCacheException;
}
