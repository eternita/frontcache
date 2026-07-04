/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.hystrix;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.FrontCacheException;
import org.frontcache.core.RequestContext;
import org.frontcache.core.WebResponse;
import org.frontcache.hystrix.fr.FallbackResolverFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixThreadPoolKey;

public class FC_ThroughCache_HttpClient extends HystrixCommand<WebResponse> {

	private String currentRequestURL = "open-circuit-default-key"; // when circuit is open
	private String originRequestURL = null;
	private final Map<String, List<String>> requestHeaders;
	private final HttpClient client;
	private final RequestContext context;
	private Logger logger = LoggerFactory.getLogger(FC_ThroughCache_HttpClient.class);

    public FC_ThroughCache_HttpClient(String urlStr, Map<String, List<String>> requestHeaders, HttpClient client, RequestContext context) {

        super(Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey(context.getDomainContext().getDomain()))
                .andCommandKey(HystrixCommandKey.Factory.asKey("Cache-Origin-http"))
                .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("OriginHitsPool")));

        this.originRequestURL = urlStr;
        this.currentRequestURL = context.getCurrentRequestURL();
        this.requestHeaders = requestHeaders;
        this.client = client;
        this.context = context;
    }

    @Override
    protected WebResponse run() throws FrontCacheException {
		HttpResponse response = null;

		try {
			logger.debug("calling " + originRequestURL);
			HttpHost httpHost = FCUtils.getHttpHost(new URL(originRequestURL));
			HttpRequest httpRequest = new HttpGet(FCUtils.buildRequestURI(originRequestURL));

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

			response = client.execute(httpHost, httpRequest);
			WebResponse webResp = FCUtils.httpResponse2WebComponent(originRequestURL, response, context);
			return webResp;

		} catch (IOException ioe) {
			logger.error("Can't read from " + originRequestURL, ioe);
			throw new FrontCacheException("Can't read from " + originRequestURL, ioe);
		} finally {
			if (null != response)
				try {
					((CloseableHttpResponse) response).close();
				} catch (IOException e) {
					e.printStackTrace();
				}
		}

    }

    @Override
    protected WebResponse getFallback() {
		context.setHystrixFallback(FallbackLogger.failureType(getExecutionEvents()));

		String failedExceptionMessage = "";
		if (null != getFailedExecutionException())
			failedExceptionMessage += getFailedExecutionException().getMessage();

		String includeCurrentURL = getIncludeCurrentURL(currentRequestURL, originRequestURL);
		// pass the throwable as the last arg so SLF4J logs its stack trace (when present); for
		// timeout/short-circuit fallbacks getFailedExecutionException() is null -> message only.
		logger.error("FC_ThroughCache_HttpClient - ERROR FOR - " + includeCurrentURL + " / " + originRequestURL + " " + failedExceptionMessage + ", Events " + getExecutionEvents() + ", " + context, getFailedExecutionException());

		WebResponse webResponse = FallbackResolverFactory.getInstance().getFallback(context.getDomainContext(), this.getClass().getName(), includeCurrentURL);

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
    	// host from current http://www.example-site.com/common/hystrix/top1.jsp
    	// uri from original http://origin.example-site.com:1234/common/hystrix/inc12.jsp
    	// ->
    	// includeCurrentURL http://www.example-site.com/common/hystrix/inc12.jsp

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