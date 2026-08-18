/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.reqlog;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import jakarta.servlet.http.HttpServletRequest;

import org.frontcache.core.FCHeaders;
import org.frontcache.core.FCUtils;
import org.frontcache.core.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  Log requests to file (slf4j) for statistics and further analysis
 *
 * 	REQUEST LOGGING FORMAT
 *  1 - true
 *  0 - false
 *
 *  cacheable_flag - true/1 if request is run through FrontCache engine (e.g. GET method, text data). false/0 - otherwise (request forwarded to origin)
 *  dynamic_flag{1|0} - true if origin has been requested. false/0 - otherwise (it's cacheable & cached).
 *
 *	log-timestamp request-id    domain   http-method   is-hystrix-error{success|error}   request-type {toplevel|include|include-async}   is-cacheable{cacheable|direct}   is-cached{dynamic|from-cache|dynamic-soft}     runtime-millis    datalength-bytes   url   client-IP    frontcache-ID    client-type{bot|browser}    user-agent
 *
 *  EXAMPLE
 *  2016-06-03T15:06:35,092-0600 1649b11f-8acf-4718-8e0b-abdcf7356212 example.com GET success toplevel cacheable from-cache 0 50874 "http://myfc.example.com:8080/en/coin_definition-1_Thaler-Silver-Kingdom_of_Prussia_(1701_1918)-c_sK.GJAIx4AAAEvnTTi7NnT.htm" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,100-0600 1649b11f-8acf-4718-8e0b-abdcf7356212 example.com GET success include cacheable from-cache 0 1581 "http://myfc.example.com:8080/fc/include-footer.htm?locale=en" 127.0.0.1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,105-0600 1649b11f-8acf-4718-8e0b-abdcf7356212 example.com GET success include-async cacheable from-cache 0 1411 "http://myfc.example.com:8080/fc/external-ads.htm?locale=" 127.0.0.1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,558-0600 e67a3f57-07f1-4fdb-91e1-e33313ba4185 example.com GET success toplevel direct dynamic 5 -1 "http://myfc.example.com:8080/follower?eid=c_sK.GJAIx4AAAEvnTTi7NnT&activity=COIN_GROUP_UPDATE&cmd=check" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,565-0600 66223049-3269-4de2-8d0e-a467b83a8390 example.com GET success toplevel cacheable dynamic 12 1676 "http://myfc.example.com:8080/fc/include-header.htm?view=desktop&locale=en" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,578-0600 cac3e17f-2084-4f18-b4f7-cb7ae6ee8a37 example.com GET success toplevel direct dynamic 2 -1 "http://myfc.example.com:8080/uinfo" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,741-0600 55ba8287-cedd-43b6-ae47-357292664cb3 example.com GET success toplevel cacheable dynamic 4 9662 "http://myfc.example.com:8080/favicon.ico" 0:0:0:0:0:0:0:1 front-cache-local-1 bot
 *
 *
 */
public class RequestLogger {

	private final static String SEPARATOR = " ";

	private static final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSZ");

	private static Logger logger = LoggerFactory.getLogger(RequestLogger.class);

	// dedicated logger for non-successful requests: guard-rule actions (reject / redirect / dry-run)
	// and hystrix fallbacks (short-circuited / timeout / rejected / failure). Routed to its own file
	// via logback (frontcache-failed-requests.log).
	private static Logger failedRequestLogger = LoggerFactory.getLogger("frontcache.failed-requests");

	private RequestLogger() {
	}

	/**
	 * Logs a request a guard rule acted on before it touched cache or origin - rejected (e.g. a 400
	 * for a malformed query), redirected (e.g. a scanner hitting the node by IP), or matched by a
	 * dry-run rule that only observed it. Written to the dedicated failed-requests log with the rule
	 * name as the reason and the sent HTTP status as the last two columns - NOT to error.log, so a
	 * high-volume bad-crawler flood stays filterable without drowning real errors.
	 *
	 * The trailing status column is written for guard actions only; the hystrix-fallback lines that
	 * {@link #logRequest} adds to the same file end at the reason (the response there is a normal
	 * - usually 200 - fallback response, whose status this logger does not read: includes are
	 * logged off the request thread, where HttpServletResponse is not safe to touch).
	 *
	 * @param url         request URL (host + URI + query)
	 * @param reason      guard rule name, e.g. "bad-request" / "ip-access"
	 * @param status      HTTP status sent to the client (0 for dry-run, where nothing was sent)
	 * @param disposition what happened: "rejected" | "redirected" | "dry-run" - written to the
	 *                    is_cached column, which is what the ELK pipeline buckets on
	 */
	public static void logGuardedRequest(String url, String reason, int status, String disposition, RequestContext context)
	{
		HttpServletRequest request = context.getRequest();
		String userAgent = (null != request) ? request.getHeader("User-Agent") : null;

		StringBuilder sb = new StringBuilder();
		sb.append(logTimeDateFormat.format(new Date()))
			.append(SEPARATOR).append(context.getRequestId())
			.append(SEPARATOR).append(context.getDomainContext().getDomain())
			.append(SEPARATOR).append((null != request) ? request.getMethod() : "-")
			.append(SEPARATOR).append("error")
			.append(SEPARATOR).append(context.getRequestType())
			.append(SEPARATOR).append("direct")
			.append(SEPARATOR).append(disposition)
			.append(SEPARATOR).append(0)   // runtimeMillis
			.append(SEPARATOR).append(-1)  // lengthBytes
			.append(SEPARATOR).append("\"").append(url).append("\"")
			.append(SEPARATOR).append("\"").append(FCUtils.getClientIP(request)).append("\"")
			.append(SEPARATOR).append(context.getFrontCacheId())
			.append(SEPARATOR).append(context.getClientType())
			.append(SEPARATOR).append("\"").append(userAgent).append("\"")
			.append(SEPARATOR).append("\"").append(reason).append("\"")
			.append(SEPARATOR).append(status);

		failedRequestLogger.trace(sb.toString());
		return;
	}

	/**
	 *
	 * @param url - request URL
	 * @param isCacheable - true if request is run through FrontCache engine (e.g. GET method, text data). false - otherwise (request forwarded to origin)
	 * @param isCached - true if origin has been cached. false/0 - otherwise (origin is called).
	 * @param runtimeMillis - runtime is milliseconds
	 * @param lengthBytes - content length in bytes.
	 */
	public static void logRequest(String url, boolean isCacheable, boolean isCached, long runtimeMillis, long lengthBytes, RequestContext context) {

		StringBuilder sb4file = new StringBuilder();
        HttpServletRequest request = context.getRequest();
        boolean isHystrixError = context.isHystrixFallback();
        String userAgent = request.getHeader("User-Agent");

        String requestTypeDynamicCachedSoft = (isCached) ? "from-cache" : "dynamic";
        if (!isCached && "true".equals(request.getHeader(FCHeaders.X_FRONTCACHE_SOFT_REFRESH)))
        	requestTypeDynamicCachedSoft = "dynamic-soft";

		sb4file.append(logTimeDateFormat.format(new Date()))
		.append(SEPARATOR).append(context.getRequestId())
		.append(SEPARATOR).append(context.getDomainContext().getDomain())
		.append(SEPARATOR).append(request.getMethod())
		.append(SEPARATOR).append((isHystrixError) ? "error" : "success")
		.append(SEPARATOR).append(context.getRequestType()) // toplevel | include | include-async
		.append(SEPARATOR).append((isCacheable) ? "cacheable" : "direct")
		.append(SEPARATOR).append(requestTypeDynamicCachedSoft)
		.append(SEPARATOR).append(runtimeMillis)
		.append(SEPARATOR).append(lengthBytes)
		.append(SEPARATOR).append("\"").append(url).append("\"")
		.append(SEPARATOR).append("\"").append(FCUtils.getClientIP(request)).append("\"")
		.append(SEPARATOR).append(context.getFrontCacheId())
		.append(SEPARATOR).append(context.getClientType())
		.append(SEPARATOR).append("\"").append(userAgent).append("\"");

		logger.trace(sb4file.toString());

		// also write non-successful requests to the dedicated failed-requests log, with the
		// failure category appended as the last column for easy filtering. No HTTP-status column
		// here - see logGuardedRequest(), which writes one for guard actions only.
		if (isHystrixError)
		{
			sb4file.append(SEPARATOR).append("\"").append(context.getHystrixReason()).append("\"");
			failedRequestLogger.trace(sb4file.toString());
		}

		return;
	}

	/**
	 *
	 * @param url
	 * @param requestType
	 * @param isCached
	 * @param softRefresh
	 * @param runtimeMillis
	 * @param lengthBytes
	 * @param context
	 * @param includeLevel
	 * @param dummy
	 */
	public static void logRequestToHeader(String url, String requestType, boolean isCached, boolean softRefresh, long runtimeMillis, long lengthBytes, RequestContext context, String includeLevel) {
        boolean isHystrixError = context.isHystrixFallback();
        String requestTypeDynamicCachedSoft = (isCached) ? "from-cache" : "dynamic";
        if (!isCached && softRefresh)
        	requestTypeDynamicCachedSoft = "dynamic-soft";

        if (context.getLogToHTTPHeaders())
        {
    		StringBuilder sb4header = new StringBuilder();
    		sb4header.append((isHystrixError) ? "error" : "success")
    		.append(SEPARATOR).append(requestType) // toplevel | include | include-async
    		.append(SEPARATOR).append(requestTypeDynamicCachedSoft)
    		.append(SEPARATOR).append(runtimeMillis)
    		.append(SEPARATOR).append(lengthBytes)
    		.append(SEPARATOR).append("\"").append(url).append("\"")
    		.append(SEPARATOR).append(context.getFrontCacheId())
//    		.append(SEPARATOR).append(dummy)
    		.append(SEPARATOR).append(context.getClientType());

    		// Never touch HttpServletResponse from here: includes are resolved on the include-processor's
    		// worker threads and the servlet response is not thread safe (concurrent setHeader() corrupts
    		// Jetty's HttpFields -> NPE in HttpFields$Mutable$Wrapper.put), while a late/timed-out include
    		// can hit an already committed/recycled response. Buffer instead; FrontCacheEngine flushes the
    		// buffer onto the response on the request thread. Anything arriving after the flush is simply
    		// dropped from the headers - it is still captured in the log files via logRequest().
    		context.getTraceHeaders().put(FCHeaders.X_FRONTCACHE_TRACE_REQUEST + "." + includeLevel, sb4header.toString());
        }

	}

}
