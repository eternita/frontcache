/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.resilience;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.resilience.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FC_ThroughCache_HttpClient extends FcCommand<WebResponse> {

	private String currentRequestURL = "open-circuit-default-key"; // when circuit is open
	private String originRequestURL = null;
	private final Map<String, List<String>> requestHeaders;
	private final HttpClient client;
	private final RequestContext context;
	// published by run(), read by getFallback() on the timeout watchdog thread - see getFallback()
	private volatile HttpGet pendingRequest = null;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_HttpClient.class);

    public FC_ThroughCache_HttpClient(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) {

        super(Setter
                .withGroupKey(context.getDomain())
                .andCommandKey("Cache-Origin-http")
                .andThreadPoolKey("OriginHitsPool"));

        this.originRequestURL = urlStr;
        this.currentRequestURL = context.getCurrentRequestURL();
        this.requestHeaders = requestHeaders;
        this.client = client;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		ClassicHttpResponse response = null;

		try {
			logger.debug("calling " + originRequestURL);
			HttpHost httpHost = FCUtils.getHttpHost(new URL(originRequestURL));
			HttpGet httpRequest = new HttpGet(FCUtils.buildRequestURI(originRequestURL));
			this.pendingRequest = httpRequest; // so getFallback() can abort it on timeout

			// translate headers
			Header[] httpHeaders = FCUtils.convertHeaders(requestHeaders);
			for (Header header : httpHeaders)
				httpRequest.addHeader(header);

			// Always advertise gzip (and only gzip) to the origin. Our HttpClient transparently
			// decodes gzip, so the cached content is always plaintext we can parse for includes and
			// never an encoding we can't handle (e.g. br) that would be cached and served wrongly.
			// buildRequestHeaders() normalizes this too; setting it here makes the cacheable origin
			// call self-contained, independent of the inbound headers.
			httpRequest.setHeader(FCHeaders.ACCEPT_ENCODING, "gzip");

			// executeOpen(), not execute(): the execute() overload returning an open response is
			// deprecated in 5.3 and this one is its replacement.
			response = client.executeOpen(httpHost, httpRequest, null);
			WebResponse webResp = FCUtils.httpResponse2WebComponent(originRequestURL, response, context);
			return webResp;

		} catch (IOException ioe) {
			logger.error("Can't read from " + originRequestURL, ioe);
			throw new FrontCacheException("Can't read from " + originRequestURL, ioe);
		} finally {
			if (null != response)
				try {
					response.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
		}

    }

    @Override
    protected WebResponse getFallback() {

		// Abort the origin call this fallback is replacing. interruptOnTimeout only sets the
		// interrupt flag, which a blocking socket read ignores - so without this the abandoned run()
		// keeps its pooled connection until the socket timeout expires (another full 10s), doubling
		// connection occupancy exactly when the origin is slow and the pool is already tight.
		// No-op when run() never started (SHORT_CIRCUITED / THREAD_POOL_REJECTED -> null) or when it
		// already completed (FAILURE); the result of an aborted run() is discarded either way.
		HttpGet pending = this.pendingRequest;
		if (null != pending)
			pending.abort();

		context.setFallbackUsed(FallbackLogger.failureType(getExecutionEvents()));

		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();

		String includeCurrentURL = getIncludeCurrentURL(currentRequestURL, originRequestURL);
		// pass the throwable as the last arg so SLF4J logs its stack trace (when present); for
		// timeout/short-circuit fallbacks getFailedExecutionException() is null -> message only.
		logger.error("FC_ThroughCache_HttpClient - ERROR FOR - " + includeCurrentURL + " / " + originRequestURL + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context, getFailedExecutionException());

		WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(this.getClass().getName(), includeCurrentURL);

		return webResponse;
    }

    /**
     *
     * @param currentRequestURL
     * @param originRequestURL
     * @return
     */
    private String getIncludeCurrentURL(String currentRequestURL, String originRequestURL)
    {
    	// host from current http://www.example.com/common/resilience/top1.jsp
    	// uri from original http://origin.example.com:1234/common/resilience/inc12.jsp
    	// ->
    	// includeCurrentURL http://www.example.com/common/resilience/inc12.jsp

    	String includeCurrentURL = null;

		int idx1 = currentRequestURL.indexOf("//");
		int idx2 = originRequestURL.indexOf("//");

		if (-1 < idx1 && -1 < idx2)
		{
			int idx11 = currentRequestURL.indexOf("/", idx1 + "//".length());
			int idx21 = originRequestURL.indexOf("/", idx2 + "//".length());

			if (-1 < idx11 && -1 < idx21)
			{
				includeCurrentURL = currentRequestURL.substring(0, idx11) + originRequestURL.substring(idx21);
			}
		}
		if (null == includeCurrentURL)
			includeCurrentURL = "can't get includeCurrentURL from " + currentRequestURL + " and " + originRequestURL;

		logger.debug("fallback includeCurrentURL " + includeCurrentURL + " based on currentRequestURL " + currentRequestURL + " and originRequestURL " + originRequestURL);

    	return includeCurrentURL;
    }

}