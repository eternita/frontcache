package org.frontcache.reqlog;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frontcache.core.FCHeaders;
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
 *  2016-06-03T15:06:35,092-0600 1649b11f-8acf-4718-8e0b-abdcf7356212 coinshome.net GET success toplevel cacheable from-cache 0 50874 "http://myfc.coinshome.net:8080/en/coin_definition-1_Thaler-Silver-Kingdom_of_Prussia_(1701_1918)-c_sK.GJAIx4AAAEvnTTi7NnT.htm" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,100-0600 1649b11f-8acf-4718-8e0b-abdcf7356212 coinshome.net GET success include cacheable from-cache 0 1581 "http://myfc.coinshome.net:8080/fc/include-footer.htm?locale=en" 127.0.0.1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,105-0600 1649b11f-8acf-4718-8e0b-abdcf7356212 coinshome.net GET success include-async cacheable from-cache 0 1411 "http://myfc.coinshome.net:8080/fc/external-ads.htm?locale=" 127.0.0.1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,558-0600 e67a3f57-07f1-4fdb-91e1-e33313ba4185 coinshome.net GET success toplevel direct dynamic 5 -1 "http://myfc.coinshome.net:8080/follower?eid=c_sK.GJAIx4AAAEvnTTi7NnT&activity=COIN_GROUP_UPDATE&cmd=check" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,565-0600 66223049-3269-4de2-8d0e-a467b83a8390 coinshome.net GET success toplevel cacheable dynamic 12 1676 "http://myfc.coinshome.net:8080/fc/include-header.htm?view=desktop&locale=en" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,578-0600 cac3e17f-2084-4f18-b4f7-cb7ae6ee8a37 coinshome.net GET success toplevel direct dynamic 2 -1 "http://myfc.coinshome.net:8080/uinfo" 0:0:0:0:0:0:0:1 front-cache-local-1 browser
 *  2016-06-03T15:06:35,741-0600 55ba8287-cedd-43b6-ae47-357292664cb3 coinshome.net GET success toplevel cacheable dynamic 4 9662 "http://myfc.coinshome.net:8080/favicon.ico" 0:0:0:0:0:0:0:1 front-cache-local-1 bot
 *
 *
 */
public class RequestLogger {

	private final static String SEPARATOR = " ";
	
	private static final DateFormat logTimeDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss,SSSZ");

	private static Logger logger = LoggerFactory.getLogger(RequestLogger.class);

	private RequestLogger() {
	}

	/**
	 * 
	 * @param url - request URL
	 * @param isCacheable - true if request is run through FrontCache engine (e.g. GET method, text data). false - otherwise (request forwarded to origin)
	 * @param isCached - true if origin has been cached. false/0 - otherwise (origin is called).
	 * @param runtimeMillis - runtime is milliseconds
	 * @param lengthBytes - content length in bytes. 
	 * @param includeLevel
	 */
	public static void logRequest(String url, boolean isCacheable, boolean isCached, long runtimeMillis, long lengthBytes, RequestContext context, String includeLevel) {

		StringBuilder sb4file = new StringBuilder();
        HttpServletRequest request = context.getRequest();
        boolean isHystrixError = context.getHystrixError();
        String userAgent = request.getHeader("User-Agent");
        
        String requestTypeDynamicCachedSoft = (isCached) ? "from-cache" : "dynamic";
        if (!isCached && "true".equals(request.getHeader(FCHeaders.X_FRONTCACHE_SOFT_REFRESH)))
        	requestTypeDynamicCachedSoft = "dynamic-soft";

		sb4file.append(logTimeDateFormat.format(new Date()))
		.append(SEPARATOR).append(context.getRequestId())
		.append(SEPARATOR).append(includeLevel)
		.append(SEPARATOR).append(context.getDomainContext().getDomain())
		.append(SEPARATOR).append(request.getMethod())
		.append(SEPARATOR).append((isHystrixError) ? "error" : "success")
		.append(SEPARATOR).append(context.getRequestType()) // toplevel | include | include-async
		.append(SEPARATOR).append((isCacheable) ? "cacheable" : "direct")
		.append(SEPARATOR).append(requestTypeDynamicCachedSoft)
		.append(SEPARATOR).append(runtimeMillis)
		.append(SEPARATOR).append(lengthBytes) 
		.append(SEPARATOR).append("\"").append(url).append("\"")
		.append(SEPARATOR).append(request.getRemoteAddr())
		.append(SEPARATOR).append(context.getFrontCacheId())
		.append(SEPARATOR).append(context.getClientType())
		.append(SEPARATOR).append("\"").append(userAgent).append("\"");

		logger.trace(sb4file.toString());
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
        boolean isHystrixError = context.getHystrixError();
        String requestTypeDynamicCachedSoft = (isCached) ? "from-cache" : "dynamic";
        if (!isCached && softRefresh)
        	requestTypeDynamicCachedSoft = "dynamic-soft";
        
        if (context.getLogToHTTPHeaders())
        {
    		HttpServletResponse servletResponse = context.getResponse();
    		
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
    		
    		servletResponse.setHeader(FCHeaders.X_FRONTCACHE_DEBUG_REQUEST + "." + includeLevel, sb4header.toString());
        }
        
	}
	
}
