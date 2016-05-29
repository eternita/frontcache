package org.frontcache.reqlog;

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
 *	cacheable_flag{1|0}  dynamic_flag{1|0}   runtime_millis    datalength_bytes    url  clientIP frontcacheID
 *
 */
public class RequestLogger {

	private final static String SEPARATOR = " ";

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
	 */
	public static void logRequest(String url, boolean isCacheable, boolean isCached, long runtimeMillis, long lengthBytes, RequestContext context) {

		StringBuilder sb = new StringBuilder();
        HttpServletRequest request = context.getRequest();

		// FORMAT
		// dynamic_flag runtime_millis datalength_bytes url
		sb.append((isCacheable) ? 1 : 0)
		.append(SEPARATOR).append((isCached) ? 1 : 0)
		.append(SEPARATOR).append(runtimeMillis)
		.append(SEPARATOR).append(lengthBytes) 
		.append(SEPARATOR).append("\"").append(url).append("\"")
		.append(SEPARATOR).append(request.getRemoteAddr())
		.append(SEPARATOR).append(context.getFrontCacheId());
		

		logger.trace(sb.toString());

        if ("true".equalsIgnoreCase(request.getHeader(FCHeaders.X_FRONTCACHE_DEBUG)))
        {
    		HttpServletResponse servletResponse = context.getResponse();
    		servletResponse.setHeader(FCHeaders.X_FRONTCACHE_DEBUG_CACHEABLE, (isCacheable) ? "true" : "false");
    		servletResponse.setHeader(FCHeaders.X_FRONTCACHE_DEBUG_CACHED, (isCached) ? "true" : "false");
    		servletResponse.setHeader(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_TIME, "" + runtimeMillis);
    		servletResponse.setHeader(FCHeaders.X_FRONTCACHE_DEBUG_RESPONSE_SIZE, "" + lengthBytes);
        }
		
		return;
	}
}
